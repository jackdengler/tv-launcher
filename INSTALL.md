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

If a TV software update turns the service off, Quiet shows the setup screen again.

## 4. Updating

Repeat step 2 with the same URL. The new APK installs over the old one and keeps your settings,
because every build is signed with the same key, which is committed in this repo.

## 5. Settings inside Quiet

Open **All apps** and choose **Quiet settings**, the first tile:

- Weather location (city search or latitude/longitude) and °F/°C
- News RSS feed URL (BBC News by default)
- ESPN fantasy overlay: league ID, team ID, and the `espn_s2` and `SWID` cookies if your league
  is private

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
