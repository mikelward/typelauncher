# Development

Type Launcher is a Kotlin Android home screen launcher app. The repository contains a single Android application module, `:app`, built with the Gradle wrapper.

## Prerequisites

- Android Studio, with the Android SDK and Android SDK Platform 37 installed.
- JDK 17 or newer. Cursor Cloud uses JDK 21.
- Android Gradle Plugin 9.3.1 and Gradle 9.7.0, both resolved through the checked-in Gradle wrapper and version catalog.
- For command-line work, set `ANDROID_HOME` to your Android SDK path and make sure `adb` is on `PATH`.

The app currently targets SDK 36, compiles against Android SDK Platform 37.1, and supports Android 14/API 34 and newer.

## Install dependencies

Most dependencies are resolved automatically by Gradle.

### Android Studio

1. Open the repository root in Android Studio.
2. Let Android Studio sync the Gradle project.
3. If prompted, install the Android SDK Platform 37 package, SDK build tools, and any missing Android Gradle Plugin components.
4. Select the `app` run configuration.

### Command line

From the repository root:

```sh
./gradlew tasks
```

The first Gradle invocation downloads Gradle 9.7.0 and project dependencies. If the Android SDK platform is missing, AGP can install it when SDK licenses have been accepted:

```sh
sdkmanager --licenses
```

### Cursor

Cursor Cloud is configured for this project with JDK 21 and the Android SDK at `/opt/android-sdk`. Use the integrated terminal from the repository root and run the same Gradle commands used locally:

```sh
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

Instrumented tests require an emulator or physical device, so they are not expected to run in the default cloud VM unless an emulator has been set up separately.

## Build and run

> **First run after the `.dev` → `.debug` rename: uninstall the old package once.**
> Every debug build is now `app.typelauncher.debug`; local builds used to be
> `app.typelauncher.dev`. Android treats that as a different app, not an upgrade,
> so unless the old one is removed it stays installed *and keeps the home role*
> — pressing Home goes on running the old build and no launcher prompt appears,
> which reads as "my build didn't take". Separately, a `.debug` APK left over
> from when CI published them is signed with a different key, so installing over
> it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
>
> `./gradlew installAndRun` handles both automatically. **Android Studio's Run
> button and `./gradlew installDebug` do not** — they are AGP's own paths and
> get none of it. Before using either, once:
>
> ```sh
> adb uninstall app.typelauncher.dev    # ignore "Unknown package"
> adb uninstall app.typelauncher.debug  # only if it was installed by CI
> ```

### Android Studio

1. Start an emulator or connect a physical Android device.
2. Select the `app` configuration.
3. Click Run.
4. When Android asks for a default home app, choose **Type Launcher Debug** if you want to test launcher behavior — every debug build is labeled that way, wherever it was built, so it is distinguishable from a co-installed Play build (plain "Type Launcher").

### Command line

Build the debug APK:

```sh
./gradlew assembleDebug
```

Install it on the selected emulator or device:

```sh
./gradlew installDebug
```

To install and launch the debug build only in the personal/default profile on a device that also has a work profile, use:

```sh
./gradlew :app:installAndRun
```

That task targets Android user `0`, removes the debug build from non-owner users if it is present there, and starts the debug launcher as user `0`. The package it acts on is `app.typelauncher.debug`, everywhere. (It used to be `.dev` for a local build and `.debug` in CI; CI has built no debug APK since the build job moved to the release variant, so there was nothing left for the split to distinguish.)

The task also clears two installs that would otherwise get in the way of that rename, so neither needs doing by hand. It uninstalls `app.typelauncher.dev` if present — a rename does not upgrade, so the old launcher would keep the home role and its layout, and pressing Home would go on running stale code. And if a `.debug` install is already there signed by a different key — a CI-built APK from when Firebase App Distribution shipped them — `adb install -r` refuses it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the task recognizes that one failure, uninstalls, and installs fresh. Any other install error is reported rather than worked around. Either way the old build's layout is gone, and Android prompts for the default launcher again on the next Home press.

If multiple devices are attached, set `ANDROID_SERIAL` first:

```sh
ANDROID_SERIAL=<device-id> ./gradlew installDebug
```

You can list connected devices with:

```sh
adb devices
```

After installation, press the device Home button and select **Type Launcher Debug** when Android prompts for the default launcher — that is the label every debug build carries, as distinct from a co-installed Play build.

## Testing

### Local JVM unit tests

Unit tests live under `app/src/test`. They run on the development machine and do not require an emulator:

```sh
./gradlew test
```

To run only the app module unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

Use these tests for Kotlin/JVM logic and fast feedback in Android Studio, Cursor, or any terminal.

### Instrumented tests

Instrumented tests live under `app/src/androidTest`. They run on an Android emulator or physical device:

```sh
./gradlew connectedDebugAndroidTest
```

Run these tests when behavior depends on Android framework APIs, resources, lifecycle, permissions, or launcher integration.

### Lint

Run Android lint before submitting code changes:

```sh
./gradlew lint
```

The debug lint HTML report is written to `app/build/reports/lint-results-debug.html`.

## GitHub CI

There is no GitHub Actions workflow checked in yet. When CI is added, it should run the same Gradle wrapper commands as local development so pull requests and local builds exercise the same paths.

A minimal unit-test workflow should:

1. Check out the repository.
2. Set up JDK 21, or any JDK 17+ compatible with AGP 9.3.1.
3. Set up the Android SDK.
4. Run `./gradlew test`.
5. Optionally run `./gradlew lint` and `./gradlew assembleDebug`.

Example job steps:

```yaml
- uses: actions/checkout@v4
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: "21"
- uses: android-actions/setup-android@v3
- run: ./gradlew test
- run: ./gradlew lint
- run: ./gradlew assembleDebug
```

Emulator-backed CI is slower and needs a separate emulator action or self-hosted runner. Use it for `connectedDebugAndroidTest` only when the workflow explicitly starts and waits for an emulator.

## Emulator testing

Use Android Studio Device Manager to create an emulator with API 34 or newer. API 36 is preferred because it matches the current target SDK.

Recommended manual checks:

- Install the debug build.
- Launch the app from the app drawer.
- Press Home and select **Type Launcher Debug** as the default home app (the label every debug build carries).
- Reboot or rotate the emulator if testing launcher persistence or configuration changes.

If an emulator is already running, command-line install and tests can use:

```sh
./gradlew installDebug
./gradlew connectedDebugAndroidTest
```

## Physical device testing

1. Enable Developer options and USB debugging on the device.
2. Connect the device over USB and approve the debugging prompt.
3. Confirm the device is visible:

```sh
adb devices
```

4. Install and run the debug build:

```sh
./gradlew installDebug
```

5. Press Home and select **Type Launcher Debug** to test it as a launcher (the label every debug build carries).

Use a physical device for launcher behavior that may differ from emulators, including gesture navigation, default-home prompts, OEM launchers, and battery or background restrictions.

## Versioning

`versionCode` and `versionName` are derived from git at configure time:

- `versionCode` = `git rev-list --count HEAD` (number of commits up to `HEAD`).
- `versionName` = `"1.0.<commitCount>+<shortSha>"`, e.g. `1.0.50+5e6eb54`.

This means the same commit always produces the same `versionCode`, and the short SHA is always recoverable from an installed APK via `adb shell dumpsys package app.typelauncher | grep versionName`.

CI must check the repo out with full history for the count to be correct. The `actions/checkout` step uses `fetch-depth: 0`; if you run a build from a shallow clone, `versionCode` will collapse to `1`.

If you need to override the base name (for example, to bump to `2.0`), edit `baseVersionName` in `app/build.gradle.kts`.

## Distribution

Internal testing builds go out through the Play Store internal track, uploaded by the `deploy` job on every push to `main` that carries a release-worthy commit. See [docs/play-store-internal-track.md](docs/play-store-internal-track.md).

Firebase App Distribution used to ship the CI debug APK alongside it. Both channels published on every push and reached the same testers, so it was retired; the internal track is also the route to alpha/beta/production. Nothing distributes a debug APK now — sideloading one means building it locally.

## Common Gradle commands

| Task | Command |
| --- | --- |
| List Gradle tasks | `./gradlew tasks` |
| Build debug APK | `./gradlew assembleDebug` |
| Install debug APK | `./gradlew installDebug` |
| Install and run debug APK in personal profile | `./gradlew :app:installAndRun` |
| Run local unit tests | `./gradlew test` |
| Run app unit tests | `./gradlew :app:testDebugUnitTest` |
| Run instrumented tests | `./gradlew connectedDebugAndroidTest` |
| Run lint | `./gradlew lint` |
| Clean build outputs | `./gradlew clean` |
