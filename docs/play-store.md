# Google Play submission text

Copy-paste text for the Play Console. Limits are Play's; counts are of the text inside each block.

## Store listing

### App name (30 max)

```
Gessearch Launcher
```

### Short description (80 max)

```
Draw a shape to open any app. Fast search, no icon grid, and no internet access.
```

### Full description (4000 max)

```
Gessearch Launcher is a minimal, fast home screen. There is no grid of icons: draw a letter or shape anywhere on your wallpaper and the app you linked to it opens instantly.

DRAW TO LAUNCH
• Teach it a shape for each app you use most: draw it three times and save
• Gestures can also open the app drawer, a web address, quick search, notifications or quick settings
• A soft trail follows your finger, with an optional vibration when a gesture is recognised
• Adjust how strict matching is, and test a shape without launching anything

ONE SEARCH FOR EVERYTHING
• Search your apps with forgiving, typo-tolerant matching, and give apps your own nicknames
• Type "timer 10 min", "alarm 7:30am weekdays" or "event dinner fri 7pm" to set it up in your clock or calendar app
• Type "web" or "map" followed by what you want to look up, in Google Maps or Waze
• "map home" or "map work" starts the route to the place saved in your maps app
• Sums and percentages are answered as you type
• Ask the time anywhere: "time in tokyo" or "3pm est to london"
• Press Enter to open the top result
• Or just say it: hold the dock and speak — "open Spotify" opens it straight away

QUICK SEARCH OVER ANY APP
• Make Gessearch your digital assistant and hold the power button to open a floating search bar from anywhere
• Swipe a result to the right to open it in a floating window, on devices that support it

YOUR HOME SCREEN
• Clock, date, battery and today's remaining calendar events over your wallpaper
• A widgets page on one side and a quick notes page on the other, each of which you can turn off
• Full app drawer with an A–Z scroller, app info, shortcuts and uninstall
• Light and dark themes, your own font file, and adjustable text size
• Work profile apps included

PRIVATE BY DESIGN
• The app has no internet access: no accounts, ads, analytics or tracking
• Everything stays on your device; export a backup file whenever you like
• Calendar access is optional and only used to show today's events

SAFE TO TRY
• The search button is always on the home screen, whatever your gestures do
• If the launcher ever crashes repeatedly, it opens a safe mode so you can reach your apps and switch back to another home screen

OPTIONAL ACCESSIBILITY FEATURE
One optional feature uses Android's accessibility service: pressing Alt + Space on a physical keyboard to open quick search from any app. The service stays off unless you turn it on, the app explains what it does and asks for your agreement first, and it cannot read what is on your screen.
```

### Other listing fields

| Field | Value |
| --- | --- |
| App category | Personalization |
| Tags | Launcher, Home screen |
| Privacy policy URL | `https://github.com/JinWei225/custom-gesture-launcher/blob/main/PRIVACY.md` |
| Contains ads | No |

## Accessibility API declaration

Play Console → App content → Accessibility API. Answer that the app is **not** an accessibility
tool (`isAccessibilityTool` is not set).

### Describe the core functionality of your app

```
Gessearch Launcher is a home-screen (launcher) app. Users open apps by drawing shapes on the home screen, and search apps and quick actions from a search bar on the home screen or a floating search window over other apps.
```

### Describe how your app uses the AccessibilityService API

```
The service provides one optional, user-initiated feature that Android exposes to third-party apps only through the AccessibilityService API: a keyboard shortcut. When the user presses Alt + Space on a physical keyboard while any app is in front, the launcher opens its quick search window. This needs key events from other apps' windows, which an app can only receive through flagRequestFilterKeyEvents. The service checks each key event for Alt + Space and returns every other event unhandled. Key events are not logged, stored, or transmitted.

The service does not declare canRetrieveWindowContent, so it cannot read screen content. Its accessibility events are limited to the app's own package. The app has no INTERNET permission, so no data can leave the device.

Before the user is sent to the system Accessibility settings, an in-app dialog describes the feature and what the service receives and does not do, and the user must tap "Agree". Tapping "No thanks" closes the dialog and does not open the settings.
```

### Video

Record a screen capture (an unlisted YouTube link is fine) that shows, in order:

1. Settings → Keyboard access, tapped with access off, showing the disclosure dialog
2. Tapping **Agree**, then turning on "Gessearch Launcher" in Accessibility
3. Alt + Space on a physical keyboard opening quick search over another app
