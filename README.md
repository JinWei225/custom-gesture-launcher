# Gesture Launcher

A minimal, speed-focused Android home-screen replacement. The home screen is just your system
wallpaper plus an invisible full-screen canvas: draw a single-stroke letter/shape and the mapped
app launches instantly. A small bottom-right button always opens a normal, searchable app drawer
as a guaranteed fallback.

Built from [`gesture-launcher-spec.md`](gesture-launcher-spec.md). Kotlin, classic Views/XML, JSON
persistence, min SDK 26 (Android 8.0+).

> **Status:** actively developed as a personal daily-driver project, shared in case it's useful to
> others. Core functionality (gesture recognition, app drawer, backup/restore, work-profile
> support) is solid, but some areas are still being polished — see [Known limitations](#known-limitations)
> below and the repo's issues for what's tracked.

## Features

- **Draw-to-launch**: a Protractor-variant $1 unistroke recognizer matches single-stroke shapes
  against your saved gestures — no on-screen icons required.
- Gestures can also **open the app drawer** or **open a URL**, not just launch an app.
- A visible **halo trail** follows your finger while drawing, and haptic feedback (toggleable)
  confirms a match.
- Full **app drawer** with alphabet-index fast scroll, search, app labeling, and long-press
  app-info/uninstall.
- **Work profile** app support via `LauncherApps`.
- Adjustable **recognition sensitivity** with a live test mode (draw a shape, see what it would
  match, without launching anything).
- **Backup/restore** your gestures, labels, and sensitivity settings to a single file.
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
- Safe Mode after 3 crashes that will change back to your system launcher of your phone to prevent hanging situation.

## First use

The launcher ships with **no gestures**. Open the drawer (bottom-right button) → overflow →
**Gesture settings** → **+** → choose what the gesture should do → draw the shape 3× → save. Then
draw it anywhere on the home screen to trigger it. Tune matching strictness under the overflow →
**Recognition sensitivity**.

## Known limitations

- Gesture actions are currently limited to launching an app, opening the drawer, or opening a URL
  (no pinned shortcuts or contact call/text actions yet).
- Release builds are unsigned/debug-signed for now — there's no dedicated release keystore, so
  reinstalling over an existing copy after certain updates may require uninstalling the old one
  first.
- No automated UI tests yet; unit tests cover the recognizer only.

## Building from source

1. **Open** this folder in Android Studio (File → Open). On first sync Studio will:
   - offer to install **SDK Platform 34** and build-tools if missing — accept;
   - download **Gradle 8.9** via the wrapper and generate `gradle/wrapper/gradle-wrapper.jar`.
   > If Studio reports the Gradle wrapper is missing, let it regenerate, or run
   > `gradle wrapper` once from a terminal that has Gradle.
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

## Contributing

This is a personal project shared publicly; issues and pull requests are welcome, but please open
an issue to discuss larger changes before sending a PR.

## License

[MIT](LICENSE) — do what you like with it, a copy of the license is appreciated.
