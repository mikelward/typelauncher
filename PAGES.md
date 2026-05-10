# Carousel Page Identity — Refactor Options

The launcher's carousel state is identified two different ways at once:

- **Upstream** (`LauncherUiState`): `state.screen: LauncherScreen` plus `state.currentWidgetPage: Int`. Two fields that are only jointly meaningful when `screen == Widgets`. Off-Widgets, `currentWidgetPage` is stale data preserved so a return to Widgets lands on the last page.
- **Carousel** (`SwipeNavigationBox`): `currentPage: Int`, a virtual index near `Int.MAX_VALUE / 2` that maps via `floorMod(_, visiblePages.size)` to the visible carousel page list `[Home, Widgets[0], …, Widgets[N-1], Agenda?]`.

The two representations are bridged by `LauncherPage(screen, widgetPageIndex)`. PR #294 fixed a bug where stale `currentWidgetPage = 1` on Home made `LauncherPage(Home, 1) ≠ LauncherPage(Home, 0)` at the carousel claim check, dropping every horizontal swipe after Widgets[N>0] → tap app → Home button. The current fix patches four comparison sites with a `sameVisiblePage(a, b)` helper.

This doc captures three structural options for replacing that patch with something more durable.

## Option A — zero `currentWidgetPage` off-Widgets

Add `state.lastWidgetPage: Int` that survives screen changes. `state.currentWidgetPage` becomes a *live* value: it's the active widget page when on Widgets, and `0` everywhere else.

- `showHome()` and `showAgenda()` and `returnToLauncherHome()` set `currentWidgetPage = 0` (and snapshot the prior value into `lastWidgetPage` if the previous screen was Widgets).
- `showWidgets()` (no-arg) reads `lastWidgetPage` to pick the page.
- `showWidgets(pageIndex)` writes `currentWidgetPage = pageIndex` as today.

After this, every `LauncherPage(screen, currentWidgetPage)` constructed off-Widgets is `LauncherPage(_, 0)`, so `sameVisiblePage` is unnecessary — `==` is correct. Delete the helper, revert the four callsites to `==`.

**Touches:** `LauncherUiState`, `LauncherViewModel` (4 mutators), `LauncherDebugLog`, a handful of unit tests that hand-build state, `TypeLauncherApp.kt` (revert `sameVisiblePage` callsites + delete helper).

**Fixes:**
- The PR #294 bug class.
- Any future caller that constructs `LauncherPage(screen, state.currentWidgetPage)` for any purpose — they all see `0` off-Widgets, so equality is consistent everywhere it's used.
- Debug logs no longer show stale `currentWidgetPage=1` when `screen=Home`; the field reflects ground truth.

**Doesn't fix:**
- The data type `LauncherPage` is unchanged — anyone building a `LauncherPage(Home, 1)` by hand (e.g. in a test) still produces a value that's "wrong but valid." Convention only, not type-enforced.

**Pros:**
- Makes `state` self-consistent. The invariant "off-Widgets ⇒ index is 0" is enforced by the state mutators rather than per-comparison helpers.
- Smallest change that lets us delete `sameVisiblePage` with confidence.
- Restoration semantics ("return to Widgets lands on the last page") become explicit via `lastWidgetPage` instead of an implicit "the field happens to retain its prior value."
- Reversible — if we later move to B, this is a clean intermediate step.

**Cons:**
- Adds a field (`lastWidgetPage`). Two pieces of widget-page state to keep in sync via the mutators.
- Every screen-changing entry point has to remember to snapshot. Easy to miss on a new entry point — though the snapshot is a one-liner and is wrong-on-write rather than wrong-on-read, so failures show up loudly (the user lands on widget page 0 instead of the last page) instead of silently dropping gestures.
- Modest test churn — fixtures that explicitly set `currentWidgetPage` while on Home will need updating.

## Option B — sealed `LauncherDestination`

Replace `state.screen: LauncherScreen` and `state.currentWidgetPage: Int` with a single sealed type:

```kotlin
sealed class LauncherDestination {
    data object Home : LauncherDestination()
    data object Agenda : LauncherDestination()
    data class Widgets(val pageIndex: Int) : LauncherDestination()
}
```

Plus `state.lastWidgetPage: Int` (same role as in A) for restore.

The carousel's bridge type `LauncherPage` either disappears (carousel reads `LauncherDestination` directly) or stays as a thin layer that's now constructed only from `LauncherDestination`, so `LauncherPage(Home, 1)` is unrepresentable.

**Touches:** `LauncherUiState`, `LauncherViewModel` (every read of `state.screen` or `state.currentWidgetPage` — ~30+ sites), `LauncherDebugLog`, screen-rendering composables that switch on `state.screen`, snapshot tests, anywhere screen state is persisted or restored. Largest refactor of the three.

**Fixes:**
- Same as A, plus:
- The data type itself enforces the invariant. "Home with widget index 1" is a *type error*, not a convention. Future variant-specific fields (e.g. an Agenda date filter, a Widgets edit-mode flag) get the same protection for free.
- Removes the question "what does `widgetPageIndex` mean on Home?" entirely — it doesn't exist on Home.

**Doesn't fix:**
- Nothing structural. This is the strongest option.

**Pros:**
- Type-system guarantee, not a convention. The bug class is *closed*, not patched.
- Pattern matching at every read site (`when (destination) { Home -> …; Agenda -> …; is Widgets -> destination.pageIndex }`) is more idiomatic Kotlin and harder to forget than a flat `screen + index` pair.
- Sets up the codebase for adding more variant-specific state cleanly.

**Cons:**
- Largest blast radius. Touches Settings, the `@Preview`s in `Previews.kt`, every test that builds a fixture with `screen = LauncherScreen.Home`, persistence (if `LauncherUiState` is `@Parcelize`d or saved-state-backed), debug log formatting.
- Migration is mechanical but tedious — every `if (state.screen == Home)` becomes `if (state.destination == LauncherDestination.Home)`, every `state.copy(screen = …, currentWidgetPage = …)` becomes a destination-aware constructor.
- Higher risk of merge conflicts with in-flight branches.

## Option C — drop `LauncherPage` from the carousel-sync comparison

Leave `LauncherUiState` and `LauncherPage` as-is. Change only the carousel's "are we synced with upstream?" check, in `TypeLauncherApp.kt`. Compare via virtual page integers:

```kotlin
// Old (current, after PR #294):
sameVisiblePage(currentLauncherPage, candidateLauncherPage)

// C:
LauncherScreen.closestCarouselPage(
    currentPage = currentPage,
    page = currentLauncherPage,
    widgetPageCount,
    isAgendaEnabled,
) == currentPage
```

`closestCarouselPage` internally normalizes `LauncherPage(Home, 1)` to `LauncherPage(Home, 0)` via `visibleCarouselPage(visiblePages)`, so the answer is correct: "the carousel is already where upstream wants" ⇒ integer match. Apply at all four sites. Delete `sameVisiblePage`.

**Touches:** `TypeLauncherApp.kt` only. ~6 line net change.

**Fixes:**
- The PR #294 bug class *for the carousel*. The four comparison sites no longer flow through `LauncherPage`'s compound `==`.
- Removes the helper + the discipline of "remember to use `sameVisiblePage`, not `==`."
- Replaces equality-on-a-struct-that's-secretly-variant-dependent with a semantically direct integer comparison ("does upstream want me to be exactly at my current virtual page?").

**Doesn't fix:**
- `state.currentWidgetPage = 1` while `state.screen = Home` is *still possible* — the data is still inconsistent, just no longer poisons the carousel. Today's readers (`LauncherViewModel.kt:513` for restore, `1171/1768` for widget ops on the current page) all happen on-Widgets so they're correct in practice, but nothing prevents a future reader from breaking.
- `LauncherPage` itself still has the trap. Anyone constructing `LauncherPage(Home, 1)` for a non-carousel purpose has the same problem.
- Debug logs and persistence still emit the stale `currentWidgetPage=1` on Home.

**Pros:**
- Minimum blast radius. Single file. No state migration, no test fixture changes.
- The replacement comparison is *more direct* than equality of `LauncherPage` — "is my virtual page where upstream wants?" is exactly the question the claim check is asking.
- `closestCarouselPage` already has heavy test coverage from ExternalAnimating paths, so we inherit that confidence.
- Leaves the door open to A or B later — they're still strict improvements on top.

**Cons:**
- Doesn't add a new safety net — replaces one local fix (`sameVisiblePage`) with another. Cleanup, not hardening.
- Marginal cost per check: `closestCarouselPage` builds a 3–6 element `List<LauncherPage>` per call. Negligible at touch-event rates, but not literally free.
- Leaves the upstream inconsistency latent. If someone later adds a broken reader, we discover it as a new bug rather than being structurally protected.
- The pattern `closestCarouselPage(p, x) == p` is slightly indirect — needs a one-line comment explaining "this equation means upstream wants us to be exactly here, i.e. synced."

## Comparison

| | A: zero off-Widgets | B: sealed destination | C: virtual-page compare |
|---|---|---|---|
| Bug class closed at data-model level | Convention (mutators) | Type-enforced | No |
| `sameVisiblePage` deletable | Yes | Yes | Yes |
| `LauncherPage` data class still trap-prone | Yes (off-Widgets value can still be hand-built) | No (unrepresentable) | Yes |
| Upstream `currentWidgetPage` always meaningful | Yes | Yes (no such field) | No |
| Files touched | UiState, ViewModel, DebugLog, tests, TypeLauncherApp | UiState, ViewModel, every screen reader (~30+), tests, persistence, Previews | TypeLauncherApp only |
| Lines of net change | ~50 | ~300+ | ~6 |
| Safety floor moved | Yes | Yes (most) | No |
| Reversibility | Easy | Hard once landed | Trivial |

## Recommendation

**A** is the best balance for "make the gestures and navigation more predictable and less buggy." It's the smallest change that actually closes the bug class at the data-model level (via mutator-enforced convention), deletes `sameVisiblePage` cleanly, and is a natural stepping-stone to B if we later want type-system enforcement.

**B** is the right answer if we expect more variant-specific fields (per-screen edit mode, per-screen filter state, etc.) — pay the migration cost once, get the protection forever.

**C** is right when the priority is "minimum change to delete `sameVisiblePage` cleanly" without committing to upstream state-model work. Pure cleanup, no new safety floor — choose it knowing it's an aesthetic improvement, not a structural one.
