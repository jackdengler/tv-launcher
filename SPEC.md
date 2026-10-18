# Quiet — a custom launcher for TCL Google TV (Android TV OS)

This is the original spec, saved as written. It ends partway through the ESPN
overlay section. The assumptions used to finish that section are listed at the
end, under **Clarifications and assumptions**.

## Constraints
- I'm on an iPhone with no computer. No adb, ever. Everything must install and configure from the TV or my phone.
- Kotlin + Jetpack Compose for TV (androidx.tv). minSdk 28, landscape 1080p.
- Speed is the #1 priority: cold start under 500 ms, no jank moving focus, zero network calls on the startup path, no Google Play Services, no analytics. Keep dependencies minimal.
- No bloat: background work only via WorkManager or coroutines that stop when idle.

## Build and install (phase 0)
- GitHub Actions workflow builds a signed release APK on every push to main and publishes it as release "latest" with the asset name quiet.apk, so this URL never changes:
  https://github.com/<owner>/<repo>/releases/latest/download/quiet.apk
- Generate one keystore and commit it so every build has the same signature (updates must install over the old version). The repo is public and holds no secrets.
- I install with the "Downloader" app on the TV. Write INSTALL.md for that.

## Home screen
- Full-screen rotating photo background, crossfading every 60 s, with a slow Ken Burns zoom/drift (about 45 s, scale 1.04 to 1.14). Dark scrims: radial top-left, gradient at the bottom.
- Top left: clock "10:59" in Manrope ExtraLight at about 148 px (tabular numerals). Below it, the date in Instrument Serif Italic about 34 px, and weather inline in the accent color ("62° · Clear" with an icon).
- Under that, one news headline in a small frosted pill with a source tag, rotating every 60 s.
- Top right: small photo-progress dots (the active one is a wide pill in the accent color).
- Bottom center: 4x2 grid of 120 px tiles, radius 20, gap 22. Order: YouTube TV, Paramount+, HBO Max, Disney+, Netflix, YouTube, All apps, Settings. Use each app's real banner or icon from PackageManager.
- Tiles are frosted glass: draw a pre-blurred, cropped copy of the current photo behind them (no real-time blur). The focused tile is near-white, scales 1.1, and has an accent-colored glow, animated in 280 ms.
- All apps: a simple grid of installed launchable apps. Settings opens system Settings. Long-press a tile to swap in a different app.
- Bundle the Manrope and Instrument Serif fonts (OFL).

## Photos (mix)
- Bundle 8 landscape photos in the APK as an offline fallback.
- Also fetch photos.json from this repo (raw.githubusercontent URL) listing image URLs. Download, downscale to 1920x1080, and cache to disk. Never block the UI.
- Time-aware: prefer photos tagged morning, day, or night by hour.
- Accent color: extract it from each photo with androidx.palette (a light, muted tone), then apply it to the weather, headline tag, dots, and focus glow.

## Weather and news
- Weather: Open-Meteo (free, no key), refreshed every 30 min. Location comes from settings.
- Headlines: BBC News RSS by default (configurable), refreshed every 15 min.

## Idle mode
- After 3 min with no input on the home screen, fade the grid out. The photo goes full screen with a smaller clock, date, weather, and headline at the bottom left. Any key restores the grid. Keep the screen on while idle.

## Night mode
- From 10 pm to 6 am: dim the photo (brightness about 0.5, desaturate), add a warm amber overlay, soften text and tiles, and switch the weather icon to a moon.

## Home button takeover (no adb)
- An AccessibilityService that detects when the stock Google TV launcher comes to the foreground and immediately brings Quiet forward (the Projectivy approach). Include an onboarding screen that walks me through enabling it in TV Settings > Accessibility.

## ESPN fantasy overlay
- Drawn by the same AccessibilityService using TYPE_ACCESSIBILITY_OVERLAY, so no overlay permission is needed. Display-only and never takes focus.
- Top right of the screen, text only, no background. A… *(the spec ends here)*

---

## Clarifications and assumptions

These fill gaps in the spec. Each one can be changed.

### ESPN fantasy overlay (the end of the section is missing)
- **What it shows:** my ESPN Fantasy Football matchup for the current week, as up to two lines of
  text, for example `Dengler 87.4 · 79.2 Opponent` and below it a smaller line such as
  `proj 112–104 · 3 playing`. Text is white with a soft drop shadow so it reads on any video.
  It has no background.
- **Data source:** ESPN's unofficial fantasy API
  (`lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/{year}/segments/0/leagues/{leagueId}?view=mMatchupScore&view=mTeam&view=mScoreboard`).
  It needs a league ID and a team ID. For private leagues it also needs the `espn_s2` and `SWID`
  cookies. All four are entered in Quiet's settings screen. They are stored only on the TV and
  never committed to the repo.
- **When it shows:** only when it is turned on in settings, and by default only during NFL game
  windows (Thu 7 pm–1 am, Sun 12 pm–1 am, Mon 7 pm–1 am local time). An "Always show" option
  overrides the windows. It hides while Quiet itself is in front, because the home screen has
  its own UI.
- **Refresh:** every 60 s while it is visible, using a coroutine scoped to "overlay visible". No
  polling happens outside the windows.
- **Size:** about 22 px primary and 15 px secondary text, with a 48 px inset from the top-right
  corner.

### Other
- **Package name:** `com.jackdengler.quiet`. compileSdk/targetSdk 35.
- **No image library** (Coil and Glide are not used). Decoding uses `BitmapFactory` with
  `inSampleSize` and runs on a background dispatcher. HTTP uses `HttpURLConnection`, JSON uses
  `org.json`, and RSS uses `XmlPullParser`, all built into Android. Settings use
  `SharedPreferences`.
- **Bundled fallback photos:** the build sandbox could not reach any stock-photo host, so the 8
  bundled images are generated landscape art (`tools/gen_photos.py`). Real photos come from
  `photos.json`. To replace a bundled image, upload a JPEG with the same name to
  `app/src/main/assets/photos/` using the GitHub web UI on a phone.
- **photos.json format:** `{"photos":[{"url":"…","tags":["morning"]}, …]}` with tags from
  `morning` (5–11), `day` (11–18), and `night` (18–5). Photos without a tag can show at any time.
- **Location:** the default is New York (40.71, -74.01). The settings screen accepts a city
  name, looked up with the Open-Meteo geocoding API, or raw lat/lon.
- **Units:** °F by default, with a toggle in settings.
- **Text entry on the TV:** the Google TV remote in the Google Home iPhone app has a keyboard,
  so long values such as ESPN cookies can be pasted from the phone.
- **Cold start:** release builds use R8, a baseline profile via `androidx.profileinstaller`,
  a black window background, and no splash work. Network and disk work start after the
  first frame.
