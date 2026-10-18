# Lessons

- **Accessibility events from your own overlay look like your app being in front.** A
  TYPE_ACCESSIBILITY_OVERLAY window reports `packageName` = this app. Decide "this app is in
  front" from the event's `className` (an Activity in this package), never from the package name
  alone. Symptom: the overlay flashed for a moment and then disappeared.
- **Ship diagnostics the user can read on the device.** There's no adb or logcat here, so every
  background feature needs a visible status line, such as "Overlay status" in settings.
- **Compose text API names:** use `TextLayoutResult.getLineEnd(line, visibleEnd)`. There is no
  `lineEnd`. When the local machine can't compile, check unfamiliar API names against the
  library's public surface before pushing.
- **Don't draw people procedurally.** A vector character reads as clip art, so pick realistic
  objects (a shaded brush) instead. Animation pacing is easy to set too fast; default to slow
  and deliberate on a TV.
- **"It doesn't show up" needs a status line on day one.** Every network-fed feature gets a
  visible "last sync" summary in settings, and gets a second source when the first is
  unverified.
