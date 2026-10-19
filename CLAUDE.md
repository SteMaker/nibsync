# NibSync

Android app for handwritten notes (stylus, pressure-sensitive), organised in books with nested
chapters, synced via a Nextcloud share. Built in steps; see `docs/architecture.md` for the
feature roadmap and where each feature lives.

## Build & test

- JDK: the Gradle daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`, the
  JetBrains JDK in `~/.gradle/jdks`); bytecode targets Java 17.
- `./gradlew assembleDebug` – build the APK
- `./gradlew test` – all JVM unit tests (core modules + Robolectric)
- Instrumented/app-level tests run on the emulator `nibsync_honor` (`scripts/emulator.sh
  start|stop|show`, modelled on the HONOR tablet: API 36, 1920x3000, 400 dpi, Play services),
  not on the user's tablet: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest`
- `./gradlew installDebug` – install (on the tablet only when the user asks; pin `ANDROID_SERIAL`
  when both are connected)
- `scripts/verify.sh` – everything, starting and stopping the emulator; `--tablet` runs the
  instrumented tests on the real tablet instead, `--no-device` skips them
- `./gradlew :desktop:createDistributable` – build the PC app; run it with
  `desktop/build/compose/binaries/main/app/nibsync/bin/nibsync [--data-dir <dir>]` (preferred
  over `:desktop:run`, which keeps a Gradle daemon busy); its tests run with `./gradlew test`
- Regenerate storage golden files only for an intentional format change:
  `NIBSYNC_UPDATE_GOLDEN=1 ./gradlew :core:storage:jvmTest` (keep old versions' files!)

- Nextcloud test server: Docker container `nibsync-nextcloud` on port 8089 (see
  `docs/architecture.md`, "Nextcloud sync"); start with `docker start nibsync-nextcloud`.

## Module rules

- `core/*` (model, document, storage, shapes, library, sync) are Kotlin Multiplatform (JVM target only for now). **No Android imports** there:
  they also back the PC app (`desktop/`). `shared/*` modules are KMP with Android and JVM
  targets for UI code both apps use.
- Memory: the dev PC froze once from RAM exhaustion; keep the caps in `gradle.properties` and
  the desktop `-Xmx1g`, and do not leave `:desktop:run` running during builds. The emulator
  takes about 4 GB: stop it when done (`verify.sh` does).
- `androidx.ink` types stay inside `ink/android` and `feature/canvas`; they never appear in
  public APIs of other modules. Likewise the rich text editor library stays inside
  `shared/text` (KMP, Android + PC) and `feature/text`; convert via `RichTextConversion`.
- Strokes are immutable and stored as raw input points in millimetres; edits are
  remove + add with a fresh id. Do not add mutating ops that break order-independent merging
  (`DocumentState` must give the same result for any op order; see its tests).
- Persisted names (enum names in `BrushFamily`, `Layer`, `TextFont`, `ListKind`, ..., serial names in
  `core/storage/Records.kt`) are part of the file format: never rename.
- Ink stock brushes are pinned to explicit versions in `BrushMapper`.

## Conventions

- Code, comments, docs: English. UI strings: English in `values/`, German in `values-de/`.
- Robolectric tests run on SDK 35 (`src/test/resources/robolectric.properties`).
- App-level tests use throw-away documents (`MainActivity.EXTRA_DOCUMENT_ID`), never the
  user's default document.
