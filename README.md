# Gesture Launcher

A minimal, speed-focused Android home-screen replacement. The home screen is your system wallpaper
plus a clock, date, battery and today's calendar over an invisible full-screen canvas: draw a
single-stroke letter/shape and the mapped app launches instantly. A small bottom-right button
always opens search, or the full app drawer on a long press, as a guaranteed fallback.

Built from [`gesture-launcher-spec.md`](gesture-launcher-spec.md). Kotlin, classic Views/XML, JSON
persistence, min SDK 26 (Android 8.0+).

> **Status:** actively developed as a personal daily-driver project, shared in case it's useful to
> others. Core functionality (gesture recognition, app drawer, unified search, backup/restore,
> work-profile support) is solid, but some areas are still being polished — see [Known limitations](#known-limitations)
> below and the repo's issues for what's tracked.

## Features

- **Draw-to-launch**: a Protractor-variant $1 unistroke recognizer matches single-stroke shapes
  against your saved gestures — no on-screen icons required.
- Gestures can also **open the app drawer**, **open a URL**, or **open the floating search**, not
  just launch an app.
- A visible **halo trail** follows your finger while drawing, and haptic feedback (toggleable)
  confirms a match.
- **Unified search** over installed apps, local files and the web, ranked by one fuzzy matcher so
  everything sorts by the same rules. Enter opens the top result whatever kind it is. File search
  (via MediaStore) and the web row are individually toggleable.
- **Floating search over any app**: the same search box, reachable from anywhere on the phone by
  holding the power button (it registers as a digital assistant) or from a home-screen gesture.
- **Swipe a result right** to open it in a floating window instead of full screen — apps, files
  and web results alike, on devices that support freeform windows.
- Full **app drawer** with alphabet-index fast scroll, app aliases, and long-press
  app-info/shortcuts/uninstall. It paints a complete list on its first frame from a disk snapshot,
  so it stays instant even after the OS hibernates the launcher.
- **Frosted-glass UI**: translucent surfaces over a blurred backdrop, with an automatic fallback to
  a heavier veil when the system withholds blur (battery saver, unsupported devices).
- **Light/dark theming** with a manual theme selector, plus an importable **custom font** (.ttf or
  .otf) and a font-size multiplier.
- **Work profile** app support via `LauncherApps`.
- Adjustable **recognition sensitivity** with a live test mode (draw a shape, see what it would
  match, without launching anything).
- **Backup/restore** your gestures, app aliases, and settings — sensitivity, theme, font size and
  the search toggles — to a single file. The imported font file itself can't travel in a JSON
  backup, so the font has to be re-picked on a new device.
- Built-in safety nets: the drawer button is always present regardless of gesture state, a
  one-tap deep link to the Home-app picker, and a crash counter that drops into a **Safe Mode**
  screen after repeated crashes so you're never locked out of your phone.

## Download

Grab the latest APK from this repo's [Releases](../../releases) page and sideload it (you'll need
to allow installs from your file manager / browser in Android's settings). There's no Play Store
listing.

## Making it your launcher (and how to leave safely)

- After installing, press Home → pick **Gesture Launcher**. **Keep your existing launcher
  installed** as the real safety net — this app is not a drop-in replacement for a full-featured
  launcher.
- To switch back: Settings → Apps → Default apps → **Home app**.
- If the launcher crashes 3 times, it comes up in **Safe Mode** instead of the gesture home
  screen: a plain screen with buttons to open the app drawer, jump straight to Android's Home-app
  picker, and reset the counter once things are working again. No app is allowed to unset itself
  as the default launcher, so Safe Mode gets you to the picker rather than switching for you.

## First use

The launcher ships with **no gestures**. Long-press the bottom-right button to open the drawer →
gear icon → **Gesture settings** → **+** → choose what the gesture should do → draw the shape 3× →
save. Then draw it anywhere on the home screen to trigger it. Tune matching strictness under
**Recognition sensitivity**.

The same settings hub is where you turn on file/web search, the floating search window, a custom
font, and the theme. File search asks for all-files access, and the floating search needs you to
pick Gesture Launcher as your digital assistant — both are off until you grant them deliberately.

## Known limitations

- Gesture actions are currently limited to launching an app, opening the drawer, opening a URL, or
  opening the floating search (no pinned shortcuts or contact call/text actions yet).
- File search only sees files the media scanner has indexed; anything it hasn't reached is
  invisible to the search box.
- Floating windows depend on the device reporting freeform-window support, and OEM skins keep
  their own rules on top of it — some apps report themselves resizable, reach freeform, and are
  then expanded to full screen anyway. Nothing public exposes that, so those cases aren't warned
  about.
- Release builds are unsigned/debug-signed for now — there's no dedicated release keystore, so
  reinstalling over an existing copy after certain updates may require uninstalling the old one
  first.
- No automated UI tests yet; unit tests cover the recognizer, search ranking and URL detection.

## Building from source

1. **Open** this folder in Android Studio (File → Open). On first sync Studio will offer to
   install **SDK Platform 34** and build-tools if missing — accept. The Gradle wrapper is committed,
   so **Gradle 8.9** downloads itself on the first build.
2. Studio uses its **bundled JBR 21** to run Gradle (the system `java` on PATH may be too new for
   AGP — don't point Gradle at it if so). Verify under Settings → Build → Build Tools → Gradle →
   Gradle JDK.
3. **Run** the `app` configuration onto a device/emulator (API 26+), or build an APK via
   Build → Build Bundle(s)/APK(s) → Build APK(s).

## Suggested test order (safety before gestures)

1. Confirm the drawer + wallpaper + button work and you can set/unset the launcher as default.
2. Add 2–3 gestures and confirm recognition; a sloppy/unknown stroke should do nothing (or show
   the "not recognized" hint) rather than misfiring.
3. To exercise Safe Mode, temporarily throw in `MainActivity.onCreate`, crash 3×, confirm the Safe
   Mode screen appears, then remove the test crash.
4. Check both themes: the glass surfaces have to stay legible over a light *and* a dark wallpaper,
   which is the thing most likely to regress.

## Contributing

This is a personal project shared publicly; issues and pull requests are welcome, but please open
an issue to discuss larger changes before sending a PR.

## License

[MIT](LICENSE) — do what you like with it, a copy of the license is appreciated.
