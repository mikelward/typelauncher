# Type Launcher

Android home screen launcher app (Kotlin, single `:app` module).

## Cursor Cloud specific instructions

### Environment

- **JDK 21** is pre-installed on the VM. AGP 9.2.0 requires JDK 17+.
- **Android SDK** is installed at `/opt/android-sdk`. Environment variables `ANDROID_HOME`, `JAVA_HOME`, and `PATH` are set via `~/.bashrc`. In non-login/non-interactive shells (e.g. plain `Shell` tool calls), you may need to `source ~/.bashrc` or export these variables explicitly before running Gradle commands.
- The Gradle wrapper (`./gradlew`) auto-downloads Gradle 9.4.1 on first run.
- AGP auto-installs `Android SDK Platform 36.1` (compileSdk minor API level 1) on the first build if only `platforms;android-36` is present.

### Key commands

| Task | Command |
|------|---------|
| Build debug APK | `./gradlew assembleDebug` |
| Run unit tests | `./gradlew test` |
| Run lint | `./gradlew lint` |
| Clean build | `./gradlew clean` |

### Testing expectations

- Code changes must include or update relevant unit tests.
- UI changes must include or update screenshot tests that cover the changed UI state.
- Run the relevant tests before submitting changes and report any environment limitations clearly.

### Notes

- This is a pure Android client app with no backend services, databases, or Docker dependencies.
- Instrumented tests (`androidTest`) require an Android emulator or device and cannot run in the cloud VM without additional emulator setup.
- The lint report is written to `app/build/reports/lint-results-debug.html`.
- First build takes ~1-2 minutes due to Gradle daemon startup and dependency downloads; subsequent builds are much faster.
