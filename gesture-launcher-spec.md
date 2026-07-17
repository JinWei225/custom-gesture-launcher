# Gesture Launcher — Android App Spec

## 1. Concept
A minimal custom Android launcher. Home screen = your wallpaper + an invisible gesture canvas.
Draw a letter/shape anywhere on the screen → matching app launches instantly.
A small, always-visible button in the bottom-right corner opens a normal app drawer as a guaranteed fallback.

This replaces the system launcher (it registers for the `HOME` intent), so after building and
installing it, you'll be asked to pick it as your default Home app. **Keep your current launcher
(e.g. stock/Nova) installed — do not uninstall it.** That's your real safety net; everything below
is a second and third layer on top of it.

---

## 2. Screens / Components

### 2.1 Home Screen (`MainActivity`, set as `HOME`/`DEFAULT` launcher category)
- Full-screen `View` showing the system wallpaper (use `WallpaperManager`, don't render your own — respects whatever wallpaper is set in system settings).
- Transparent gesture-capture layer on top, listening for touch paths via `onTouchEvent`.
- Fixed button, bottom-right corner, always rendered (not gesture-dependent) → opens **App Drawer Activity**.
- No icons, no widgets, no app grid on the home screen itself — by design, per your choice.

### 2.2 App Drawer Activity
- Simple scrollable list/grid of all launchable apps (`PackageManager.queryIntentActivities` with `Intent.ACTION_MAIN` / `Intent.CATEGORY_LAUNCHER`).
- Tap an app → launches it.
- This activity must be **lightweight and dependency-free** — no gesture recognizer, no custom rendering — so it's the least likely part of the app to crash. It's your escape hatch.

### 2.3 Gesture Settings Activity
- List of saved gesture → app mappings.
- "Add gesture": pick an app, then draw the shape 3× (standard practice for recognizer training — draw same shape multiple times to average out natural variation).
- Delete/edit existing mappings.
- Also reachable from the App Drawer (so it's never gesture-only-accessible).

### 2.4 System Settings Shortcut
- One item in the App Drawer's overflow/menu: **"Open Android Settings"** → `Intent(Settings.ACTION_SETTINGS)`. Always available without needing a gesture, so you're never locked out of system settings.

---

## 3. Gesture Recognition
**Approach: $1 Unistroke Recognizer** (a well-documented, lightweight geometric template-matching algorithm — normalizes a drawn stroke by rotation/scale/position, then compares to saved templates via cosine-distance-style scoring). Chosen over Android's built-in `GestureLibrary` because it's easier to tune the matching threshold and works well for single-stroke letter shapes.

- Each gesture = one stroke (finger down → move → finger up). Multi-stroke letters (like "I" with serifs) should be simplified to single-stroke versions.
- Store templates as point-lists in local storage (Room DB or simple JSON file) alongside the target app's package name.
- On recognition: compute best match + confidence score. If below your confidence threshold → **do nothing, let you redraw** (per your choice — no popup, no fallback list, just try again).
- Threshold should start conservative (fewer false positives) and be tunable in Settings later if you find it too strict/loose in practice.

---

## 4. Safety Mechanisms

### 4.1 Guaranteed fallback app drawer (primary safety net)
- Bottom-right button, rendered independently of the gesture-canvas code path, so even if gesture recognition logic throws an error, the button and its click handler are unaffected.
- Kept in its own minimal Activity (§2.2) with no complex dependencies, so it's the most crash-resistant screen in the app.

### 4.2 Crash counter + auto-revert (2–3 crashes → revert)
- Register a custom `Thread.setDefaultUncaughtExceptionHandler` in `Application.onCreate()`.
- On any uncaught crash: increment a counter in `SharedPreferences` *before* the crash propagates, and record a timestamp.
- On next successful launch of `MainActivity`, check the counter:
  - If ≥ 3 crashes with no successful "heartbeat" in between → don't render the gesture canvas. Instead show a plain screen: "Gesture Launcher has crashed repeatedly. [Open Home App Settings]" button, which fires `Intent(Settings.ACTION_HOME_SETTINGS)` (or `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` depending on API level) so you can switch the default launcher back in one tap.
  - **Caveat to know going in:** no app can force-unset itself as the default launcher — Android doesn't allow that for security reasons. The best any app can do is detect repeated failure and deep-link you straight to the system picker screen, which is what this does.
- Reset the crash counter whenever the app runs for >10 seconds without crashing (a simple `postDelayed` "heartbeat" write to `SharedPreferences`), so occasional one-off crashes don't accumulate forever.

### 4.3 Defense in depth summary
| Layer | What it protects against |
|---|---|
| Fallback app-drawer button, isolated & simple | Gesture recognizer bugs/crashes |
| "Open Android Settings" always in App Drawer | Ever being unable to reach system settings |
| Crash counter + auto-revert prompt | Repeated crashes making the launcher unusable |
| Keeping your old launcher installed | Worst case — you can always switch back manually via Settings > Apps > Default apps > Home app |

---

## 5. Tech Stack
- Kotlin, Android Studio.
- Min SDK: recommend API 26+ (Android 8.0) unless you need to support an older device — keeps modern launcher APIs available without much legacy handling.
- No network permissions needed — everything is local (no cloud sync of gestures, unless you want that later).
- Permissions: none beyond what's implied by being a launcher (querying installed packages, setting wallpaper display).

## 6. Project Structure (suggested)
```
app/
  src/main/java/.../
    MainActivity.kt              // home screen: wallpaper + gesture canvas + drawer button
    GestureCanvasView.kt         // custom View, touch capture + $1 recognizer matching
    unistroke/
      OneDollarRecognizer.kt     // the $1 algorithm implementation
      GestureTemplate.kt
    drawer/
      AppDrawerActivity.kt
      AppListAdapter.kt
    settings/
      GestureSettingsActivity.kt
      GestureTrainingActivity.kt // draw-3x flow for new gestures
    crash/
      CrashHandler.kt            // uncaught exception handler + counter logic
      SafeModeActivity.kt        // shown after repeated crashes
    data/
      GestureStore.kt            // Room DB or JSON persistence for gesture->app mappings
    App.kt                       // Application class, registers CrashHandler
  src/main/AndroidManifest.xml   // HOME/DEFAULT intent filters on MainActivity
```

## 7. Build/Test Plan
1. Build MVP: App Drawer + wallpaper display + button, no gestures yet. Confirm you can set it as default launcher and switch back cleanly via Settings before adding any gesture logic.
2. Add gesture canvas + $1 recognizer with 2-3 test gestures.
3. Add crash handler + safe-mode fallback screen; deliberately trigger a crash (e.g. temporary bug) to confirm the auto-revert prompt actually appears after 3 crashes.
4. Only then add the training/settings UI for adding your own gestures.

This order means the safety net is tested and working *before* the more experimental gesture code is layered on top.
