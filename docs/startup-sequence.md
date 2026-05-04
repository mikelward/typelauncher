# Startup sequence

This document summarizes the launcher's cold-open path in execution order, including cached state, fresh loading, rendering, async work, thread use, and IME triggering.

## Cold-open order

1. Android launches `MainActivity`. The manifest defaults the activity to `stateAlwaysVisible|adjustResize`, so the window is biased toward the launcher's primary "type immediately" flow.
2. `MainActivity.onCreate` runs on the UI thread: it calls `super.onCreate`, enables edge-to-edge, starts the `launcher_cold_start` trace, creates `LauncherAppWidgetHost` / `AppWidgetManager`, and obtains `LauncherViewModel`.
3. `LauncherViewModel` construction synchronously reads lightweight persisted state on the UI thread:
   - `WidgetStore`: selected widget IDs and custom heights.
   - `DockSettingsStore`: dock visibility, dock size, list mode, sort order, recents setting, notification pull-down behavior, keyboard auto-show, and theme mode.
   - `AppLaunchStatsStore`: launch counts and recents.
   - `HiddenAppStore`: hidden app IDs.
   - `DockedAppStore`: docked app IDs.
   - `AppMetadataStore`: cached personal-profile app metadata.
   - `ActiveNotifications.hasListenerAccess`: current notification-listener permission state.
4. The initial `LauncherUiState` is created from those synchronous reads. If cached app metadata is missing, `isLoadingApps` starts `true`; if cached metadata exists, the UI can render last-known apps immediately while the fresh system load runs.
5. If `AppMetadataStore` has cached app metadata, the view model synchronously applies disambiguators and derives the first-frame app surfaces: filtered app list, docked apps, recents, hidden apps, and notifying apps. This is the warm-cache path that lets labels and rows paint before `LauncherApps` returns.
6. The view model starts observing `ActiveNotifications.packages` from `viewModelScope` on the main dispatcher. Updates refresh only the notifying-app list.
7. The view model starts icon snapshot restore from `viewModelScope`: the coroutine starts on main, switches to `Dispatchers.IO`, reads `filesDir/icon_snapshots`, reconstructs bitmaps, and inserts them into `AppIconLoader`'s in-memory LRU cache. This avoids blocking first composition on disk reads and bitmap allocation.
8. The view model starts the fresh installed-app load from `viewModelScope`: the coroutine starts on main, switches to `Dispatchers.IO`, queries `LauncherApps` across profiles, falls back to `PackageManager.queryIntentActivities` if needed, deduplicates, sorts, and applies disambiguators.
9. The view model registers `LauncherApps.Callback` before the fresh load finishes. Package changes during the cold-start load are latched and replayed after the initial result publishes, rather than racing a concurrent reload.
10. Back in `MainActivity.onCreate`, before `setContent`, the activity applies persisted keyboard and theme preferences to the window:
    - keyboard auto-show enabled: `SOFT_INPUT_STATE_ALWAYS_VISIBLE | SOFT_INPUT_ADJUST_RESIZE`;
    - keyboard auto-show disabled: `SOFT_INPUT_STATE_ALWAYS_HIDDEN | SOFT_INPUT_ADJUST_RESIZE`;
    - edge-to-edge system bar styles are matched to the persisted theme mode.
11. `MainActivity` starts lifecycle collectors for keyboard-auto-show, theme mode, and home-ready, then calls `setContent` on the UI thread.
12. `setContent` composes `TypeLauncherTheme` and `TypeLauncherApp`, collecting `LauncherViewModel.uiState` with lifecycle awareness.
13. The first Compose pass renders the Home screen. `SearchCard` is composed before the home body.
14. `TypeLauncherApp` deliberately holds back the Home body for one frame. During that first frame, Home reserves the remaining space with a spacer, so the search field can compose and lay out before the app list, dock, notification bar, and recents do their heavier work.
15. `SearchCard` runs `LaunchedEffect(autoShowKeyboard)` after composition:
    - if enabled, it calls `FocusRequester.requestFocus()` and `LocalSoftwareKeyboardController.show()`;
    - if disabled, it skips both calls, and the activity-level soft input mode already keeps the IME hidden until the user taps the field.
16. The top-level carousel also holds off composing offscreen pages until after the first frame. This keeps Widgets and Agenda UI work out of the critical first search/IME frame.
17. One frame later, `homeBodyReady` flips `true`; Home composes the app list, notification bar, dock, and recents from the current state. Depending on cache state, this is either cached app data or a loading state.
18. Visible app icons are loaded lazily by each row/icon:
    - composition first checks `AppIconLoader.cached(id, sizePx)` on the composition thread;
    - on a miss, `AppIconLoader.load` resolves the `Drawable` on `Dispatchers.IO`;
    - bitmap rasterization runs on `Dispatchers.Default`;
    - the resulting `ImageBitmap` updates Compose state and is stored in the LRU cache.
19. When the fresh installed-app load returns, the coroutine resumes on main, updates `installedApps`, may prefill the dock on first run, recomputes app surfaces, sets `isLoadingApps = false`, sets `isFreshAppLoadComplete = true`, and asynchronously saves the new metadata snapshot on `Dispatchers.IO`.
20. `HomeReadySignal` waits until `isFreshAppLoadComplete` is true. When keyboard auto-show is enabled, it then waits for either IME visibility or a 1500 ms fallback; when keyboard auto-show is disabled, it skips the IME wait because no keyboard show is expected. This prevents agenda IO from competing with the fresh app load and keyboard show, while still allowing hardware-keyboard, IME-disabled, and keyboard-opt-out environments to proceed.
21. When home-ready fires, `LauncherViewModel.onHomeReady` sets `isHomeReady = true` and starts the deferred initial agenda load. The agenda load switches to `Dispatchers.IO`, checks calendar permission, queries `CalendarContract.Instances` if permitted, organizes events, and publishes only the newest agenda request.
22. The same home-ready signal releases `MainActivity`'s deferred `AppWidgetHost.startListening`. `onStart` skips widget-host listening while cold start is in progress; after home-ready, the host starts immediately if the activity is already started, and future `onStart` calls start it normally.

## Timing notes

The code has useful traces, but it does not contain enough device-independent information for a precise timing estimate. The reliable timing boundaries are:

- Home body and offscreen carousel pages are delayed by one display frame.
- The initial agenda load and widget-host listening wait for fresh app load; when keyboard auto-show is enabled, they also wait for IME visibility, with a 1500 ms fallback.
- Runtime telemetry measures the real durations with `launcher_cold_start`, `launcher_initial_load`, `installed_apps_load`, `icon_snapshot_restore`, `agenda_initial_load`, and per-miss `app_icon_load` traces.

Use those traces for actual timing. Static estimates would likely be misleading because `LauncherApps`, installed app count, icon cache state, storage speed, profile count, IME behavior, and device load all materially affect the result.
