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
- Material 3 is the visual system. Dynamic color is enabled on supported Android versions, with checked-in light and dark fallback color schemes.
- The app uses edge-to-edge layout and applies status bar, navigation bar, and IME insets through the top-level `Scaffold`.
- XML layout resources still exist from the earlier View-based implementation, but the current runtime UI path is Compose.
- The Compose migration intentionally removed the old XML-driven home/agenda rendering path while preserving the launcher behaviors covered by tests.

## Navigation

- The app has three carousel screens, `Home`, `Widgets`, and `Agenda`, plus a settings page opened from Home.
- Horizontal swipes move between screens through a Compose `HorizontalPager`; swiping right-to-left from `Home` opens the +1 `Widgets` screen, then the `Agenda` screen.
- Pager navigation uses an effectively infinite carousel so swiping left or right always wraps across `Home`, `Widgets`, and `Agenda`; user swipes are handled as discrete gestures, not free-scrolling pager flings, so each gesture can advance exactly one screen at most.
- Settings is an in-app page outside the carousel, opened by the gear icon in the empty search field and closed with a Done button.
- `LauncherScreenSwitcher` is a legacy/custom `ViewAnimator` swipe helper and is not part of the current Compose runtime path.
- Swipe handling was introduced because child lists can otherwise consume gestures before launcher-level navigation sees them.

## Home screen behavior

- The search field is focused on launch and requests the software keyboard.
- Typing filters installed apps by case-insensitive substring match against the app label.
- With an empty query, apps are sorted by descending recorded launch count, then alphabetically by display name.
- Pressing the keyboard search action or Enter launches the active first filtered app.
- Enter key handling is designed around Android editor action variance: an Enter down event should be enough to launch, while repeat and matching up events should not double-launch.
- A query equal to `settings`, ignoring case and surrounding whitespace, launches Android system settings instead of an installed app.
- The search field trailing slot shows the settings gear when the query is empty, or the clear-search button when the query is non-empty; both controls are never shown together.
- Launching an app records one launch count and clears the search query.
- Long-press app actions expose App info, Dock/Undock, and Reset rank.
- App info opens Android's application details screen for the selected package.
- Reset rank clears that app's stored launch count.
- The UI intentionally omits section headers such as "Installed apps", "Dock", and "Agenda" to keep the launcher compact and action-oriented.

## Installed apps and profiles

- Installed launcher apps are loaded through `LauncherApps` across available profiles when possible.
- If `LauncherApps` returns no activities, the app falls back to `PackageManager.queryIntentActivities` for `ACTION_MAIN` plus `CATEGORY_LAUNCHER`.
- Work apps are identified when their `UserHandle` differs from the personal profile or when tests inject package names through `TEST_WORK_PACKAGES_EXTRA`.
- Work apps show a badge on their icons.
- Apps are currently de-duplicated by lowercased display name plus work-profile status. This means multiple personal apps with the same display name are intentionally collapsed until disambiguation is designed.
- Work-profile launches use `LauncherApps.startMainActivity` when a component is available; fallback launches use an activity intent with launcher task flags.

## Dock behavior

- Docked app IDs are persisted in `SharedPreferences` under the `docked_apps` store.
- Dock settings are persisted in `SharedPreferences` under the `dock_settings` store.
- Dock order is the persisted insertion order.
- The dock is filtered by the same search query as the app list.
- Dock visibility can be disabled from settings; disabling hides the Home dock but keeps the settings preview available.
- Dock icon size can be adjusted from settings from 40dp to 80dp, defaulting to 56dp, with the real dock component shown as a live preview.
- Dock capacity is derived from screen width and the configured icon size, with a minimum of one app.
- Trying to dock beyond the current capacity shows a toast and leaves persisted dock state unchanged.
- This feature was renamed from "pinned apps" to "dock"; new UI, tests, strings, and docs should use dock terminology.
- The dock is pinned after the app list and represented as a horizontal icon row rather than a second text list.

## Widget behavior

- The widgets screen is the launcher's +1 screen between the app list and agenda.
- The top of the widgets screen is a full-width add card with a large plus button that opens Android's widget picker.
- Selected app widget IDs are persisted in `SharedPreferences` under the `widgets` store and rendered through `AppWidgetHost` when provider info is available.
- The MVP optimizes for full-width 4x1-style widgets by giving hosted widgets the whole page width and using the provider minimum height, reported in pixels, converted to Compose density-independent height with a launcher floor.
- Long-pressing a widget opens a compact action menu with a Remove item; removing a widget deletes the host ID and updates the persisted widget list.

## Agenda behavior

- The agenda screen requires `READ_CALENDAR`.
- Without permission, the agenda shows a permission card and requests the runtime permission from `MainActivity`.
- With permission, the app queries `CalendarContract.Instances` from local start-of-day through seven days ahead.
- Agenda ordering places all-day events intersecting the current UTC day before timed events that intersect or follow the current moment.
- Empty query results render an empty agenda state rather than an error.
- Calendar `SecurityException`s are treated as empty results after the permission check.

## Persistence

- Dock membership is stored in `SharedPreferences` as newline-separated app IDs.
- Dock visibility and icon size are stored in `SharedPreferences`.
- Launch ranking is stored in `SharedPreferences` as integer launch counts keyed by app ID.
- App IDs combine the user hash and launch component when available, falling back to package name.
- No backend service, database, or network dependency is part of the current design.

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
- The installed-app list evolved from a plain list into a ranked launcher surface: substring filtering handles narrowing, launch counts handle empty-query ordering, and Reset rank exists so users can correct learned ordering.
- Long press is the common gesture for secondary app actions. It first opened app info, then became the home for dock/undock and rank reset actions.
- The dock evolved from "pinned apps" into a bottom icon row with dynamic capacity, query filtering, insertion order, and capacity feedback.
- Work-profile support is intentionally visible but lightweight: work apps are included in the same launcher surface, marked with badges, and launched through profile-aware `LauncherApps` when possible.
- Agenda was added as a launcher-adjacent "-1" screen backed by Android's calendar provider, not as a separate navigation stack or remote service.
- Compose Material 3 replaced the earlier View/XML UI so new UI work should extend the Compose path; retained XML files are historical artifacts unless code starts using them again.
- The current visual direction is compact: headers were removed, app rows were tightened, dock icons were aligned with app rows, and badges remain visible in both list and dock contexts.
- The navigation model changed from a finite two-screen switcher to a wrapping carousel so either swipe direction remains useful from either screen.
