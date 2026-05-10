# Screens, pages, and the carousel

The launcher's home surface is a horizontally-paged carousel that cycles through a small ordered list of pages: a Home page, zero or more widget pages, and (optionally) an Agenda page. This doc covers the data model that backs that surface and the runtime mechanics that turn touch events into page changes.

## Three layers, kept distinct on purpose

There are three things that look related but mean different things, and the code keeps them separate:

1. **`LauncherScreen`** (`model/LauncherScreen.kt`) — an enum: `Home`, `Widgets`, `Agenda`. The *kind* of a page. Used by the page-math helpers (`fromCarouselPage`, `closestCarouselPage`, `visibleCarouselPages`) and as the `screen` field of `LauncherPage`. It does not appear in `LauncherUiState`.

2. **`LauncherPage`** (`model/LauncherScreen.kt`) — `data class LauncherPage(val screen: LauncherScreen, val widgetPageIndex: Int = 0)`. The bridge type used inside the carousel's page-math: it answers "what page does this carousel slot show?" by carrying the screen kind plus, when on Widgets, which widget page. The widget index is meaningful only when `screen == Widgets`; the rest of the time it's defaulted to `0`.

3. **`LauncherDestination`** (`model/LauncherScreen.kt`) — sealed: `Home` / `Agenda` / `Widgets(pageIndex: Int = 0)`. The user-facing destination, and the **source of truth in state**. The sealed shape makes "Home with widget index 1" unrepresentable, so a leftover widget index can't poison equality.

The relationship: `LauncherDestination` is what state stores and what callers reason about. `LauncherPage` is a flat projection used by the carousel's index math (and by `LauncherDestination.toLauncherPage()`). `LauncherScreen` is the projection of either onto its kind. Everything that matters at the boundary — the carousel's "are we synced?" check, screen-kind branching in the ViewModel, log lines — reads `state.destination`.

## State

```kotlin
@Immutable
internal data class LauncherUiState(
    val destination: LauncherDestination = LauncherDestination.Home,
    val lastWidgetPage: Int = 0,
    // …other unrelated fields…
)
```

Two page-state fields:

- **`destination`** — the user's current location. Pattern-match on it (`when (val d = state.destination) { is Widgets -> d.pageIndex; … }`) when you need the kind or the widget page. Use `state.destination is LauncherDestination.Home` for "is the user on Home right now?" — `Home` and `Agenda` are `data object`s and `Widgets` is a `data class`, so equality and `is` checks both work and stay stable across recompositions.

- **`lastWidgetPage`** — the page index to restore when the user returns to Widgets via `showWidgets()` with no argument. Survives screen changes; updated whenever the destination becomes `Widgets(N)` (via `showWidgets(N)`, `showWidgetPicker`, `addWidget`, `removeWidget`'s clamp). `LauncherViewModel.showWidgets()` (no-arg) reads this to decide where to land.

There is no `state.screen` and no `state.currentWidgetPage` — the projections deleted in PR #297 forced every reader to be explicit about whether they want the sealed-shape check, the live widget page (`(destination as? Widgets)?.pageIndex`), or the remembered restore page (`lastWidgetPage`).

## How the carousel turns indices into pages

The carousel ( `SwipeNavigationBox` in `ui/TypeLauncherApp.kt`) is built around an ordered list of visible pages and a virtual integer index that walks it cyclically:

- **`visibleCarouselPages(widgetPageCount, isAgendaEnabled): List<LauncherPage>`** returns the live ordering, e.g. `[Home, Widgets[0], Widgets[1], Agenda]` (or `[Home, Widgets[0]]` if Agenda is disabled and there's one widget page). Order is hardcoded today.

- **`currentPage: Int`** is the carousel's local position — a virtual index near `Int.MAX_VALUE / 2`. The starting offset (`firstVirtualCarouselPage`) is a trick: by parking far from `0` and `Int.MAX_VALUE`, both forward and backward swipes can advance `currentPage` by ±1 without ever hitting a clamp, so the user can drag in either direction indefinitely. `Math.floorMod(currentPage, visiblePages.size)` collapses that virtual index back to a position in the visible list.

- **Conversions**: `fromCarouselPage(currentPage, …)` projects the virtual index to a `LauncherPage` via the modulo above. `closestCarouselPage(currentPage, target, …)` goes the other way: it finds the nearest virtual index whose modular position matches the target's slot in `visiblePages`, taking the smaller of the forward or backward delta.

The carousel renders three slots — `currentPage - 1`, `currentPage`, `currentPage + 1` — translated by `carouselOffsetPx` to follow the user's finger. During a settle, only the slot the user is leaving and the slot they're moving to actually change content; the third sits offstage.

## Gesture lifecycle and the transition state machine

A horizontal gesture goes through a small state machine in `SwipeNavigationBox` so that "user dragged" and "upstream told us to be on Widgets" don't fight:

```
Idle ──user swipe (commits)──▶ UserAnimating ──animation done──▶ AwaitingAck ──state.destination == expected──▶ Idle
                                                                       │
                                                                       └──1.5 s timeout──▶ Idle (allowSwipeWithUnackedScreen)
Idle ──state.destination changes externally──▶ ExternalAnimating ──animation done──▶ Idle
```

- **`Idle`**: the carousel and upstream agree on the page. New gestures can claim.
- **`UserAnimating(targetPage, targetLauncherPage)`**: the user committed a swipe; we're animating `carouselOffsetPx` to land on the next slot. While here, gestures are ignored.
- **`AwaitingAck(settledPage, expectedPage)`**: animation finished, `currentPage` advanced, and we've dispatched `onShowAgenda` / `onShowWidgets(N)` / `onShowHome` upstream. We wait for `state.destination` to come back matching `expectedPage` before going `Idle`. If 1.5 s elapses with no ack, we flip `allowSwipeWithUnackedScreen = true` and force `Idle` so the user isn't stuck — the next swipe will work even though state is technically out of sync.
- **`ExternalAnimating(targetPage)`**: `state.destination` changed without a gesture (e.g. `MainActivity.onNewIntent` calls `returnToLauncherHome` after Home button). We animate the carousel to catch up, then go straight to `Idle` — no ack is needed because upstream already moved.

The two animating states look almost identical but only `UserAnimating` goes through `AwaitingAck`. That asymmetry is the one bit of cleverness: when the user drives the change, we wait for state to confirm; when state drives the change, we just chase it.

### Settle queue

If the user swipes again while one of the animating states is in flight, the second swipe is queued (`QueuedSettleSwipe(direction, settleTargetPage)`) rather than dropped. When the in-flight settle reaches `Idle`, the queue replays as a single one-page step from the settled position, so a fast double-swipe goes Home → Widgets → Agenda instead of stalling at Widgets. The queue is dropped if the in-flight target retargets mid-flight (e.g. another external state change races in).

## How a screen change actually flows

Putting the layers together, a swipe Home → Widgets[0] looks like this:

1. **Pointer events** reach the carousel's `pointerInput` (`Final` pass, after children get a chance). The gesture is classified as `HorizontalLauncher` once it crosses touch slop without a child consuming horizontal scroll. The claim check then asks: is the carousel `Idle`, no animation in flight, `carouselOffsetPx == 0`, and `currentLauncherPage == candidateLauncherPage`? If so, claim.
2. **Drag**: each subsequent event updates `carouselOffsetPx` and the slots translate.
3. **Release**: if `effectiveDragX` exceeds the commit distance or the fling-velocity threshold, commit. Set `UserAnimating(currentPage + 1, targetLauncherPage)`, hide the keyboard for the new screen, and animate the offset to one full page width.
4. **Animation completes**: bump `currentPage += 1`, reset `carouselOffsetPx`, transition to `AwaitingAck(currentPage, targetLauncherPage)`. Dispatch `onShowWidgets(0)` upstream, which calls `LauncherViewModel.showWidgets(0)`, which writes `state.destination = LauncherDestination.Widgets(0)` (and `lastWidgetPage = 0`).
5. **Ack arrives**: `LaunchedEffect` re-runs because `state.destination` changed; sees `statePage == transition.expectedPage`, sets `allowSwipeWithUnackedScreen = false`, transitions to `Idle`, and replays any queued swipe.

The corresponding convert-and-compare conversation between the layers:

- The carousel's `currentLauncherPage` comes from `state.destination.toLauncherPage()` — for Home it's `LauncherPage(Home, 0)` regardless of `lastWidgetPage`.
- The carousel's `candidateLauncherPage` (what the carousel thinks it's showing right now) comes from `LauncherScreen.fromCarouselPage(currentPage, widgetPageCount, isAgendaEnabled)`.
- `currentLauncherPage == candidateLauncherPage` is the synced check. Plain `==` is correct because `destination.toLauncherPage()` produces a canonical `LauncherPage(Home, 0)` for every Home destination — there's no off-Widgets stale index that could make two synced states look different.

## Why the model looks this way

Two specific traps shaped the design:

- **The widget-index trap (closed by the sealed type).** Before PR #294/#296, state held `screen: LauncherScreen` and `currentWidgetPage: Int` as separate fields, and `currentWidgetPage` was preserved across screen changes so `showWidgets()` could restore the user's last widget page. That made `LauncherPage(Home, 1)` representable, and the carousel's claim check (which compared `LauncherPage` values) silently dropped every horizontal swipe after Widgets[1] → tap app → Home. `LauncherDestination` makes the bad combination unrepresentable, and `lastWidgetPage` lives separately as the explicit restore-memory field.

- **Position vs. identity.** State stores the destination's *identity* (Home/Agenda/Widgets-N), not its *position* in `visibleCarouselPages`. If the visible list ever became reorderable (e.g. agenda between two widget pages), the identity stays stable while the position would shift. `closestCarouselPage` and `fromCarouselPage` already do identity↔index conversion at the boundary; the carousel's animation stays purely index-based.

Things still phrased as positions on purpose: the carousel's `currentPage` (a virtual index), `lastWidgetPage` (an index into the widget pages because widget pages aren't separately identified yet), and the `pageIndex` inside `Widgets(pageIndex)`. These are positions that name themselves as such; they're not pretending to be identities.
