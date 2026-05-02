# Type Launcher Design Spec

This document records the current product and technical design decisions for Type Launcher. Keep it current when behavior, architecture, persistence, permissions, navigation, or testing strategy changes.

## Product shape

- Type Launcher is a minimal Android home screen launcher focused on fast typed app launching.
- The app is a single Android application module, `:app`, implemented in Kotlin.
- The launcher supports Android 14/API 34 and newer, targets SDK 36, and compiles against Android SDK Platform 36.1.
- `MainActivity` is both a normal launcher entry point and a selectable home screen. It is exported, uses `singleTask`, is excluded from recents, clears task state on launch, and keeps the soft keyboard visible for search-first interaction.
- Launcher behavior has been treated as the app's primary identity since the initial implementation, rather than as a secondary feature inside a conventional app shell.

## UI architecture

- The active UI is Jetpack Compose rendered from `MainActivity.setContent`.
- `LauncherViewModel` owns screen state through a `StateFlow<LauncherUiState>`, and composables render from that immutable state.
- Runtime code is split into focused layers while keeping the `app.typelauncher` package: `MainActivity.kt` owns activity lifecycle and Android launchers, `LauncherViewModel.kt` owns state orchestration, `model/` contains immutable UI models, `ui/` contains Compose screens/theme/test tags/previews, `store/` contains `SharedPreferences` stores, and small helpers such as `LauncherIntents.kt`, `AgendaEventOrganizer.kt`, and `SettingsLaunchGate.kt` stay at the package root.
- Material 3 is the visual system. Dynamic color is enabled on supported Android versions, with checked-in light and dark fallback color schemes.
- The app uses edge-to-edge layout and applies status bar, navigation bar, and IME insets through the top-level `Scaffold`.
- Debug builds emit logcat diagnostics under the `TypeLauncherDebug` tag for activity/system lifecycle callbacks, first pre-draw, top-level Compose lifecycle/state rendering, launcher app loading, agenda/widget loading, and app/widget launch handoffs.
- All builds (not just debug) capture the same diagnostic events into a process-local in-memory ring buffer, capped at 300 entries, that the bug-report helper attaches to share intents.
- All builds also forward those events to Firebase Crashlytics as breadcrumbs and forward `LauncherDebugLog.warning` throwables to `recordException`, when the Firebase project config is present at build time. Telemetry is gated on `app/google-services.json` existing in the build tree: when it is present, the `google-services` and `firebase-crashlytics` Gradle plugins are applied and the Firebase SDKs auto-initialize; when it is absent (forks, Cursor Cloud, Robolectric tests), the `LauncherTelemetry` wrapper detects the missing `FirebaseApp` and no-ops. CI materializes the file from the `GOOGLE_SERVICES_JSON` secret. See `docs/firebase-telemetry.md`.
- Custom Firebase Performance traces complement the SDK's auto-instrumented `app_start`: `launcher_cold_start` brackets `MainActivity.onCreate` through first pre-draw, `launcher_initial_load` brackets the `viewModelScope` cold-start IO load, `installed_apps_load` and `agenda_initial_load` bracket the `LauncherApps`/`PackageManager` and `CalendarContract` queries respectively, and `app_icon_load` brackets every `AppIconLoader.load` call (one trace per cache miss) with `resolve_ms` (time on `Dispatchers.IO` resolving the `Drawable`) and `bitmap_ms` (time on `Dispatchers.Default` running `toBitmap`) sub-metrics plus a `result=success|drawable_missing` attribute. Cache hit/miss counts are flushed to `LauncherDebugLog` (and from there to Crashlytics breadcrumbs) every 50 lookups so steady-state hit rate is recoverable from session breadcrumbs.
- XML layout resources still exist from the earlier View-based implementation, but the current runtime UI path is Compose.
- The Compose migration intentionally removed the old XML-driven home/agenda rendering path while preserving the launcher behaviors covered by tests.

## Navigation

- The app has three carousel screens, `Home`, `Widgets`, and `Agenda`, plus a settings page opened from Home.
- Horizontal swipes move between screens through a Compose `HorizontalPager`; swiping right-to-left from `Home` opens the +1 `Widgets` screen, then the `Agenda` screen.
- Pager navigation uses an effectively infinite carousel so swiping left or right always wraps across `Home`, `Widgets`, and `Agenda`; user swipes are handled as discrete gestures, not free-scrolling pager flings, so each gesture can advance exactly one screen at most.
- Horizontal drags rubber-band the pager during the gesture: the page tracks the finger sub-linearly (offset = `rawDrag · pageWidth / (pageWidth + |rawDrag|)`, asymptoting at one page width), so the next/previous screen edges into view as resistance to the drag, and the visible offset can never exceed one page even if the user keeps dragging. On release the gesture commits when either the raw drag distance is at least 40 % of the page width, or the release velocity is at least 800 dp/s in the same direction the page was being pulled (a fling escape hatch); otherwise the pager springs back to the start page. A release velocity of 200 dp/s or more in the opposite direction of the net drag also cancels the commit, so a pull-then-push-back gesture stays on the current screen.
- A downward swipe over an area that no child consumes (search field, dock, blank space) pulls down the system notification shade via `StatusBarManager.expandNotificationsPanel`. The launcher requires `EXPAND_STATUS_BAR` and uses Compose's `PointerEventPass.Final` so vertical scrolling inside the apps list, widgets list, and agenda list still scrolls those lists rather than triggering the shade. The threshold is 96dp so the gesture must be deliberate.
- Settings is an in-app page outside the carousel, opened by the gear icon in the empty search field and closed with a Done button. It includes a button that opens Android's home role request flow so Type Launcher can be selected as the default launcher.
- Whenever Android delivers a launcher-entry intent (`ACTION_MAIN` with `CATEGORY_HOME` or `CATEGORY_LAUNCHER`) to the running activity — for example when the user presses the home button or performs the system "swipe up to home" gesture — `MainActivity.onNewIntent` resets the launcher to the Home app-list screen, closes the settings page, and dismisses the widget picker. Initial cold-start onCreate already starts on Home, so the reset only matters when Android re-enters the existing activity instance.
- The settings page header has an overflow menu with a Report bug action. Selecting it captures a screenshot of the current window via `PixelCopy`, builds a text payload (build/device info, persisted dock and widget settings, the recent log buffer), copies the text to the clipboard, and fires `Intent.ACTION_SEND` so the system share sheet can deliver the report. Screenshots are stored in `cacheDir/bug-reports/` (only the latest is kept) and exposed through a `FileProvider` whose authority is `${applicationId}.fileprovider`.
- `LauncherScreenSwitcher` is a legacy/custom `ViewAnimator` swipe helper and is not part of the current Compose runtime path.
- Swipe handling was introduced because child lists can otherwise consume gestures before launcher-level navigation sees them.

## Home screen behavior

- The search field is focused on launch and requests the software keyboard.
- Typing filters installed apps and groups results into three tiers, rendered in this order: (1) **Prefix** — labels that start with the query, case-insensitive; (2) **Anchored** — labels that match the case-insensitive anchored fuzzy rule but aren't a prefix match; (3) **Substring** — labels that contain the query as a case-insensitive substring but don't match the anchored rule. Within each tier the input alphabetical order is preserved (the sort is stable). The anchored fuzzy rule: the first query character must equal either the first letter of the label or one of the label's uppercase letters; from that anchor, the remaining query characters must appear in order; a query character that lines up with the very next label character is always accepted, while a query character that requires skipping over one or more label characters must land on a word boundary — an uppercase letter or the first character after whitespace. Worked examples: `mail` puts `Mail` first (prefix), then `iMail` (anchored at capital M), then `Gmail` (substring only — `m` is mid-word lowercase); `gm` matches `Google Mail` and `google mail` in the anchored tier (G/g anchors, M is a capital, m follows a space); `boa` matches `BofA` (anchored: B anchors, `o` is consecutive, `A` is a capital skip-target); `bf` does not match `BofA` at any tier (skipping past `o` lands on the mid-word lowercase `f`, and `bf` is not a substring); `ATV` matches `Apple TV` in the anchored tier; `fa` does not anchor against `Air France` or `Fly Delta` (the second `a` sits in the middle of a word) but a label containing `fa` as a substring would still appear in the substring tier; single-character queries like `s` no longer hide `1password` — it falls into the substring tier below any prefix or anchor matches.
- With an empty query, apps are sorted by descending recorded launch count, then alphabetically by display name. A settings toggle switches the empty-query order to alphabetical by display name. In both modes, docked apps are pushed below non-docked apps only while the dock is enabled.
- Pressing the keyboard search action or Enter opens in-app settings when the query is blank, opens Android system settings when the query is `settings`, or launches the active first filtered app otherwise.
- The active first filtered app is highlighted in both text-list and icon-list modes so the Enter/search launch target is visible. In the text list the entire row is painted with a pale blue background (`#CFE2FF` light / `#274C7A` dark) using 8dp rounded corners. In the icon-only grid a pale blue rounded square (8dp corners, 8dp padding around the icon) is drawn behind the active icon. Both colors are fixed rather than derived from the Material dynamic color scheme because dynamic color produces tokens too close to the card surface to be reliably visible.
- Enter key handling is designed around Android editor action variance: an Enter down event should be enough to launch, while repeat and matching up events should not double-launch.
- A query equal to `settings`, ignoring case and surrounding whitespace, launches Android system settings instead of an installed app.
- The search field trailing slot shows the settings gear when the query is empty, or the clear-search button when the query is non-empty; both controls are never shown together.
- Launching an app records one launch count and clears the search query.
- Long-press app actions expose App info, Dock/Undock, and Reset rank.
- App info opens Android's application details screen for the selected package.
- Reset rank clears that app's stored launch count.
- A settings toggle can switch the installed app list from text rows to an icon-only grid. It is off by default, and icon-only apps use the same icon size and visual treatment as the dock.
- A settings toggle switches the app list sort order between by-usage (default) and alphabetical.
- The UI intentionally omits section headers such as "Installed apps", "Dock", and "Agenda" to keep the launcher compact and action-oriented.

## Installed apps and profiles

- Installed launcher apps are loaded through `LauncherApps` across available profiles when possible.
- If `LauncherApps` returns no activities, the app falls back to `PackageManager.queryIntentActivities` for `ACTION_MAIN` plus `CATEGORY_LAUNCHER`.
- The cold-start load resolves only labels (not icons) so the app list can render as soon as `LauncherApps.getActivityList` returns. App icons are fetched lazily by `AppIconLoader` (an in-memory `LruCache<String, ImageBitmap>` keyed by `userHandle.hashCode():componentName:sizePx`) when each row enters the viewport — rows show the placeholder surface until the bitmap arrives. Each call site passes its display `Dp` and the `Drawable` is rasterized exactly once at the matching pixel size, so a 40dp text-row icon and a 56dp dock icon are stored as separate entries instead of upscaling a single shared bitmap. The cache uses a 24 MB byte budget rather than an entry count so per-size caching can't blow up memory on high-density displays. The `Drawable` resolve runs on `Dispatchers.IO`, the `toBitmap` conversion runs on `Dispatchers.Default`, and only the resulting `ImageBitmap` is read on the composition thread.
- `AppMetadataStore` persists a JSON snapshot of personal-profile installed apps (display name, package, component, work flag, `LauncherApps`-vs-`PackageManager` launch flag) under the `app_metadata` `SharedPreferences`. The view model reads it synchronously during initialization so the dock and last-known app list paint on the very first frame after the second-or-later launch; the fresh `LauncherApps` load runs immediately after and replaces the snapshot once it returns. Work-profile apps are intentionally excluded because reconstructing their `UserHandle` outside a live `LauncherApps` query is not generally possible; they re-appear after the fresh load completes.
- Work apps are identified when their `UserHandle` differs from the personal profile or when tests inject package names through `TEST_WORK_PACKAGES_EXTRA`.
- Work apps show a badge on their icons.
- Apps are currently de-duplicated by lowercased display name plus work-profile status. This means multiple personal apps with the same display name are intentionally collapsed until disambiguation is designed.
- Work-profile launches use `LauncherApps.startMainActivity` when a component is available; fallback launches use an activity intent with launcher task flags.

## Dock behavior

- Docked app IDs are persisted in `SharedPreferences` under the `docked_apps` store.
- Dock settings are persisted in `SharedPreferences` under the `dock_settings` store.
- Dock order is the persisted insertion order.
- The dock is filtered by the same search query as the app list.
- Dock visibility can be disabled from settings; disabling hides the Home dock and preview dock, and the settings app-list preview expands into the combined app-list/dock preview space.
- Dock icon size is derived from a settings slider for the number of icons visible across the dock, so a larger visible icon count shrinks each icon and shows more apps before horizontal scrolling.
- Docked app count is not capped by the visible icon count; extra docked apps remain available by scrolling the dock row.
- This feature was renamed from "pinned apps" to "dock"; new UI, tests, strings, and docs should use dock terminology.
- The dock is pinned after the app list and represented as a horizontal icon row rather than a second text list.

## Widget behavior

- The widgets screen is the launcher's +1 screen between the app list and agenda.
- The top of the widgets screen is a full-width add card with a large plus button that opens the in-app add-widget page.
- The add-widget page groups available home-screen widget providers in collapsed per-app rows and expands providers on demand. Expanding a provider row immediately renders its generated/static/placeholder preview and an Add button — there is no separate "Preview" toggle to load it.
- The add-widget page has a filter field above the provider list that narrows the visible app groups by name using the same three-tier matcher as the installed-app list (prefix → anchored fuzzy → substring, via `String.launcherMatchTier`). App groups are reordered into those tiers when the filter is non-empty, so typing behavior in the widget picker stays in sync with the launcher's app filter.
- Selected app widget IDs are persisted in `SharedPreferences` under the `widgets` store and rendered through `AppWidgetHost` when provider info is available.
- Static widget previews and provider-list app-icon thumbnails wrap their `Drawable.toBitmap().asImageBitmap()` conversions in `remember`, keyed on the source `Drawable` (or `WidgetProvider`), so scrolling the picker doesn't re-rasterize the same bitmap on every recomposition.
- The MVP optimizes for full-width 4x1-style widgets by giving hosted widgets the whole page width and using the provider minimum height, reported in pixels, converted to Compose density-independent height with a launcher floor.
- Provider rows in the picker show a "Very small, will be padded" note when the provider's reported minimum height (in dp) is below the launcher's floor, so users see at add time that those widgets will render taller than they request.
- Long-pressing a widget opens a compact action menu with a Remove item; removing a widget deletes the host ID and updates the persisted widget list.

## Agenda behavior

- The agenda screen requires `READ_CALENDAR`.
- Without permission, the agenda shows a permission card and requests the runtime permission from `MainActivity`.
- With permission, the app queries `CalendarContract.Instances` from local start-of-day through seven days ahead.
- Agenda ordering places all-day events intersecting the current UTC day before timed events that intersect or follow the current moment.
- Empty query results render an empty agenda state rather than an error.
- Calendar `SecurityException`s are treated as empty results after the permission check.
- Each event row mirrors the Google Calendar schedule view: the start time on the left, a vertical stripe in the per-event color (`CalendarContract.Instances.DISPLAY_COLOR`, which Google Calendar populates with the user's calendar color), and the title on the right. No leading event-type icon is drawn.
- Event rows show only the start time (and end time for ranges); the date is never repeated on individual rows. Date context is communicated only by the day-header separators described below.
- Time text is preprocessed so that whitespace inside each time half is non-breaking (U+00A0) and the separator between the start/end times is a regular-space-flanked en-dash (U+2013). The time column is a fixed 72dp wide so the colored stripe aligns across every row regardless of whether the event has a time range, a single start time, or is all-day; longer ranges wrap at the dash (e.g. `12:00 PM` / `– 1:00 PM`) instead of splitting mid-time, and single-time strings (`9:30 AM`, `All day`) pass through unchanged.
- Events are grouped by their local calendar day. Between the last event of one day and the first event of the next day, a day-header row is rendered as a date label followed by a horizontal divider line ("label the line with the date"). Today and tomorrow render localized `Today` / `Tomorrow` labels; other days render a weekday plus abbreviated month/day. All-day events that intersect today are grouped under the today header.
- Tapping a row launches `Intent.ACTION_VIEW` on `content://com.android.calendar/events/<id>` with `EXTRA_EVENT_BEGIN_TIME`/`EXTRA_EVENT_END_TIME` so the user's calendar app opens the event details. If no calendar app handles the intent the tap is a no-op.

## Persistence

- Dock membership is stored in `SharedPreferences` as newline-separated app IDs.
- Dock visibility, dock icon size, the app-list icon-only preference, and the app-list sort order are stored in `SharedPreferences`.
- Launch ranking is stored in `SharedPreferences` as integer launch counts keyed by app ID.
- A snapshot of personal-profile installed apps for fast cold-start render is stored as JSON in the `app_metadata` `SharedPreferences`.
- App IDs combine the user hash and launch component when available, falling back to package name.
- No backend service, database, or network dependency is part of the current design.

## Versioning and distribution

- `versionCode` is derived at configure time from `git rev-list --count HEAD` so it monotonically increases per commit and is reproducible (same commit always produces the same code). When the git command is unavailable (for example, in source-only archives) it falls back to `1`.
- `versionName` is `"<base>.<commitCount>+<shortSha>"` (for example, `1.0.50+5e6eb54`), where the base prefix is held in a single `baseVersionName` constant in `app/build.gradle.kts`. The `+` separator follows the SemVer "build metadata" convention so the short commit SHA is always discoverable from a built APK.
- CI must check the repository out with full history (`fetch-depth: 0` for `actions/checkout`) for the commit count to be correct; shallow clones produce a stuck count of `1`.
- Internal testing builds are distributed through Firebase App Distribution from CI rather than from gradle. The `build` job's last step uses `wzieba/Firebase-Distribution-Github-Action@v1` to upload the debug APK (`app/build/outputs/apk/debug/app-debug.apk`) to the `testers` group. Distributing the debug APK avoids configuring a release signing keystore at this stage; debug-signed builds remain installable for internal testers.
- The upload step is gated on `github.event_name == 'push'`, the ref being `refs/heads/main`, and the `FIREBASE_APP_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON` secrets being non-empty. This keeps feature-branch and fork CI runs from spamming testers and lets a fresh checkout still pass without Firebase configuration.
- Release notes default to the head commit message followed by the run number and full SHA. The first ~60 characters land in the tester device's push notification, so the commit subject should stay informative.
- A signed release AAB is also uploaded to the Google Play Store **internal** testing track in parallel with the Firebase distribution, using `r0adkll/upload-google-play@v1`. The release `signingConfig` in `app/build.gradle.kts` is populated from `RELEASE_KEYSTORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` env vars that CI materialises from secrets. Local builds without those env vars produce an unsigned release AAB so forks build cleanly. The AAB build and Play upload steps share the same gate as the Firebase step (push to `main` plus secrets present), with the additional requirement that `RELEASE_KEYSTORE_BASE64` and `PLAY_SERVICE_ACCOUNT_JSON` are non-empty. Play App Signing re-signs with its managed app-signing key, so the keystore CI uses is only the upload key, not what testers actually run. See `docs/play-store-internal-track.md`.

## Testing strategy

- JVM tests under `app/src/test` cover logic and Robolectric-backed Compose behavior.
- Robolectric Compose tests also write screenshot artifacts for key UI states.
- Instrumented tests under `app/src/androidTest` are reserved for emulator or device validation.
- Cursor Cloud is not expected to run connected Android tests because nested virtualization/KVM is unavailable.
- UI behavior changes should update screenshot coverage for the changed state; the widget add card is covered by Robolectric Compose screenshot output.
- Screenshot coverage moved from fake/static keyboard images, to emulator-backed checks, to Robolectric Compose screenshots that seed realistic launcher rows for repeatable local/cloud feedback.
- Tests are expected to assert visible product decisions, such as header removal, dock placement, row/icon alignment, work badges, and carousel wrap behavior, not just implementation details.

## History-derived decisions

- The app started from a standard Android skeleton, then quickly made the home-screen launcher role the central product boundary.
- Search came before broad navigation or customization, and the design has continued to optimize for typing first, keyboard visible, active first result, and fast launch.
- The installed-app list evolved from a plain list into a ranked launcher surface: anchored fuzzy filtering handles narrowing, launch counts handle empty-query ordering, and Reset rank exists so users can correct learned ordering. Filtering started as plain case-insensitive substring matching, was tightened to anchor on word boundaries (start of label, capital letters, or characters after whitespace) so typing `s` would not surface apps like `1password`, and then evolved into a three-tier ranking (prefix → anchored → substring) that brings substring matches back as a low-priority fallback so queries like `mail` can still surface `Gmail` without elbowing out the strict matches.
- Long press is the common gesture for secondary app actions. It first opened app info, then became the home for dock/undock and rank reset actions.
- The dock evolved from "pinned apps" into a bottom icon row with dynamic capacity, query filtering, insertion order, and capacity feedback.
- Work-profile support is intentionally visible but lightweight: work apps are included in the same launcher surface, marked with badges, and launched through profile-aware `LauncherApps` when possible.
- Agenda was added as a launcher-adjacent "-1" screen backed by Android's calendar provider, not as a separate navigation stack or remote service.
- Compose Material 3 replaced the earlier View/XML UI so new UI work should extend the Compose path; retained XML files are historical artifacts unless code starts using them again.
- The current visual direction is compact: headers were removed, app rows were tightened, dock icons were aligned with app rows, and badges remain visible in both list and dock contexts.
- The navigation model changed from a finite two-screen switcher to a wrapping carousel so either swipe direction remains useful from either screen.
