# NibSync

Android app for handwritten notes (stylus, pressure-sensitive), organised in books with nested
chapters, synced via a Nextcloud share. Built in steps; see `docs/architecture.md` for the
feature roadmap and where each feature lives.

## Build & test

- JDK: the Gradle daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`, the
  JetBrains JDK in `~/.gradle/jdks`); bytecode targets Java 17.
- `./gradlew assembleDebug` – build the APK
- `./gradlew test` – all JVM unit tests (core modules + Robolectric)
- `./gradlew connectedDebugAndroidTest` – instrumented/app-level tests on the USB tablet
  (`gradle.properties` keeps the app installed afterwards, so notes on the device survive)
- `./gradlew installDebug` – install on the tablet
- `scripts/verify.sh` – everything; `--no-device` skips instrumented tests
- Regenerate storage golden files only for an intentional format change:
  `NIBSYNC_UPDATE_GOLDEN=1 ./gradlew :core:storage:jvmTest` (keep old versions' files!)

## Module rules

- `core/*` (model, document, storage, shapes) are Kotlin Multiplatform (JVM target only for now). **No Android imports** there:
  they must later back a Windows client.
- `androidx.ink` types stay inside `ink/android` and `feature/canvas`; they never appear in
  public APIs of other modules.
- Strokes are immutable and stored as raw input points in millimetres; edits are
  remove + add with a fresh id. Do not add mutating ops that break order-independent merging
  (`DocumentState` must give the same result for any op order; see its tests).
- Persisted names (enum names in `BrushFamily`, `Layer`, ..., serial names in
  `core/storage/Records.kt`) are part of the file format: never rename.
- Ink stock brushes are pinned to explicit versions in `BrushMapper`.

## Conventions

- Code, comments, docs: English. UI strings: English in `values/`, German in `values-de/`.
- Robolectric tests run on SDK 35 (`src/test/resources/robolectric.properties`).
- App-level tests use throw-away documents (`MainActivity.EXTRA_DOCUMENT_ID`), never the
  user's default document.
