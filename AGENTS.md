# Type Launcher

Android home screen launcher app (Kotlin, single `:app` module).

## Project documentation

- Keep `SPEC.md` up to date when changing product behavior, architecture, persistence, permissions, navigation, or testing strategy.

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

### Emulator and connected tests

- **Android Emulator** (v36.5.11) is installed at `$ANDROID_HOME/emulator/` and exposed on `PATH` via `~/.bashrc`.
- **System image**: `system-images;android-36;google_apis;x86_64` (Google APIs Intel x86_64, API 36).
- **KVM is not available** in the Cursor Cloud VM (`/dev/kvm` does not exist). The VM runs inside Firecracker, which does not expose nested virtualization.
- Without KVM the emulator falls back to software emulation (TCG/QEMU), which is extremely slow and may not boot reliably. Running `connectedDebugAndroidTest` or `installDebug` in the cloud VM is therefore **not practical** without a KVM-capable host.
- To create an AVD for local or KVM-enabled CI use:
  ```sh
  avdmanager create avd -n "pixel_api36" -k "system-images;android-36;google_apis;x86_64" -d "pixel_6"
  emulator -avd pixel_api36 -no-window -no-audio -gpu swiftshader_indirect
  ```
- On a KVM-capable host, verify with `emulator -accel-check` (should report "KVM is operational").

### Notes

- This is a pure Android client app with no backend services, databases, or Docker dependencies.
- The lint report is written to `app/build/reports/lint-results-debug.html`.
- First build takes ~1-2 minutes due to Gradle daemon startup and dependency downloads; subsequent builds are much faster.
