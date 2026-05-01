# Type Launcher Design Spec

This document records the current product and technical design decisions for Type Launcher. Keep it current when behavior, architecture, persistence, permissions, navigation, or testing strategy changes.

## Product shape

- Type Launcher is a minimal Android home screen launcher focused on fast typed app launching.
- The app is a single Android application module, `:app`, implemented in Kotlin.
- The launcher supports Android 14/API 34 and newer, targets SDK 36, and compiles against Android SDK Platform 36.1.
- `MainActivity` is both a normal launcher entry point and a selectable home screen. It is exported, uses `singleTask`, is excluded from recents, clears task state on launch, and keeps the soft keyboard visible for search-first interaction.

## UI architecture

- The active UI is Jetpack Compose rendered from `MainActivity.setContent`.
- `LauncherViewModel` owns screen state through a `StateFlow<LauncherUiState>`, and composables render from that immutable state.
- Material 3 is the visual system. Dynamic color is enabled on supported Android versions, with checked-in light and dark fallback color schemes.
- The app uses edge-to-edge layout and applies status bar, navigation bar, and IME insets through the top-level `Scaffold`.
- XML layout resources still exist in the project, but the current runtime UI path is Compose.

## Navigation

- The app currently has two screens: `Home` and `Agenda`.
- Horizontal swipes move between screens through a Compose `HorizontalPager`.
- Pager navigation uses an effectively infinite carousel so swiping left or right always wraps between `Home` and `Agenda`.
- `LauncherScreenSwitcher` is a legacy/custom `ViewAnimator` swipe helper and is not part of the current Compose runtime path.

## Home screen behavior

- The search field is focused on launch and requests the software keyboard.
- Typing filters installed apps by case-insensitive substring match against the app label.
- With an empty query, apps are sorted by descending recorded launch count, then alphabetically by display name.
- Pressing the keyboard search action or Enter launches the active first filtered app.
- A query equal to `settings`, ignoring case and surrounding whitespace, launches Android system settings instead of an installed app.
- Launching an app records one launch count and clears the search query.
- Long-press app actions expose App info, Dock/Undock, and Reset rank.
- App info opens Android's application details screen for the selected package.
- Reset rank clears that app's stored launch count.

## Installed apps and profiles

- Installed launcher apps are loaded through `LauncherApps` across available profiles when possible.
- If `LauncherApps` returns no activities, the app falls back to `PackageManager.queryIntentActivities` for `ACTION_MAIN` plus `CATEGORY_LAUNCHER`.
- Work apps are identified when their `UserHandle` differs from the personal profile or when tests inject package names through `TEST_WORK_PACKAGES_EXTRA`.
- Work apps show a badge on their icons.
- Apps are currently de-duplicated by lowercased display name plus work-profile status. This means multiple personal apps with the same display name are intentionally collapsed until disambiguation is designed.
- Work-profile launches use `LauncherApps.startMainActivity` when a component is available; fallback launches use an activity intent with launcher task flags.

## Dock behavior

- Docked app IDs are persisted in `SharedPreferences` under the `docked_apps` store.
- Dock order is the persisted insertion order.
- The dock is filtered by the same search query as the app list.
- Dock capacity is derived from screen width and icon size, with a minimum of one app.
- Trying to dock beyond the current capacity shows a toast and leaves persisted dock state unchanged.

## Agenda behavior

- The agenda screen requires `READ_CALENDAR`.
- Without permission, the agenda shows a permission card and requests the runtime permission from `MainActivity`.
- With permission, the app queries `CalendarContract.Instances` from local start-of-day through seven days ahead.
- Agenda ordering places all-day events intersecting the current UTC day before timed events that intersect or follow the current moment.
- Empty query results render an empty agenda state rather than an error.
- Calendar `SecurityException`s are treated as empty results after the permission check.

## Persistence

- Dock membership is stored in `SharedPreferences` as newline-separated app IDs.
- Launch ranking is stored in `SharedPreferences` as integer launch counts keyed by app ID.
- App IDs combine the user hash and launch component when available, falling back to package name.
- No backend service, database, or network dependency is part of the current design.

## Testing strategy

- JVM tests under `app/src/test` cover logic and Robolectric-backed Compose behavior.
- Robolectric Compose tests also write screenshot artifacts for key UI states.
- Instrumented tests under `app/src/androidTest` are reserved for emulator or device validation.
- Cursor Cloud is not expected to run connected Android tests because nested virtualization/KVM is unavailable.
- UI behavior changes should update screenshot coverage for the changed state.
