# Firebase Crashlytics + Performance Monitoring

The launcher reports crashes and performance traces to Firebase. Both SDKs
identify installs by an anonymous Firebase Installation ID — there is no Google
sign-in or user-visible login. Devices without Google Play Services (e.g.
GrapheneOS, LineageOS without GApps) skip telemetry silently.

## What is captured

- **Crashlytics**: uncaught exceptions, ANRs (when the system reports them),
  and the most recent log lines from `LauncherDebugLog` as breadcrumbs. The
  `LauncherDebugLog.warning` call also forwards its `Throwable` (when present)
  to `recordException`.
- **Performance Monitoring**: the SDK auto-instruments `app_start` (cold/warm/
  hot) and screen rendering. The launcher adds these custom traces:
  - `launcher_cold_start` — `MainActivity.onCreate` through first pre-draw,
    attribute `saved_instance_state=present|absent`.
  - `launcher_initial_load` — the `viewModelScope` IO load that populates the
    app list and agenda on cold start, metric `app_count`.
  - `installed_apps_load` — the `LauncherApps`/`PackageManager` query, metric
    `app_count`.
  - `agenda_initial_load` — the `CalendarContract` query, attribute `state` =
    `Events` / `Empty` / `PermissionRequired`.

`LauncherTelemetry.kt` wraps both SDKs. Every entry point checks whether a
`FirebaseApp` is initialized in the current process and no-ops if not, so the
production code paths are unconditional and safe to call from Robolectric
tests, forks, and de-Googled devices.

## Build wiring

Firebase is gated on **two** things: `app/google-services.json` being present,
*and* that file carrying a client for the application ID being built.

- **File present, release variant, client matches** → `app/build.gradle.kts`
  applies the `com.google.gms.google-services` and
  `com.google.firebase.crashlytics` plugins, the SDKs auto-initialize via the
  manifest-merged `FirebaseInitProvider`, and telemetry flows. Release builds
  only — a release build is plain `app.typelauncher` wherever it was made, so
  whether this case applies turns entirely on whether the config in the build
  tree registers that client. **Today's CI secret does not**, so no shipping
  build is currently in this case at all; see the mapping-upload section below.
- **File present, debug variant** → the debug-variant Google Services tasks are
  skipped and any Firebase resources an earlier build generated are purged, so
  the SDKs find no `FirebaseApp` and `LauncherTelemetry` stays in its no-op path.
  Unconditionally, in every environment including CI: a build nobody installs
  has no business reporting into the project beside the released build's, and
  CI is where the debug variant is exercised most — `testDebugUnitTest` runs the
  unit and Robolectric screenshot suites against it. See "Local development"
  below.
- **File absent** → the plugins are skipped, the SDKs find no
  `FirebaseApp` at runtime, and `LauncherTelemetry` stays in its no-op path.
  Forks, the Cursor Cloud sandbox, and Robolectric tests build cleanly.

The `firebase-bom`, `firebase-crashlytics`, and `firebase-perf` dependencies
are always pulled so the wrapper compiles either way; only the gradle plugins
(which inject the project config and upload symbols) are conditional.

`app/google-services.json` is gitignored. The Firebase project ID it carries
is not strictly secret — Firebase apps are protected by the Android signing
key, not the contents of this file — but the maintainer's Firebase project is
private, and gating via existence makes the conditional build behavior easy to
reason about.

## Populating it in CI

CI materializes the file from a GitHub Actions secret named
`GOOGLE_SERVICES_JSON`. Set the secret's value to the **raw JSON** downloaded
from the Firebase console (Project settings → General → Your apps →
`google-services.json`):

```text
{
  "project_info": { ... },
  "client": [ ... ],
  ...
}
```

The `Materialize google-services.json` step in
`.github/workflows/ci.yml` writes it to `app/google-services.json`
before the build. The step is gated on the secret being non-empty *and* on the
event being a push, so pull requests — forks or not — build without Firebase at
all, and the Crashlytics plugin never applies there. On a push it does apply,
and `RELEASE_KEYSTORE_FILE` keeps the mapping upload to the job that deploys, so
the build job's release APK doesn't upload a mapping for an artifact nobody
receives.

## Local development

Day-to-day work does not need telemetry: with no `google-services.json` in
`app/`, the build skips the Firebase plugins and the launcher runs identically
minus the trace/crash reports.

Dropping the same `google-services.json` into `app/` is **not** enough to enable
it for a debug build, and that is deliberate. `app/build.gradle.kts` skips the
Google Services tasks for the debug variant outright and purges any Firebase
resources a previous build left behind, so a debug build stays dormant even with
the config present. That is what keeps a developer's day-to-day crashes and
traces — and CI's test runs — out of the shared project alongside real tester
data.

It is not conditional on the application ID any more. It used to be: the debug
build was `app.typelauncher.dev`, which the project deliberately did not
register, so dormancy fell out of a client being absent rather than out of any
decision. Registering that ID silently switched telemetry on, and CI was exempt
from the check entirely. Now the debug variant simply never gets Firebase, and
the suffix is plain `.debug` everywhere.

A local *release* build is the exception and always was: it keeps the unsuffixed
`app.typelauncher`, matches the production client, and reports. Rare, and
deliberate when you do it.

To exercise the telemetry wiring itself, build the release variant.

## Crashlytics mapping upload

Release builds run R8 fully optimizing — shrink, optimize, obfuscate — on any
machine, not only in CI (`isMinifyEnabled = true`, see
`app/proguard-rules.pro`), so a release stack trace only becomes readable once
its mapping file has been uploaded. The `firebase-crashlytics` Gradle plugin
does that for every minified variant on its own:
`uploadCrashlyticsMappingFileRelease` runs as part of `assembleRelease` /
`bundleRelease`, with no configuration of ours.

That upload depends on the same `google-services.json` everything else here
does. Without a matching client the plugin is never applied and the upload task
is disabled (`app/build.gradle.kts`), so a crash from such a build arrives with
obfuscated frames and nothing to resolve them against.

Which variant you built decides whether that matters. A **debug** build runs no
R8 at all, so its traces are un-obfuscated to begin with and need no mapping. A
**release** build always runs R8, on any machine — so a release APK you build
locally is obfuscated too, while the mapping upload stays gated to the deploy
lane. Its frames have nothing to resolve against. Build debug for day-to-day
work.

**That is the current state, not a hypothetical, and it is worse than a missing
mapping.** The `google-services.json` CI materializes carries no
`app.typelauncher` release client, so `hasReleaseClient` is false and both
`processReleaseGoogleServices` **and** `uploadCrashlyticsMappingFileRelease` are
disabled in every lane — the `deploy` job included. Every `main` run's
`Build release AAB` step logs `uploadCrashlyticsMappingFileRelease SKIPPED`, and
has done since before R8 was turned on.

Disabling `processReleaseGoogleServices` means the shipping build carries no
generated `google_app_id`. No `FirebaseApp` initializes, so `LauncherTelemetry`
takes the same no-op path it takes in a checkout with no config at all. **The
Play build reports nothing** — no crashes, no traces. There are no obfuscated
stack traces going unresolved, because none are being sent.

So the missing mapping is a symptom, not the problem, and fixing the mapping
alone would fix nothing. The remedy is a Firebase console change: register an
`app.typelauncher` release client and refresh the `GOOGLE_SERVICES_JSON` secret.
Both tasks then re-enable together.
