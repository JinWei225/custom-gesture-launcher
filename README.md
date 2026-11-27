# Gessearch Launcher

A minimal, speed-focused Android home-screen replacement. The home screen is your system wallpaper
plus a clock, date, battery and today's calendar over an invisible full-screen canvas: draw a
single-stroke letter/shape and the mapped app launches instantly. A search button in the dock (in
whichever bottom corner you choose) always opens search on a tap, or the full app drawer on a long
press, as a guaranteed fallback. A widgets page sits to the left of home and a notes page to the
right.

Built from [`gesture-launcher-spec.md`](gesture-launcher-spec.md). Kotlin, classic Views/XML, JSON
persistence, min SDK 26 (Android 8.0+).

> **Status:** actively developed as a personal daily-driver project, shared in case it's useful to
> others. Core functionality (gesture recognition, app drawer, unified search, backup/restore,
> work-profile support) is solid, but some areas are still being polished — see [Known limitations](#known-limitations)
> below and the repo's issues for what's tracked.

## Features

- **Draw-to-launch**: a Protractor-variant $1 unistroke recognizer matches single-stroke shapes
  against your saved gestures — no on-screen icons required.
- Gestures can also **open the app drawer**, **open a URL**, **open the floating search**, or pull
  down **notifications** or **quick settings**, not just launch an app.
- A visible **halo trail** follows your finger while drawing, and haptic feedback (toggleable)
  confirms a match.
- **Unified search** over installed apps, ranked by one fuzzy matcher, plus command rows:
  `event`, `timer`, `alarm`, `web` and `map` followed by a space create that thing in the app that
  owns it. Enter opens the top result whatever kind it is. The `web` command searches on the
  engine picked in settings (Google, Bing, DuckDuckGo, Brave Search or Ecosia).
- **Maps**: `map` searches Google Maps, or Waze when it is installed and picked in settings.
  `map home` and `map work` start the route to the places saved in that app. By voice it opens
  straight away: "take me home" starts the route, "navigate to KLCC" opens the app's results.
- **Ask an AI app**: `ask Claude how tall is K2` opens a new chat in that app with the question
  typed in and not sent; `ask …` with no app named offers every installed assistant (Claude,
  Gemini, ChatGPT, Copilot, Perplexity, DeepSeek, Grok). By voice, "ask Claude …" opens it
  straight away.
- **Answers in the search box**: a sum (`12*12`, `20% of 50`) or a time-zone question (`time in
  tokyo`, `3pm est to tokyo`, `tokyo to london`) is answered in a row pinned above the results;
  tap it to copy. Zones can be named by city, country or abbreviation, straight from the
  platform's tz database — nothing is fetched.
- **Three home pages**: a **widgets** page to the left of home and a **notes** page to the right
  (quick notes you type and send, kept by day). Either can be turned off in settings. Swipe along
  the dock strip beside the search button to move between them; its dots show where you are.
- **Clock, date and today's events** over the wallpaper: tap the time for your alarms, the date for
  your calendar, and the events line to let the launcher read today's calendar (optional).
- **Floating search over any app**: the same search box, reachable from anywhere on the phone by
  holding the power button (it registers as a digital assistant) or from a home-screen gesture.
  Buttons under the bar start an event, timer, alarm or web search in one tap.
- **Voice search**: hold the strip beside the search button on the home screen and speak, or use
  the mic in the floating search. "Open Spotify" opens the app straight away; anything else
  ("set a timer for 10 minutes", "search for how tall is K2", "WhatsApp") lands in the search box
  with its results ready to tap; speech that matches nothing becomes a web search. Holding the
  power button opens the floating search already listening. App names and aliases are what it
  listens for, and **Voice words** in settings add names it keeps mishearing. Speech is turned
  into text by the phone's own speech recognition service.
- **Swipe a result right** to open it in a floating window instead of full screen — apps, actions
  and web searches alike, on devices that support freeform windows.
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
  the search settings — to a single file. The imported font file itself can't travel in a JSON
  backup, so the font has to be re-picked on a new device.
- **Guided tours**: on first launch, pop-ups point at each control on the home screen and end on the
  screen where you make your first gesture. The floating search bar, the widgets page and the notes
  page each explain themselves the first time you open them. All of them can be replayed from
  settings.
- Built-in safety nets: the search button is always present regardless of gesture state, a
  one-tap deep link to the Home-app picker, and a crash counter that drops into a **Safe Mode**
  screen after repeated crashes so you're never locked out of your phone.

## Download

Grab the latest APK from this repo's [Releases](../../releases) page and sideload it (you'll need
to allow installs from your file manager / browser in Android's settings). There's no Play Store
listing.

To get updates automatically, add the repo to [Obtainium](https://github.com/ImranR98/Obtainium):
**Add App** → paste `https://github.com/JinWei225/custom-gesture-launcher` → **Add**. Every
release carries one signed APK (plus an `.aab` bundle, which Obtainium ignores), so Obtainium needs
no further configuration.

Releases are signed with a dedicated key. A copy built from source in Android Studio is signed
with your own debug key instead, and Android won't install one over the other — uninstall first
when switching between the two.

## Making it your launcher (and how to leave safely)

- After installing, press Home → pick **Gessearch Launcher**. **Keep your existing launcher
  installed** as the real safety net — this app is not a drop-in replacement for a full-featured
  launcher.
- To switch back: Settings → Apps → Default apps → **Home app**.
- If the launcher crashes 3 times, it comes up in **Safe Mode** instead of the gesture home
  screen: a plain screen with buttons to open the app drawer, jump straight to Android's Home-app
  picker, and reset the counter once things are working again. No app is allowed to unset itself
  as the default launcher, so Safe Mode gets you to the picker rather than switching for you.

## First use

The first time the home screen opens, a short **tour** walks through it: drawing on the canvas,
the search button, the dock strip (pages and voice), the clock and calendar, the floating search,
and finally a button that opens the screen where your first gesture is made. The floating search
bar (its one-tap command buttons, the mic, and how to open results), the widgets page (adding,
moving, resizing and removing widgets) and the notes page (sending notes, and copying, editing or
deleting one) each get a short tour of their own the first time you open them. Skip any of them
whenever you like; **Settings → Show the tours again** replays them all.

The launcher ships with **no gestures**. If you draw on the home screen before adding any, a
pop-up offers to add one. To get there by hand: **touch and hold** the search button to open the
app drawer → gear icon → **Gestures** → **+** → choose what the gesture should do → draw the shape
3× → save. Then draw it anywhere on the home screen to trigger it. Tune matching strictness under
**Adjust gesture sensitivity**.

The same settings hub is where you pick the search engine, turn the widgets and notes pages on or
off, turn on the floating search window, and set a custom font and the theme. The floating search
needs you to pick Gessearch Launcher as your digital assistant — it is off until you grant that
deliberately. **Once it is on, a tap on the search button opens the floating bar instead of the
drawer, so touch and hold the button for the app list and its gear icon** (typing `settings` in
the bar also offers Launcher settings).

## Known limitations

- Gesture actions are currently limited to launching an app, opening the drawer, opening a URL,
  opening the floating search, or pulling down notifications or quick settings (no pinned
  shortcuts or contact call/text actions yet).
- Floating windows depend on the device reporting freeform-window support, and OEM skins keep
  their own rules on top of it — some apps report themselves resizable, reach freeform, and are
  then expanded to full screen anyway. Nothing public exposes that, so those cases aren't warned
  about.
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

## Publishing a release

Releases are built by [GitHub Actions](.github/workflows/release.yml) from a version tag; nothing
is built or uploaded by hand.

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The workflow runs the unit tests, builds a release APK and app bundle with `versionName` `1.2.3`
and `versionCode` `1002003` derived from the tag, and publishes a GitHub Release with both attached
and auto-generated notes. Obtainium picks up the APK from there; the `.aab` is the file to upload
to Google Play.

It signs with a keystore held in repository secrets, set up once:

1. Generate the key (Android Studio's bundled JDK has `keytool`), and **back the file up** — a
   lost keystore means every future release is a fresh install for everyone:
   ```bash
   keytool -genkeypair -v -keystore gessearch-release.jks -alias gessearch -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Add four secrets under the repo's Settings → Secrets and variables → Actions:
   `KEYSTORE_BASE64` (the file, base64-encoded: `base64 -i gessearch-release.jks | pbcopy`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`gessearch`) and `KEY_PASSWORD`.

A signed build locally works the same way: put the keystore's path and passwords in a
`keystore.properties` at the repo root (gitignored — `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) and run `./gradlew assembleRelease`. Without that file a release build is unsigned.

## Suggested test order (safety before gestures)

1. Confirm the drawer + wallpaper + button work and you can set/unset the launcher as default.
   On a fresh install the tour should start by itself; Skip ends it for good, and Settings → Show
   the tours again brings it back, along with the search, widgets and notes tips.
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
