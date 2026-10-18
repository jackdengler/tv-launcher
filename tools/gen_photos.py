"""Generates the 8 bundled fallback backgrounds (1920x1080 JPEG) into app/src/main/assets/photos.

Layered-landscape renders: graded sky, sun/moon glow, fractal ridgelines with atmospheric
perspective, optional water reflection, stars and film grain. Deterministic (fixed seeds).
Run: python3 tools/gen_photos.py
"""
import json
import os

import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "photos")


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32) / 255.0


def gradient(stops, n):
    """stops: [(pos 0..1, '#rrggbb'), ...] -> (n, 3)"""
    pos = np.array([p for p, _ in stops])
    cols = np.stack([hexc(c) for _, c in stops])
    t = np.linspace(0, 1, n)
    return np.stack([np.interp(t, pos, cols[:, k]) for k in range(3)], axis=1)


def ridge(rng, base, amp, rough, smooth=1.0):
    """Fractal 1-D ridgeline (pixel y for each x)."""
    x = np.linspace(0, 1, W)
    y = np.zeros(W)
    a, f = amp, 1.5
    for _ in range(7):
        phase = rng.uniform(0, 2 * np.pi)
        y += a * np.sin(2 * np.pi * f * x + phase) * (0.6 + 0.4 * rng.uniform())
        y += a * 0.5 * np.sin(2 * np.pi * f * 1.7 * x + rng.uniform(0, 6.28))
        a *= rough
        f *= 2.1
    if smooth > 1:
        k = int(smooth)
        y = np.convolve(np.pad(y, k, mode="edge"), np.ones(k) / k, mode="same")[k:-k]
    return base * H + y


def glow(img, cx, cy, radius, color, strength):
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / radius
    g = np.exp(-d ** 2)[..., None] * strength
    return img + g * hexc(color)


def disc(img, cx, cy, r, color, soft=2.0):
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    m = np.clip((r - d) / soft + 0.5, 0, 1)[..., None]
    return img * (1 - m) + hexc(color) * m


def fill_below(img, line, color, haze_color=None, haze=0.0, shade=0.0):
    yy = np.arange(H)[:, None]
    m = np.clip(yy - line[None, :] + 0.5, 0, 1)[..., None]
    col = hexc(color)
    layer = np.broadcast_to(col, img.shape).copy()
    if shade:
        # Darken with depth below the ridge for a little volume.
        depth = np.clip((yy - line[None, :]) / (H * 0.35), 0, 1)[..., None]
        layer = layer * (1 - shade * depth)
    if haze_color is not None and haze:
        layer = layer * (1 - haze) + hexc(haze_color) * haze
    return img * (1 - m) + layer * m


def fill_band(img, line, bottom, color):
    """Fill between a ridgeline and a fixed y (a distant shore above water)."""
    yy = np.arange(H)[:, None]
    m = (np.clip(yy - line[None, :] + 0.5, 0, 1) * (yy < bottom))[..., None]
    return img * (1 - m) + hexc(color) * m


def stars(img, rng, count, max_y):
    for _ in range(count):
        x, y = rng.integers(0, W), rng.integers(0, int(max_y))
        b = rng.uniform(0.3, 1.0) * (1 - y / max_y) ** 0.5
        r = 1 if rng.uniform() < 0.9 else 2
        img[max(0, y - r + 1):y + r, max(0, x - r + 1):x + r] += b
    return img


def water(img, horizon, tint, strength=0.6):
    """Mirror everything above the horizon into the lower part with ripples and a tint."""
    h = int(horizon)
    below = H - h
    src_rows = np.clip(2 * h - np.arange(h, H), 0, h - 1)
    refl = img[src_rows].copy()
    rng = np.random.default_rng(7)
    for i in range(below):
        shift = int(6 * np.sin(i * 0.35) * (i / below))
        refl[i] = np.roll(refl[i], shift + rng.integers(-2, 3), axis=0)
    t = np.linspace(0, 1, below)[:, None, None]
    refl = refl * (1 - strength * (0.4 + 0.6 * t)) + hexc(tint) * strength * (0.4 + 0.6 * t)
    img[h:] = refl
    return img


def finish(img, rng, name, blur=0.6, grain=0.018, vignette=0.35):
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2)
    img = img * (1 - vignette * np.clip(d - 0.5, 0, 1)[..., None])
    img = img + rng.normal(0, grain, img.shape)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    im = im.filter(ImageFilter.GaussianBlur(blur))
    im.save(os.path.join(OUT, name), quality=86, optimize=True, progressive=True)


def sky(stops):
    g = gradient(stops, H)
    return np.repeat(g[:, None, :], W, axis=1).astype(np.float64)


def scene_dawn_peaks():
    rng = np.random.default_rng(1)
    img = sky([(0, "#2b3a5c"), (0.35, "#8d7ca6"), (0.58, "#f2b38a"), (0.7, "#ffd9a8"), (1, "#ffe7c2")])
    img = glow(img, 1250, 640, 380, "#ffcf9a", 0.55)
    layers = [(0.56, 60, "#b59aa8", 0.55), (0.62, 80, "#8a7590", 0.35), (0.7, 90, "#5d5070", 0.15), (0.8, 70, "#302a42", 0)]
    for base, amp, col, hz in layers:
        img = fill_below(img, ridge(rng, base, amp, 0.55), col, "#f2c3a0", hz, shade=0.15)
    finish(img, rng, "dawn_peaks.jpg")


def scene_misty_lake():
    rng = np.random.default_rng(2)
    img = sky([(0, "#7c93a8"), (0.45, "#c7d1d6"), (0.6, "#e9e3d8"), (1, "#e9e3d8")])
    img = glow(img, 560, 520, 300, "#fff3dd", 0.35)
    for base, amp, col, hz in [(0.5, 40, "#9fadb4", 0.5), (0.55, 55, "#6f8590", 0.3), (0.6, 35, "#3c5059", 0.1)]:
        img = fill_below(img, ridge(rng, base, amp, 0.5, smooth=9), col, "#dfe3e2", hz)
    img = water(img, 0.64 * H, "#95a7af", 0.45)
    # Mist band on the water line.
    yy = np.arange(H)[:, None, None]
    img = img + 0.22 * np.exp(-((yy - 0.64 * H) / 40.0) ** 2) * hexc("#f4f1ea")
    finish(img, rng, "misty_lake.jpg", blur=0.9)


def scene_dunes():
    rng = np.random.default_rng(3)
    img = sky([(0, "#3f7fb8"), (0.4, "#86b7d9"), (0.6, "#d8e4e6"), (1, "#f0d7b0")])
    img = glow(img, 1500, 180, 260, "#ffffff", 0.35)
    for base, amp, col in [(0.6, 30, "#e2b98a"), (0.68, 45, "#d29a62"), (0.78, 50, "#bb7d47"), (0.9, 40, "#9a5f33")]:
        line = ridge(rng, base, amp, 0.42, smooth=31)
        img = fill_below(img, line, col, shade=0.35)
    finish(img, rng, "desert_dunes.jpg")


def scene_alpine_day():
    rng = np.random.default_rng(4)
    img = sky([(0, "#2f6fa8"), (0.5, "#8fc0e0"), (0.62, "#d6e6ee"), (1, "#d6e6ee")])
    img = fill_below(img, ridge(rng, 0.42, 110, 0.5, smooth=7), "#e9eef2", "#cfe0ea", 0.25, shade=0.12)
    img = fill_below(img, ridge(rng, 0.55, 90, 0.5, smooth=5), "#7d93a3", "#c6d9e4", 0.35, shade=0.15)
    img = fill_below(img, ridge(rng, 0.68, 60, 0.5, smooth=5), "#3f6b4f", shade=0.35)
    img = fill_below(img, ridge(rng, 0.82, 40, 0.6), "#24432f", shade=0.3)
    finish(img, rng, "alpine_day.jpg")


def scene_coast():
    rng = np.random.default_rng(5)
    img = sky([(0, "#3d7bb0"), (0.45, "#9cc6de"), (0.6, "#e6eff0"), (1, "#e6eff0")])
    img = glow(img, 420, 260, 360, "#ffffff", 0.3)
    horizon = 0.6 * H
    img = fill_band(img, ridge(rng, 0.545, 22, 0.5, smooth=25), horizon, "#8fa6b3")
    img = fill_band(img, ridge(rng, 0.575, 18, 0.5, smooth=25), horizon, "#61798a")
    img = water(img, horizon, "#2d6a88", 0.7)
    # Sunlit glitter on the water under the sun.
    yy, xx = np.mgrid[0:H, 0:W]
    spark = (rng.uniform(size=(H, W)) > 0.985) * np.exp(-((xx - 420) / 260.0) ** 2) * (yy > horizon)
    img = img + spark[..., None] * 0.5
    finish(img, rng, "coastal_bay.jpg")


def scene_sunset_hills():
    rng = np.random.default_rng(6)
    img = sky([(0, "#1d1f3d"), (0.3, "#58407a"), (0.5, "#d46a5e"), (0.62, "#f6a25a"), (1, "#f9c27a")])
    img = glow(img, 860, 640, 420, "#ffb36b", 0.6)
    img = disc(img, 860, 640, 46, "#ffe2b0", soft=3)
    for base, amp, col in [(0.62, 35, "#9a4f5c"), (0.68, 45, "#6d3550"), (0.76, 50, "#42233d"), (0.86, 40, "#1e1226")]:
        img = fill_below(img, ridge(rng, base, amp, 0.45, smooth=21), col)
    finish(img, rng, "sunset_hills.jpg")


def scene_starry_peaks():
    rng = np.random.default_rng(7)
    img = sky([(0, "#05070f"), (0.45, "#0e1a33"), (0.7, "#1f3350"), (1, "#2a4060")])
    img = stars(img, rng, 1400, H * 0.7)
    # Faint milky-way band.
    yy, xx = np.mgrid[0:H, 0:W]
    band = np.exp(-(((yy - (0.9 * xx * 0.35 + 80)) / 120.0) ** 2))[..., None]
    img = img + band * 0.08 * hexc("#c9d6ff")
    for base, amp, col in [(0.58, 110, "#1b2a40"), (0.7, 80, "#111c2c"), (0.82, 60, "#070c15")]:
        img = fill_below(img, ridge(rng, base, amp, 0.6), col)
    finish(img, rng, "starry_peaks.jpg", blur=0.4, grain=0.012)


def scene_moonlit_sea():
    rng = np.random.default_rng(8)
    img = sky([(0, "#070b18"), (0.5, "#1a2745"), (0.62, "#36486c"), (1, "#36486c")])
    img = stars(img, rng, 700, H * 0.5)
    img = glow(img, 1380, 260, 260, "#9fb4dd", 0.35)
    img = disc(img, 1380, 260, 38, "#f1efe6", soft=2)
    img = fill_band(img, ridge(rng, 0.6, 14, 0.5, smooth=15), 0.62 * H, "#0b1222")
    img = water(img, 0.62 * H, "#0d1628", 0.55)
    # Moon path on the water.
    yy, xx = np.mgrid[0:H, 0:W]
    path = np.exp(-((xx - 1380) / (30 + (yy - 0.62 * H).clip(0) * 0.25)) ** 2) * (yy > 0.62 * H)
    flick = (np.sin(yy * 0.9) > 0.2)
    img = img + (path * flick)[..., None] * 0.35 * hexc("#dfe6f5")
    finish(img, rng, "moonlit_sea.jpg", blur=0.5, grain=0.012)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    scenes = [
        (scene_dawn_peaks, "dawn_peaks.jpg", ["morning"]),
        (scene_misty_lake, "misty_lake.jpg", ["morning"]),
        (scene_dunes, "desert_dunes.jpg", ["day"]),
        (scene_alpine_day, "alpine_day.jpg", ["day"]),
        (scene_coast, "coastal_bay.jpg", ["day"]),
        (scene_sunset_hills, "sunset_hills.jpg", ["night", "day"]),
        (scene_starry_peaks, "starry_peaks.jpg", ["night"]),
        (scene_moonlit_sea, "moonlit_sea.jpg", ["night"]),
    ]
    manifest = []
    for fn, name, tags in scenes:
        fn()
        manifest.append({"file": name, "tags": tags})
        print("wrote", name)
    with open(os.path.join(OUT, "bundled.json"), "w") as f:
        json.dump({"photos": manifest}, f, indent=2)
