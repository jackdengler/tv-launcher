# Installing Quiet on a TCL Google TV (no computer)

You only need the TV remote and, optionally, an iPhone. Each push to `main` builds a new APK,
and the download link below always points to the newest build:

```
https://github.com/jackdengler/tv-launcher/releases/latest/download/quiet.apk
```

## 1. One-time TV setup

1. **Install Downloader.** On the TV, open the Google Play Store, search for **Downloader**
   (by AFTVnews), and install it.
2. **Turn on developer options.** Go to **Settings → System → About** and scroll to
   **Android TV OS build**. Click it 7 times until the TV says "You are now a developer".
3. **Allow Downloader to install apps.** Go to **Settings → Apps → Security & restrictions →
   Unknown sources** and turn on **Downloader**.

> Tip: to type on the TV from your phone, open the **Google Home** app on the iPhone and tap
> the TV, then the remote. The keyboard icon lets you type or paste into any text field on the
> TV. Use it for the URL below and for long settings such as ESPN cookies.

## 2. Install Quiet

1. Open **Downloader**. If it asks for storage access, allow it.
2. In the **Home** tab's URL box, enter the link above and click **Go**.
   - Shortcut: on your iPhone, go to **aftv.news** and create a free Downloader code for the
     link. After that you only type a short number on the TV.
3. When the download finishes, choose **Install**, then **Done**. Downloader may offer to
   delete the APK file. Say yes to save space.
4. Open Quiet from **Apps** on the Google TV home screen.

## 3. Make the Home button open Quiet

Google TV doesn't let you pick a different home app without a computer. Quiet works around
this with an accessibility service. When the stock home screen appears, Quiet replaces it
immediately. This is the same approach Projectivy Launcher uses.

1. The first time Quiet opens, it shows a **Set up Home button** screen. Choose **Open
   Accessibility settings**.
2. In **Settings → System → Accessibility**, choose **Quiet Home**, then turn it **On** and
   confirm.
3. Press **Back** to return to Quiet. From now on, pressing Home opens Quiet.

If the switch is greyed out or says "Restricted setting", Android is blocking accessibility for
apps installed outside the Play Store. Go to **Settings → Apps → Quiet**, choose **Allow
restricted settings** (on TVs that show it), and try again.

To see the setup screen again later, go to **All apps → Quiet settings → Home button**.

Expect a short flash: Google TV's own home screen appears for a moment before Quiet replaces it.
That can't be avoided without a computer.

## 4. Updating

**One click:** open **All apps**. When a newer build exists, an **Update Quiet** card appears
first. Select it, then confirm **Update** on the system prompt. You can also go to **Quiet settings →
Update Quiet**, which always shows your current build. The first time, Android asks you to let
Quiet install apps: turn that on, press Back, and select Update again.

**Fallback:** repeat step 2 with the same URL in Downloader. Your settings are kept either way,
because every build is signed with the same key, which is committed in this repo.

## 5. Everyday use

- **Long-press OK on any of the first six tiles** to swap in a different app.
- After 3 minutes without a button press, the tiles fade out and the photo fills the screen.
  Any button brings them back.
- From 10 pm to 6 am the screen dims and warms automatically.

## 5b. Settings inside Quiet

Open **All apps** and choose **Quiet settings**, the first tile:

- Weather location (city search or latitude/longitude) and °F/°C
- Headline feeds: one or more RSS URLs separated by spaces (WSJ World News + Variety by default)
- ESPN fantasy overlay: league ID, team ID, and the `espn_s2` and `SWID` cookies if your league
  is private

### ESPN fantasy overlay

1. In **Quiet settings → ESPN fantasy overlay**, set **League 1 ID** and **League 1 team ID**
   (and League 2 if you have one). To find them, open the league on fantasy.espn.com in
   Safari: the address has `leagueId=…`, and your team page has `teamId=…`.
2. **Private leagues:** choose **Sign in to ESPN** once and log in on the TV.
3. Turn **Overlay** on. Then pick:
   - **League 1 / League 2:** select either one to switch it between Shown and Hidden.
   - **Look:** Scorebug (logos, scores and a win-chance pennant), Card (adds the win
     chart), or Full rosters (every starter).
   - **Corner:** where it sits (top right by default).
4. For both leagues in full detail inside Quiet, open **All apps → Fantasy**.

The overlay needs the Home button service from step 3 to be on. Broadcaster logos (FOX, CBS)
are part of the picture and can't be removed; use **Corner** to stay out of their way.

## 6. Adding your own photos

From the GitHub app or website on your phone:

- **Online photos:** edit `photos.json` in this repo and add entries such as
  `{"url": "https://…/photo.jpg", "tags": ["night"]}`. Quiet checks this file a few times a
  day over Wi-Fi.
- **Bundled photos (used offline):** upload JPEGs to `app/src/main/assets/photos/` with the same
  names as the existing ones. The next build includes them.

## Uninstalling

Go to **Settings → Apps → Quiet → Uninstall**. Turn off the accessibility service first so
the stock home screen comes back right away.
