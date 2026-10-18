# Quiet — build plan

Each phase ends with a push to `main` and a green GitHub Actions build before the next starts.
The workflow publishes `quiet.apk` to the `latest` release, so every green phase can be installed.

## Phase 0 — Build and install pipeline
- [x] Gradle project (AGP, Kotlin, Compose compiler plugin, version catalog, wrapper)
- [x] Minimal `MainActivity` registered as HOME/LEANBACK launcher, landscape, black window
- [x] Release keystore committed (`keystore/quiet.jks`), signingConfig wired into `release`
- [x] R8 + resource shrinking on release; `profileinstaller`
- [x] `.github/workflows/build.yml`: build on every push; on `main` replace release `latest` with `quiet.apk`
- [x] `INSTALL.md` (Downloader app, short URL, updating, set as default, accessibility)
- [x] Push → CI green

## Phase 1 — Home screen (offline)
- [x] Fonts: Manrope (ExtraLight, Regular, SemiBold) + Instrument Serif Italic, OFL license
- [x] 8 bundled 1920×1080 landscape images in assets with time tags
- [x] Background: crossfade every 60 s, Ken Burns 45 s (1.04 → 1.14 with drift), scrims
- [x] Clock (tabular numerals, 148 px) + date (Instrument Serif Italic 34 px)
- [x] App tile grid 4×2, 120 px, radius 20, gap 22; banners/icons from PackageManager
- [x] Frosted tiles: one pre-blurred, downscaled copy of each photo, cropped per tile
- [x] Focus: near-white, scale 1.1, accent glow, 280 ms
- [x] All apps screen; Settings tile opens system settings
- [x] Push → CI green

## Phase 2 — Photos mix + accent
- [x] `photos.json` in repo; WorkManager fetch (daily, network constraint) → downscale → disk cache
- [x] Time-aware playlist (morning/day/night) mixing cached + bundled
- [x] Palette accent per photo (light muted), animated across weather/tag/dots/glow
- [x] Progress dots top-right
- [x] Push → CI green

## Phase 3 — Weather, news, settings
- [x] Open-Meteo current weather every 30 min (cache last value to prefs for instant start)
- [x] RSS headlines (BBC default) every 15 min, rotating pill every 60 s
- [x] Quiet settings screen: location (city search / lat-lon), units, RSS URL
- [x] Push → CI green

## Phase 4 — Idle + night
- [x] Idle after 3 min of no keys: grid fades, compact info bottom-left, keep screen on
- [x] Night mode 22:00–06:00: dim + desaturate, amber overlay, softer text/tiles, moon icon
- [x] Push → CI green

## Phase 5 — Tile swap + home takeover
- [x] Long-press tile → app picker → persisted slot mapping
- [x] AccessibilityService: stock launcher in foreground → start Quiet
- [x] Onboarding screen with step-by-step instructions + deep link to accessibility settings
- [x] Push → CI green

## Phase 6 — ESPN fantasy overlay
- [ ] Settings: enable, league ID, team ID, espn_s2, SWID, always-show
- [ ] Overlay view via TYPE_ACCESSIBILITY_OVERLAY (not focusable/touchable), top-right text
- [ ] ESPN fetch every 60 s while visible during game windows only
- [ ] Push → CI green

## Review
(filled in at the end)
