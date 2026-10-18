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
- [x] Settings: enable, league ID, team ID, espn_s2, SWID, always-show
- [x] Overlay view via TYPE_ACCESSIBILITY_OVERLAY (not focusable/touchable), top-right text
- [x] ESPN fetch every 60 s while visible during game windows only
- [x] Push → CI green

## Phase 7 — Live NFL scores + fantasy upgrades
- [ ] ESPN public NFL scoreboard (no key); live/just-final games rotate into the headline pill as "NFL"
- [ ] Polls every 2 min only while a game is live and Quiet is visible
- [ ] Overlay: starters playing / yet to play (roster + scoreboard game state)
- [ ] Overlay: accent highlight + "Took the lead" / "Lost the lead" for 15 s on a lead change
- [ ] Push → CI green

## Phase 8 — More headlines, more detail
- [ ] Keep RSS summaries and publish time
- [ ] Headline pill is focusable; OK opens a news panel (list with summaries, source, time)
- [ ] QR code for the selected story so it opens on the phone
- [ ] Push → CI green

## Phase 9 — Weather glance
- [ ] Open-Meteo hourly (12 h) + daily (3 days) in the same request
- [ ] Weather is focusable; OK opens a forecast card
- [ ] Push → CI green

## Phase 10 — Live Santa Monica scene
- [ ] Render on the TV: sky from real solar elevation (lat/lon + time), sun/moon, Santa Monica Mountains, ocean, pier + Ferris wheel, night lights
- [ ] Weather layers from the current code: clouds, overcast, fog (marine layer), rain
- [ ] Re-render every 10 min off the main thread; feeds the same backdrop (Ken Burns, frost, accent)
- [ ] Settings: Background = Live scene (default) / Photos
- [ ] Push → CI green

## Review

**Status:** all 7 phases (0–6) compile and publish in CI. The release asset is at
`releases/latest/download/quiet.apk`.

**How each phase was checked:** this sandbox can't reach Google's Maven or the Android SDK, so
GitHub Actions is the only compile gate. Each phase was pushed to `main` and had to build
green before the next phase was pushed. Exceptions: Phases 4 and 5 went out in the same push
as a Phase 3 compile fix, so 3–5 were verified together (build 7). Phase 6 was verified on
its own (build 8). Nothing has run on a real TV yet.

**CI failures and fixes:**
- `android:resumeWhilePaused` is a hidden attribute that AAPT rejects. Removed.
- A fully qualified call to the `Modifier.composed` extension doesn't resolve. Now imported.
- The `idle` property setter clashed with a hand-written `setIdle()` on the JVM. Renamed to `updateIdle()`.

**Not verified yet (needs the TV):**
- Cold start under 500 ms. The design supports it: a black window, no network or disk I/O
  before the first frame, the photo decoded off the main thread, WorkManager started
  lazily, R8, and a baseline profile. It hasn't been measured.
- Focus smoothness, and how long-press on OK behaves on the TCL remote.
- How the accessibility takeover behaves on this Google TV build. Restricted settings may apply
  on Android TV 14.
- ESPN API field names. They come from community docs of the unofficial API.
- The Unsplash photo IDs in `photos.json`. Any dead link is skipped.
