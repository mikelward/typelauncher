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

- **File present, client matches** → `app/build.gradle.kts` applies the
  `com.google.gms.google-services` and
  `com.google.firebase.crashlytics` plugins, the SDKs auto-initialize via the
  manifest-merged `FirebaseInitProvider`, and telemetry flows. This is CI's debug
  and release builds, and a local *release* build (still unsuffixed
  `app.typelauncher`, so it matches the production client).
- **File present, no matching client** → the debug-variant Google Services tasks
  are skipped and any Firebase resources an earlier build generated are purged, so
  the SDKs find no `FirebaseApp` and `LauncherTelemetry` stays in its no-op path.
  This is the local *debug* build: it is `app.typelauncher.dev`, which the shared
  project deliberately does not register. Only outside CI — in CI a missing client
  means a stale `GOOGLE_SERVICES_JSON` secret, and the plugin's hard failure is
  left in place to surface it. See "Local development" below.
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
it locally, and that is deliberate. A debug build made outside CI has the
application ID `app.typelauncher.dev` (see `DEVELOPMENT.md`), and the shared
Firebase project does not register that ID — so `app/build.gradle.kts` skips the
Google Services tasks for it and purges any Firebase resources a previous tester
build left behind. A local *debug* build therefore stays dormant even with
the config present, which is what keeps a developer's day-to-day crashes and
traces out of the shared project alongside real tester data. (A local *release*
build keeps the unsuffixed `app.typelauncher` and does match the production
client, so telemetry is live there — rare, and deliberate when you do it.)

To genuinely enable local telemetry — normally only worth it when debugging the
telemetry wiring itself — register an `app.typelauncher.dev` Android app in the
Firebase project and re-download `google-services.json`. The client check then
matches and Firebase wires up for local builds with no further changes.

## Crashlytics mapping upload

CI builds run R8 fully optimizing — shrink, optimize, obfuscate
(`isMinifyEnabled = isCiBuild`, see `app/proguard-rules.pro`) — so a release
stack trace only becomes readable once its mapping file has been uploaded. The
`firebase-crashlytics` Gradle plugin does that for every minified variant on its
own: `uploadCrashlyticsMappingFileRelease` runs as part of `assembleRelease` /
`bundleRelease`, with no configuration of ours.

That upload depends on the same `google-services.json` everything else here
does. Without a matching client the plugin is never applied and the upload task
is disabled (`app/build.gradle.kts`), so a crash from such a build arrives with
obfuscated frames and nothing to resolve them against. A local build skips R8
altogether, so its traces are un-obfuscated to begin with.
