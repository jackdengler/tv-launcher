# Lessons

- **Accessibility events from your own overlay look like your app being in front.** A
  TYPE_ACCESSIBILITY_OVERLAY window reports `packageName` = this app. Decide "this app is in
  front" from the event's `className` (an Activity in this package), never from the package name
  alone. Symptom: the overlay flashed for a moment and then disappeared.
- **Ship diagnostics the user can read on the device.** There's no adb or logcat here, so every
  background feature needs a visible status line, such as "Overlay status" in settings.
