# Quiet — build plan

Each phase ends with a push to `main` and a green GitHub Actions build before the next starts.
The workflow publishes `quiet.apk` to the `latest` release, so every green phase can be installed.

## Phase 0 — Build and install pipeline
- [ ] Gradle project (AGP, Kotlin, Compose compiler plugin, version catalog, wrapper)
- [ ] Minimal `MainActivity` registered as HOME/LEANBACK launcher, landscape, black window
- [ ] Release keystore committed (`keystore/quiet.jks`), signingConfig wired into `release`
- [ ] R8 + resource shrinking on release; `profileinstaller`
- [ ] `.github/workflows/build.yml`: build on every push; on `main` replace release `latest` with `quiet.apk`
- [ ] `INSTALL.md` (Downloader app, short URL, updating, set as default, accessibility)
- [ ] Push → CI green

## Phase 1 — Home screen (offline)
- [ ] Fonts: Manrope (ExtraLight, Regular, SemiBold) + Instrument Serif Italic, OFL license
- [ ] 8 bundled 1920×1080 landscape images in assets with time tags
- [ ] Background: crossfade every 60 s, Ken Burns 45 s (1.04 → 1.14 with drift), scrims
- [ ] Clock (tabular numerals, 148 px) + date (Instrument Serif Italic 34 px)
- [ ] App tile grid 4×2, 120 px, radius 20, gap 22; banners/icons from PackageManager
- [ ] Frosted tiles: one pre-blurred, downscaled copy of each photo, cropped per tile
- [ ] Focus: near-white, scale 1.1, accent glow, 280 ms
- [ ] All apps screen; Settings tile opens system settings
- [ ] Push → CI green

## Phase 2 — Photos mix + accent
- [ ] `photos.json` in repo; WorkManager fetch (daily, network constraint) → downscale → disk cache
- [ ] Time-aware playlist (morning/day/night) mixing cached + bundled
- [ ] Palette accent per photo (light muted), animated across weather/tag/dots/glow
- [ ] Progress dots top-right
- [ ] Push → CI green

## Phase 3 — Weather, news, settings
- [ ] Open-Meteo current weather every 30 min (cache last value to prefs for instant start)
- [ ] RSS headlines (BBC default) every 15 min, rotating pill every 60 s
- [ ] Quiet settings screen: location (city search / lat-lon), units, RSS URL
- [ ] Push → CI green

## Phase 4 — Idle + night
- [ ] Idle after 3 min of no keys: grid fades, compact info bottom-left, keep screen on
- [ ] Night mode 22:00–06:00: dim + desaturate, amber overlay, softer text/tiles, moon icon
- [ ] Push → CI green

## Phase 5 — Tile swap + home takeover
- [ ] Long-press tile → app picker → persisted slot mapping
- [ ] AccessibilityService: stock launcher in foreground → start Quiet
- [ ] Onboarding screen with step-by-step instructions + deep link to accessibility settings
- [ ] Push → CI green

## Phase 6 — ESPN fantasy overlay
- [ ] Settings: enable, league ID, team ID, espn_s2, SWID, always-show
- [ ] Overlay view via TYPE_ACCESSIBILITY_OVERLAY (not focusable/touchable), top-right text
- [ ] ESPN fetch every 60 s while visible during game windows only
- [ ] Push → CI green

## Review
(filled in at the end)
