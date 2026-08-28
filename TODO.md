# TODO

- Decide whether empty-query Enter/Search should continue opening Type Launcher settings or do something else.
- Revisit cached Home keyboard geometry after adding a permanent bottom reservation for the recents bar. Today `keyboard_reservation_bottom_px` keeps Home in typing-height geometry even after the user dismisses the IME with Back; that is intentional for the current hot path, but it should become redundant once the app list and dock reserve stable bottom space independent of keyboard visibility.
- Decide the secondary-tray behavior when `Show keyboard automatically` is disabled. The current tray is coupled to cached keyboard geometry from the type-first path; keyboard-opt-out users may need a stable bottom reservation that is not derived from IME auto-show.
- Design keyboard access for launching docked apps.
- Consider work-profile surfacing that is only visible when a managed work profile is provisioned and currently active (i.e. not paused / quiet-mode). Two shapes worth weighing:
  - **A separate work dock.** Sits alongside the personal dock — open questions: replace the personal dock, render as a secondary row, or expose via a swipe target?
  - **A whole separate page for work-profile stuff.** A dedicated page (sibling to Home / Widgets in the carousel) that hosts the work app list, work dock, and any work-only widgets, so personal and work surfaces never mix on a single screen. Likely the cleaner mental model if Android's quiet-mode toggle is meant to make the entire work surface disappear at once.
  - Cross-cutting open questions for either shape: how to enumerate work-profile apps via `LauncherApps` for the secondary `UserHandle`, how to react to `ACTION_MANAGED_PROFILE_AVAILABLE` / `ACTION_MANAGED_PROFILE_UNAVAILABLE` / `ACTION_MANAGED_PROFILE_ADDED` / `ACTION_MANAGED_PROFILE_REMOVED` so the surface appears and disappears without a relaunch, whether dock slot and rename / icon-override persistence should be per-profile, and how the work badge interacts with the existing regional disambiguator badge.
- Try to recover the 1 dp the dock formula gives back to pixel-rounding slack. PR #281 shipped `DOCK_PIXEL_ROUNDING_SLACK_PER_SLOT_DP = 1` so the `FlowRow` would actually fit `dockIconCount` items per row at 411dp/420dpi (six slots dropped icons from 43 → 42 dp). The slack is a workaround for `Modifier.padding(4.dp) + AppIcon(iconSize.dp)` rounding three `Dp` values independently in `Density.roundToPx`; rounding `(iconSize+8).dp` *once* per item undershoots the dp logical width, which is what the apps-list `LazyVerticalGrid.Adaptive` relies on. Worth trying:
  - **Render dock items at a single fixed cell width.** Replace the per-item `padding(4.dp)` with `Modifier.size((iconSize + DOCK_ITEM_HORIZONTAL_PADDING_DP).dp)` so each cell is one `roundToPx` instead of three. Should let the formula drop the slack at densities ≥ 2.0; verify density 1.5 (240 dpi) still fits — earlier scratch math showed it overruns by 1 px on a 411 dp / 6 slot row even with the size approach, so the slack may still need to apply at low densities.
  - **Distribute the row's actual pixel width across N cells the way `Adaptive` does.** A custom `Layout` (or a `Row` with `weight(1f)` per cell, modulo wrapping) would give each cell `(rowPx - spacingPx*(N-1)) / N` and centre the AppIcon inside it. That removes the rounding accumulation entirely at any density — at the cost of dropping the FlowRow primitive and re-implementing the wrap and drag-reorder slot-centre tracking against the new layout.
  - If neither path reclaims the dp, leave the slack in place — the visual delta (43 → 42 dp on six slots) is invisible and the trade is correctness for 1 dp.

- Revisit two carousel-gesture hardening items if either becomes user-visible. Both currently sit at "theoretical bug, no real trigger today, defensive fix introduces complexity worse than the symptom." Revisit if telemetry / bug reports show the trigger actually firing, or if a future code path (async widget reload, programmatic agenda toggle, dispatch path that returns early) makes either reachable.

  1. **Mid-gesture `widgetPageCount` / `isAgendaEnabled` change cancels the swipe.** The horizontal `pointerInput` in `SwipeNavigationBox` is keyed on both values. Compose tears down the `awaitEachGesture` coroutine when any key changes, so a recomposition with a new value mid-swipe drops the gesture. Today the trigger isn't reachable: Settings is a separate screen (no overlap with swipes), widget add/remove fires from the long-press menu (the user has to release before tapping). Defensive fix shape: capture both values via `rememberUpdatedState` and drop them from the keys list. **Caveat (PR #298 first revision found this):** the defensive fix on its own is worse — the gesture survives the recomposition but `claimGestureStartPage` was captured in the old `visibleCarouselPages` modulo space, while the post-change config has a different `visiblePages` size, so `targetPage = claimGestureStartPage + dragDirection` translated through the new config can land on the wrong `LauncherPage`. To do this safely also requires re-anchoring `claimGestureStartPage` (via `LauncherScreen.reanchoredCarouselPage`) at release if the snapshot config differs from the live config.

  2. **`allowSwipeWithUnackedScreen` permissive flag on ack timeout.** When upstream never acks a swipe within `CAROUSEL_ACK_TIMEOUT_MS` (1.5 s), the carousel re-dispatches the screen change, sets `allowSwipeWithUnackedScreen = true`, and forces `Idle`. The flag lets the user keep swiping even though `currentLauncherPage != candidateLauncherPage` — carousel and `state.destination` stay in disagreement, and any UI driven by `state.destination` (keyboard auto-show, secondary trays, etc.) behaves inconsistently with what's visually on screen. Bugs in the dispatch path become invisible to the user.

     An attempt to fix this (PR #298 first revision) replaced the flag with an active resync — `currentPage = closestCarouselPage(currentPage, currentLauncherPage, …)` on timeout — so carousel and state always agree once `Idle`. That worked for hygiene but produced a worse UX: a 1.5 s pause followed by a visible snap-back undoes the user's expressed intent, and against a permanently-broken dispatch path the user can never reach their target page (every swipe times out, snaps back). The trade-off favours diagnostic clarity over the user, which is the wrong default.

     **Options worth weighing:**

     1. **Re-dispatch + keep the flag.** Closest to current behaviour. The re-dispatch is the user-helpful action (try again in case of a transient miss); the flag is the safety valve when re-dispatch also fails. Hygiene cost: state and carousel stay in disagreement under permanent failure. Recommended baseline if no one reports the disagreement biting other UI.
     2. **Re-dispatch + N retries before resync.** Try the dispatch a few times (each on a short timer, e.g. 500 ms) before giving up and resyncing. Preserves the user's intent through transient races, surfaces the bug on permanent failure. More state machine surface; need to bound retries so we don't loop forever.
     3. **Re-dispatch + show a one-shot "navigation didn't take" indicator instead of silently snapping back.** Resyncs hygiene but tells the user explicitly rather than just yanking the carousel back. Adds UI surface (toast / snackbar / inline indicator).
     4. **Treat ack failure as "unreachable destination" and let the user navigate around it.** If `showAgenda()` returns early because agenda is disabled, the right answer is probably to remove Agenda from `visibleCarouselPages` so the swipe can't target it in the first place — not to time out after the fact. Audit the dispatch entry points (`showHome`, `showWidgets(N)`, `showAgenda`) for "returns early" conditions and gate them at the page-list level instead.

     The right answer probably combines (4) for known-unreachable cases with (1) or (2) as the safety net for genuinely unexpected races.

## CI

- **`screenshot_appListIconOnly_overflowingGrid` fails intermittently in the
  screenshot job**, and only there: `assertIsDisplayed()` on the apps-list
  bottom chevron throws `IllegalArgumentException: performMeasureAndLayout
  called during measure layout` from Compose's `MeasureAndLayoutDelegate`. Seen
  once on PR #669 (head `fe0bea50`) and not on the head immediately before it,
  nor on any local run of the same class under `-Proborazzi.test.record=true`.
  The assertion re-enters measurement while a pass is already running, so it is
  an ordering bug in the test rather than anything about the chevron itself —
  the fix is to make the ordering explicit, not to retry or sleep. Left alone
  for now because it reproduces nowhere a fix could be verified; if it recurs,
  that is the signal to stop treating it as noise.

- **Reconcile the docs-lane classifier with the release-notes-skip classifier
  for `PRIVACY.md`, and reconsider forcing it onto the code lane at all.**
  `.github/lanes.conf`'s `code PRIVACY.md` rule means a PRIVACY.md-only
  change always runs the full build/test/lint pipeline, even a pure wording
  fix that changes nothing about the app — but PRIVACY.md isn't actually
  code, so that's a heavier CI cost than the change needs; being release-
  worthy and needing heavy CI are two separate questions the lane rule
  currently conflates. Separately, the deploy job's release-notes generator
  has two *independent* skip conditions — a non-user-facing subject prefix
  skips a commit regardless of what it touched, and a housekeeping-path
  check (which PRIVACY.md is carved out of) skips a commit whose diff is
  all `.md`/dotfiles. A commit like `docs: clarify data retention wording`
  touching only PRIVACY.md would still be dropped by the prefix check, even
  though the lane rule's whole point is that PRIVACY.md is never "just
  docs." The two classifiers can silently diverge on this one case.
- **Require a PRIVACY.md update in the same commit as the practice change it
  documents** (mirroring how the sibling repos keep SPEC.md in sync), and
  stop treating every PRIVACY.md touch as automatically release-worthy — a
  pure wording/typo fix with no actual change in practice shouldn't force a
  release the way a genuine new disclosure should. Needs a real distinction
  between "the policy text changed" and "what the policy describes
  changed," which the current mechanism can't make on its own.
- Add an AGPL license gate to `ci.yml`: fail if a dependency declares
  an AGPL license, catching one added by hand in a normal PR, not just ones
  the weekly bot bumps. Likely the `com.github.jk1.dependency-license-report`
  Gradle plugin. GPL/LGPL undecided. Independent of `gradle-update`, and
  independent of the AboutLibraries export behind the Licenses page — that one
  reports what is bundled, it does not gate on what a license says. Work out
  placement, gating, and coverage (release vs. debug classpath, etc.) when
  actually building this.
- **Reconcile `SPEC.md` and `docs/play-store-internal-track.md` with the release
  keystore now being required** (Codex, PR #674; deferred there deliberately, at
  the maintainer's instruction). Two records still describe the old behavior:
  - `SPEC.md` says the AAB build "is gated only on `RELEASE_KEYSTORE_BASE64`
    being present", and that the build job's release APK is always produced. On
    a non-fork push to `main` the keystore is now required rather than a gate —
    `deploy`'s `Require a release keystore` step fails the run without it — and
    the build job skips its release APK for any non-fork `main` push.
  - `docs/play-store-internal-track.md` still promises fresh clones without
    secrets get a green run. That now holds for **forks** only.

  Left for the maintainer because the same document also states the release
  secrets are environment-scoped, which does not describe their current state
  (they are repository-scoped, with the move planned), so rewriting the setup
  contract means asserting facts about infrastructure that need checking rather
  than inferring. The CI behavior itself does not depend on the answer: the skip
  gates on fork status, not on the secret, so it holds under either scope.

- **Scale the retained icon count by device RAM tier.** The background trim added
  2026-08-27 keeps the same set — dock plus the top 50 apps — whether the phone
  has 4GB or 16GB. That is backwards relative to how the threshold works: Play
  grades its memory and bitmap-memory thresholds **by device RAM tier**, so the
  cheapest device, where the limit is tightest, currently gets exactly the same
  footprint as a flagship.

  Scaling the count off `ActivityManager.getMemoryClass()` (or `isLowRamDevice`
  as the coarse signal) would put the app clearly under where it is tight and
  keep full warmth where there is headroom, at no cost on a high-tier device.

  Two things to settle when doing it. What the low-tier floor should be — one
  screenful is the obvious candidate, but a 42-icon screen is 4.5MB at xxhdpi
  and 8.0MB at xxxhdpi, and a cheap phone can still be high-density, so the
  floor wants deriving from icon size rather than fixing as a count. And whether
  to scale the **foreground** budget too: the 24MB cache is also unscaled, and
  on a 4GB device it is a larger share of what the app is allowed. That is the
  riskier half — the foreground budget is what keeps scrolling free of
  re-rasterization — so it wants a device to test on before being touched.

  `minSdk = 34` does not cover this. It raises the OS floor, not the RAM floor:
  entry-level phones ship with current Android, so a new Android 14/15 device
  with 4GB is in scope, and Android Go targets that segment specifically. The
  low RAM tier is exactly where the threshold is tightest, and minSdk excludes
  none of it.

  Nothing blocks doing this — `getMemoryClass()` and `isLowRamDevice` are
  ordinary runtime reads. What is blocked is *verifying* it: Play vitals has no
  data for this app and no handset is available, so the effect can be reasoned
  about but not measured.

  Worth knowing before sizing any of this: launchers likely fall under Play's
  **Personalization** category, alongside wallpaper and theme apps, which hold
  far less resident state and have no reason to stay warm. If thresholds are set
  against that comparison set, a launcher's usage shape is unfavorable and the
  margin matters more, not less. Unverified — Play vitals reports "not enough
  data" for this app, so every figure here is arithmetic rather than
  measurement, and the first real signal is vitals once there are enough
  installs to report.

- **Bring the icon-cache bullet in `SPEC.md` back up to spec altitude.** Codex
  flagged it on PR #678 and the flag is fair: that one bullet carries concrete
  class names, a byte budget, cache-key shape, dispatcher names, and the
  in-flight coalescing mechanism — all facts that live in the code and change
  with it, which is exactly what the "product and architecture decisions, not
  low-level implementation detail" rule excludes.

  Not done in #678 deliberately: the offending text is almost entirely
  pre-existing, so rewriting the paragraph around a memory fix would turn that
  PR into a spec refactor and land the rewrite unreviewed as a side effect of
  something unrelated. Worth its own change, where the diff is the point.

  The test to apply, bullet by bullet: would this still be true and worth
  stating if the implementation were rewritten? Keep the decisions — lazy icon
  loading with a placeholder, per-size rasterization, one budget rather than an
  entry count, background trimming and what it keeps, the trade a dropped icon
  costs. Drop the rest into the code, where it already lives.

- **Decide whether the icon warm-up should know about an in-progress drag.**
  Raised by Codex on #683 and correct on the mechanism, declined there pending a
  call from the maintainer. The warm-up's 150 ms trailing debounce measures quiet
  between *reorder events*, not gesture completion: `handleDockDrag` calls
  `onReorder` only when the dragged icon crosses a slot, so a user who holds an
  icon still mid-drag lets the timer expire and a sweep starts while the gesture
  is live.

  What that actually costs is small. The next slot crossing cancels the sweep
  immediately (`scheduleIconWarmUp` cancels `iconWarmUpJob`), and the drop itself
  schedules the real one, so the residue is the handful of icon loads already
  handed to `AppIconLoader` — which finish in its own scope regardless — running on
  IO and Default while the drag resumes on Main. Not the whole sweep racing the
  gesture; that part is closed.

  What closing it would cost is the open question, and it is why this is not a
  judgment to make unilaterally. There is no drag state in the view model today, so
  it means the UI pushing drag start/end for every drag surface (the dock, an opened
  folder, and anything added later) — with a new failure mode where a missed drag-end
  suppresses warming for the rest of the session. There is precedent both ways:
  `setDockSuppressedByKeyboard` and `onRenderedIconSizes` are already UI-pushed
  state, but the standing rule from #679 is that questions whose answer lives in the
  composable go stale anywhere else, and five review rounds were lost to exactly that.

  Cheaper alternatives if it is worth closing at all: raise the debounce (trades
  responsiveness on every other trigger for this one case), or have the sweep check
  a drag flag only at its yield points rather than gating the schedule.

- **Reprioritize the warm-up when the rendered order changes.** Changing "Sort apps
  by", or rotating into or out of the Compact landscape tier, changes which apps head
  the warm-up plan without changing the set. The `refreshLists` funnel (landed
  2026-08-28) now fires for the sort-order case, but firing is not enough: on a device
  already at the 75% ceiling the re-sweep exits on its first iteration, because the
  ceiling is checked before any load -- the cache is full of the *old* head and the new
  one never warms. The tier case does not even fire, since `setHomeLandscapeTier` goes
  through `refreshFilteredApps` (the keystroke path, deliberately not hooked) rather
  than `refreshLists`.

  The list still paints either way -- the visible rows miss and load on demand, as
  every row did before any warm-up existed -- so this is a lost optimization, not a
  stuck first page. Closing it means making room for the new head, which is the same
  reservation the item below needs. Do the two together.

- **Reserve the first screenful of the app list against live eviction.** The 75%
  ceiling bounds the *warm-up*, not rendering: the UI fills the cache to 100% and
  the LRU evicts freely. And `rememberAppIconResolution` reads the cache only in
  its `remember` initializer, so an icon's recency is set once when its row
  composes and never refreshed while it stays on screen — which makes the rows the
  user has been looking at longest the *first* the LRU reclaims.

  So on a large app set — roughly 425 apps at xxhdpi, 245 at xxxhdpi before a
  40dp list icon's ~58KB/~102KB fills 24MB — scrolling to the bottom of the list
  can evict the top of it, and scrolling back up pays one async load per row. The
  dock is unaffected: it never leaves composition, so Compose holds its bitmaps
  whatever the LRU does.

  Closing it means pinning (or reserving budget for) the rows at the head of the
  current sort order. Two things to settle. How many rows is "a screenful" — that
  is layout knowledge the view model does not have and must not guess at, since
  every wrong guess about what the UI draws cost a review round in #679; the UI
  would have to report it the way it already reports rendered sizes. And whether
  a reservation is a pinned set or a second, smaller `LruCache` — a pinned set
  cannot be evicted under pressure, which is the point and also the risk.

  Pairs with the reorder half of the item above: both need the same reservation.

- **Give the icon cache a disk-backed miss path, so a trimmed icon comes back
  cheaply.** The background trim added 2026-08-27 drops everything outside the
  priority set when the launcher goes off screen. Coming back, each dropped icon
  is re-resolved through `LauncherApps` and re-rasterized from scratch, with its
  row painting the placeholder until that lands — the trim's real cost, and the
  reason the retained set cannot simply be made small.

  `IconSnapshotStore` already writes pre-rasterized tiles as raw pixel buffers,
  and a read of one is a plain file read with no decoder cost — far cheaper than
  a resolve plus a rasterize. But it does not help here, for two reasons. It is
  read exactly once, in `LauncherViewModel`'s init block, so nothing consults it
  after startup. And it persists only the priority set — precisely the ids the
  trim *keeps* — so even a lookup would miss on every icon the trim dropped.

  Two changes, and they are separable. Have `AppIconLoader` consult the snapshot
  on a miss before falling back to a resolve, which alone helps any icon still on
  disk. And widen what is persisted beyond the priority set, so the trimmed icons
  are actually there to be found — that one trades disk for warmth and needs a
  cap, since the whole app list at two sizes is not a small directory.

  Sizing matters more than it looks: at 4 bytes/px a 56dp tile is 110KB at xxhdpi
  and 196KB at xxxhdpi, so persisting a couple of hundred at two sizes is tens of
  megabytes of storage to save tens of milliseconds of resolve. Worth measuring
  what a resolve-plus-rasterize actually costs on a handset before assuming the
  trade is good.
- **Derive the icon-cache priority set from what is actually on the home screen,
  rather than from launch counts.** The background trim added 2026-08-27 keeps
  the dock (folder members included) plus the top 50 apps by launch count, which
  is a proxy: it is the set `IconSnapshotStore` already persists, so reusing it
  meant one definition instead of two drifting apart. The ideal is the real
  thing — infer the visible set from the layout settings (grid size, rows and
  columns, current sort order) so the retained icons are exactly the ones the
  next foreground frame paints, no more and no fewer. Maintainer's call
  (2026-08-27): top-N plus the dock is good enough until that inference exists.

  Two things to weigh when doing it. Launch count and screen position disagree
  for an app that is displayed but rarely opened; the count-based set misses it,
  and it is on screen. And `priorityIconCacheIds()` filters `launchCount > 0`,
  which is only harmless because newly installed apps do not bubble to the top
  of the list — if that ever changes, a new app on the home screen becomes the
  one icon that reloads.

  Numbers to size it against, measured 2026-08-27 at ARGB_8888, 4 bytes/px:
  a 56dp icon is 110KB at xxhdpi and 196KB at xxxhdpi, so a 42-icon home screen
  is 4.5MB / 8.0MB respectively, against a 24MB cache budget. An app rendered
  at two sizes (docked and in the list) holds two entries.

- **Cover the release-keystore configuration guard with an automated test**
  (Codex, PR #674; applies to all four repos, which now share the guard). The
  all-or-none check and the signing-config attachment are build-script logic
  with no test behind them: the only evidence they work is a Gradle invocation
  run by hand. That gap is not theoretical — review caught a real defect in the
  first version, where the guard normalized blank to absent but the build type
  tested the raw string, so a whitespace-only `RELEASE_KEYSTORE_FILE` slipped
  past the guard and then attached an empty signing config. A test over the
  none / whitespace / partial / complete cases would have caught it. Not done
  here because nothing in any of the four repos can test build logic today —
  no `buildSrc`, no `build-logic`, no Gradle TestKit — so this is a new harness
  in four places, and the cheaper alternative (a CI step asserting the guard
  fires) adds a second Gradle invocation to every pull-request run, which cuts
  against what #674 was for. Worth doing the first time any of these repos
  grows a `buildSrc` for another reason.
- Stop searching for the screenshot-diff comment altogether. The lookup now paginates (`gh api --paginate`), which is correct at any comment count but is still a search costing O(comments) API calls per run on a long PR. Stashing the comment id somewhere stable — a workflow-run output, a branch note, the check-run summary — would make the upsert a direct PATCH. Not urgent; this is about cost and simplicity, not correctness.

## Layout, caching, rendering, and recomposition follow-ups

- [ ] **The Settings scroll chevron can land between the consent card's two
      actions.** The chevron is a screen-level overlay pinned bottom-center;
      `TelemetryConsentCard` arranges **Don't allow** and **Allow** at opposite
      ends, so the empty middle is exactly where the chevron sits when the card
      is at the bottom of a scrollable viewport — and it then reads as a third
      consent choice. Visible in
      `compose_telemetry_consent_settings_placement_robolectric.png`.

      Left as-is deliberately: the card normally sits at the *top* of Settings
      with the chevron at the bottom of the screen, so it takes a short viewport
      or a large font scale to collide, and the buttons stay labeled either way.
      Worth fixing if it shows up on a real device — reserve bottom clearance in
      the row rather than abandoning the edge-to-edge arrangement, which is
      deliberate (a two-way choice with no default should not read as an action
      plus an escape hatch). Raised by Codex on #668.

- Split `LauncherUiState` consumption into smaller screen/subtree projections so typing, widget, and settings updates do not invalidate broad composition scopes. Candidate slices: theme, home/search/results, keyboard tray, carousel, widgets, and settings.
- Move query filtering and ranking off the main thread, or pre-index enough app search metadata to keep per-keystroke work cheap. Keep query text updates immediate, make result computation cancellable with `mapLatest`, and publish only the latest filtered list.
- Revisit offscreen carousel composition so non-current widget and agenda pages stay lightweight. Prefer composing the current page plus the active drag/animation target, and avoid creating hosted widget `AndroidView`s for pages that are only preloaded for swipe readiness.
- Cache the widget picker's app-icon and widget-icon bitmaps, keyed by drawable
  and requested size. The preview half of this is done — the fetch was already
  off the main thread and the rasterization now happens on the same IO hop. The
  icons still rasterize in composition, deliberately (see `Decisions needing
  review`), but `remember(appIcon)` only caches for as long as that row stays
  composed, and the picker emits rows positionally — so filtering re-rasterizes
  every visible icon on each keystroke. A cache keyed by the drawable would cost
  nothing at first paint and remove the repeat work.
- Use lazy or otherwise bounded rendering inside the widget picker list. The picker currently materializes matching app groups in a regular `Column`; flattening into the outer lazy list or using a bounded nested lazy list would scale better on devices with many widget providers.
- Decide whether to persist more than priority icons only after telemetry shows first-scroll icon misses are hurting startup or scroll performance. If needed, persist a bounded first screenful for the active empty-query sort order and icon size.
- Add lightweight debug-only recomposition/performance instrumentation around hot composables and interactions: search, app list rows/grid buttons, keyboard tray, widgets, first query keystroke, backspace, Home ↔ Widgets swipe, and widget-picker expansion.

## Dependency updates

- [ ] **Adopt `mikelward/gradle-update`** — the weekly Gradle catalog updater.
      Consumers get wired up one repo at a time; this batch was done by hand.
- [ ] **Drop `material-icons-extended` and vendor the 22 icons the app uses.**
      The library ships several thousand `ImageVector`s; `app/src/main`
      references 22. R8 strips the rest from the release APK, so the shipped
      size is unaffected — but the debug APK, which never minifies, measured
      **76.57 MiB (73.44 MiB of it dex)** against a 4.83 MiB release APK on
      2026-08-27. That is the APK `installDebug` puts on a phone.

      Copy **all 22**, rather than taking what `material-icons-core` happens to
      cover and hand-vectoring the remainder. One mechanism beats two: a split
      leaves no way to tell by looking whether a given icon came from the
      library or the local set, and the next icon someone adds silently pulls
      the dependency back in. Vendoring the lot makes adding a 23rd a
      deliberate act.

      The 22, as of `4c4d08b0`:

      ```
      AutoMirrored.Filled: ArrowBack, KeyboardArrowLeft, KeyboardArrowRight, Message
      Filled: Add, ArrowDropDown, Call, Clear, DragHandle, Email, EventBusy,
              ExpandLess, ExpandMore, KeyboardArrowDown, KeyboardArrowUp,
              MoreVert, Person, Search, Settings, Star, Warning, Widgets
      ```

      Re-derive the list before starting rather than trusting this one:
      `grep -rhoE "Icons\.(Filled|Outlined|Rounded|TwoTone|Sharp|Default|AutoMirrored)[A-Za-z.]*\.[A-Za-z]+" app/src/main --include="*.kt" | sort -u`

      Icons are visual, so this needs screenshot-test coverage to prove nothing
      shifted: several of the 22 already appear in recorded snapshots, and a
      vendored vector that differs by a pixel will show up there. Check the
      Roborazzi diff rather than assuming a copy is a copy.

      This is typelauncher-only: simmo (8 icons) and clothescast (15) already
      depend on `material-icons-core`, and snoozemo has no material-icons
      dependency at all. So there is no fleet-wide version of this change, and
      `-core` is the shape the siblings already settled on — which is an
      argument for checking how many of the 22 it covers before vendoring, even
      though vendoring all of them stays the more robust end state.

## Not planned

- **Showing work-profile calendar events on the agenda.** Investigated 2026-07: not feasible in practice, because access is gated by MDM admin policy that neither the user nor the app can grant. Findings, in case the policy landscape changes:
  - The agenda's `CalendarContract.Instances` query reads only the personal profile's calendar provider; the work profile runs an isolated provider instance under a separate Android user, and `READ_CALENDAR` does not cross that boundary.
  - Android 10+ has a purpose-built mechanism — `CalendarContract.Calendars/Events/Instances.ENTERPRISE_CONTENT_URI` — letting a personal-profile app query the work profile's calendar. Three gates must all be open: (1) the work profile's device policy controller must allowlist the calling package via `DevicePolicyManager.setCrossProfileCalendarPackages()` — the default is an **empty** allowlist, only the org's MDM admin can change it, and there is no user-side or app-side way to self-grant; (2) the user must enable the work profile's cross-profile calendar setting; (3) the personal-profile app needs `READ_CALENDAR` (we have it). Gate (1) is the showstopper: admins commonly allowlist Google Calendar, not arbitrary launchers, so shipping this would light up for approximately no one.
  - If it ever becomes worth doing, the shape is small: issue the same instances query a second time against `Instances.ENTERPRISE_CONTENT_URI` in `loadAgendaEvents` and merge before organizing (the search index inherits it via the same function); tag rows with an `isWorkEvent` flag so the tap path uses `CalendarContract.startViewCalendarEventInManagedProfile()` instead of the personal `ACTION_VIEW` intent (which cannot open a work event). Constraints: the cross-profile provider only permits an allowlisted projection (our current columns appear to be on it — verify against a real managed profile) and returns an empty cursor rather than throwing when access is disallowed or no work profile exists, so the query can run unconditionally. `minSdk = 34` means no API-level guard is needed.
  - The `INTERACT_ACROSS_PROFILES` / connected-apps route does not help — it grants no cross-profile content-provider access, and full cross-user provider queries need system-only permissions a launcher cannot hold.
  - **User workarounds (no code needed):** (1) add the work calendar app's widget to a widget page — the launcher already hosts work-profile widgets, so this works today and is the recommendation to give users; (2) share the work calendar with the personal account server-side so it syncs into the personal provider — but many orgs block sharing work calendars with non-work accounts, so this one often isn't available either.

## Privacy

- [ ] **Align the four app repos' debug loggers.** `ProcessExitReasons.kt` was
      ported from here into clothescast, Snoozemo and Simmo as a deliberate
      copy — same file name, function names, log-line format and field names —
      so the four logs read identically and a future unification is a
      lift-and-share rather than a reconciliation. The loggers underneath
      them are what differ, and the divergence is real. The other three repos
      each carry this same inventory; this is the copy for the repo the others
      treat as the reference.
      - **Type Launcher** (here) has the *default-safe type rule*
        (`LogValue`): a log call is a literal format string plus arguments,
        and an argument reaches the Crashlytics breadcrumb mirror only if its
        type cannot name anything of the user's, with `safe(...)` /
        `sensitive(...)` overriding per value. This is the strictest of the
        four and the one worth converging on.
      - **clothescast's `DiagLog`** takes a pre-built `String` and writes to
        disk only — no breadcrumb mirror — so redaction is whatever the call
        site remembered, and the port needed no wrappers.
      - **Snoozemo's `SnoozeDebugLog`** is also a pre-built `String`, an
        in-memory buffer plus a file sink with no off-device mirror, gated on
        a recording preference that is on by default.
      - **Simmo's `SimmoDebugLog`** redacts whole lines with `scrubPii` and
        *does* fan out to Crashlytics breadcrumbs on opted-in installs — the
        combination that made the port omit the exit `description` there and
        render timestamps in a spelled-month format, since `scrubPii` masks a
        raw epoch as a phone number.

      Unifying them is a bigger piece of work than any one port and was
      explicitly out of scope for the ports (maintainer, 2026-08-28: *"the
      loggers should be aligned, that's likely a bigger thing, but don't
      diverge them further"*). This entry exists so whoever picks it up starts
      from an inventory rather than rediscovering the differences. The floor
      stays per-repo regardless: uniformity must not loosen any repo's privacy
      rules.

- [x] **Hold Crashlytics off before it auto-starts.** Done. Both SDKs now
      default off via `firebase_crashlytics_collection_enabled` and
      `firebase_performance_collection_enabled` in the manifest, and only an
      explicit Allow turns them on. The consent gate is what forced it: with
      `PRIVACY.md` promising nothing is sent until the user says yes, an SDK
      that auto-initializes already collecting made that claim false. The
      runtime setters persist an override, so an install that has consented
      still collects from auto-initialization — the default governs only the
      un-consented case, which is the one that needed it.

      What this does *not* fix is the other half of the same root cause: a
      flag write that throws, or our own preference write that throws, still
      leaves the launcher's belief and the SDK's state disagreeing. The
      remaining step is to stop treating the SDK flag as the record at all —
      keep it derived from ours, and re-assert rather than assume. Codex found
      six instances of this across #662, #666 and #668; the manifest default
      closes the widest one.

- [ ] **Take over Firebase initialization, if a persisted opt-in ever exists.**
      Not currently reachable, and recorded so it is not rediscovered as a bug.
      A runtime `setCrashlyticsCollectionEnabled(true)` persists an override that
      beats the manifest default, and `FirebaseInitProvider` runs before
      `Application.onCreate` — so an install carrying such an override would have
      a window on each launch, provider start to the startup coroutine, in which
      a queued report could upload before the gate is applied.

      No shipped build has ever enabled collection, so no install carries that
      override, and the consent gate means none can acquire one without its user
      asking for it. If that changes — a build ships collecting, or the trial is
      reverted and later re-applied — this becomes live, and closing it means
      removing `FirebaseInitProvider` from the merged manifest
      (`tools:node="remove"`) and initializing Firebase ourselves after a
      synchronous read of the stored choice, which needs care against the
      cold-start budget.

- [ ] **A failed consent `commit()` leaves the in-memory preferences claiming
      consent.** `SharedPreferences.Editor.commit()` applies the edit to the
      process's live map *before* returning `false` for a disk write that never
      landed. `setTelemetryEnabled` returns early on that `false`, so nothing is
      gated and no transition is enqueued — but the in-memory map now reads
      `enabled/answered = true`, and if `TypeLauncherApp`'s startup transition is
      still pending it would read those values and enable Firebase's persisted
      flags. Disk then says unanswered while the SDKs auto-start enabled.

      Left as-is. It needs a failing disk write *and* the startup transition
      still pending — which, since it runs at `Application.onCreate`, means the
      user is tapping Allow in Settings within the first moments of a process
      start — *and* a process death after. Closing it means snapshotting both
      preference values before the write and restoring them on failure, which
      cannot be a blanket `false/false` (that would erase a previous decline),
      so it is state-snapshot machinery in the function this review reshaped
      repeatedly. Raised by Codex on #668; noted rather than fixed because the
      cost is real and the reachability is not.

- [ ] **Stop treating the SDK flags as the record of the user's choice.**
      The manifest defaults above close the auto-start hole, but not the rest
      of the same root cause: our record can fail to take, and something then
      re-reads state that contradicts what the user actually chose.

      Three shapes of it, all found by Codex and all real:
      - `applySdkFlags` logs a throw from either SDK and carries on, so a failed
        *disable* leaves collection on while the discharge runs, and the durable
        marker is then cleared anyway — no debt left for a later transition.
      - If the opt-out's own preference write *throws*, the stored choice stays
        `true` while only the in-memory state says otherwise; the queued
        transition re-reads the store, discharges, and re-enables collection in
        the same process, with the Settings switch still showing off.
      - More generally, a write that did not take cannot record that it did not
        take, so no ordering *inside* the transition repairs it.

      The fix is to make the launcher's own record authoritative and derive the
      SDK state from it — re-assert on every transition rather than assume the
      last write landed, and refuse to clear a debt unless the disable it
      depends on actually succeeded. That is knowable under this design and is
      not under today's.

### Decisions needing review

- **`stateNotNeeded` on the home activity is an open question, not a settled
  one.** `ManifestUnitTest.mainActivity_manifestHasLauncherFlagsAndCalendarPermission`
  asserts `android:stateNotNeeded` is **absent** from `MainActivity`, with a
  rationale in the test: the flag lets Android restart the launcher with a null
  saved-state bundle, which breaks every in-flight-result recovery carried
  through instance state — the icon picker's `rememberSaveable`
  `pendingIconPickAppId`, `ActivityResultRegistry`'s own pending-request
  record, and `MainActivity`'s `KEY_PENDING_WIDGET_ID` for the widget
  bind/configure flow.

  Reopened because the rationale weighs the flag as "crash-loop-on-restore
  protection", and that is narrower than what the attribute actually does. When
  a process dies the system removes any activity that had not yet saved its
  state, and an activity is stateless *precisely while it is resumed and
  visible* — which is what the launcher is when a batch of app updates kills
  it. So the case is not a rare crash loop; it is the ordinary path. Android's
  own documentation names the home screen as the example for the attribute
  ("the activity that displays the Home screen uses this setting to make sure
  that it doesn't get removed if it crashes for some reason"), and AOSP's
  Launcher3 sets it, which leaves Type Launcher as the only home app on the
  device without the guard.

  What it would cost: an icon-pick or widget-configure result that was in
  flight *at the moment the process died* is dropped. Rare, and the flows
  recover on the next attempt.

  What it might buy: the launcher still being there when the user presses Home
  after an update batch. The reported symptom is the phone falling back to the
  other launcher, escaped by opening Type Launcher by hand — with the home role
  never revoked, so no launcher-side state reflects it.

  Not yet established that this is the cause, and there appear to be **two
  distinct failures** here, not one:

  **A — silent fallback.** After a batch of app updates, a Home press goes
  straight to the other launcher with no prompt, and stays that way until Type
  Launcher is opened by hand from that launcher. The home role is still held
  throughout. Seen repeatedly, roughly weekly.

  **B — the resolver sheet.** Android's "which launcher app would you like to
  use" sheet appears. Left unanswered, so nothing was revoked; opening Type
  Launcher afterwards confirms it still holds the role. Seen once, separately
  from A.

  Both are consistent with the home activity not being resolvable at the moment
  Home was pressed, differing in whether the system fell back silently or
  asked — but A *persisting* until a manual launch is what the momentary
  install window does not explain, and is the part the saved-state flag would.
  The observation that argues against the flag explaining everything: the
  "which launcher app would you like to use" resolver sheet, left it
  unanswered, opened Type Launcher by hand, and found it still holding the
  role. A dropped home activity does not produce that sheet  — the system would re-resolve to the role
  holder and start it. A sheet means home resolution could not reach an
  unambiguous target *while the role still named us*, which is what the
  installer swapping the APK looks like: during the replace there is no home
  activity to resolve to, and nothing has been revoked. So the flag addresses
  at most A.

  The evidence that separates them is a bug report carrying the `processExit`
  records, the `ownPackage lastUpdateTime`, and the home-role line. A previous
  run ending in `packageUpdated` whose timestamp sits beside this package's own
  update time confirms the install window (B). One ending in `lowMemory` or
  `crash` at foreground importance points back at the saved-state flag (A). The
  **gap** between that exit timestamp and this run's first log line is what
  tells the two apart even when the reason is the same: seconds means a
  momentary window, hours means the launcher stayed unreachable until it was
  started by hand, which is A's signature. Decide after a
  report from a real device, and if the flag goes in, the test and its comment
  are the record to update — a reversal belongs in `SPEC.md` with its reason,
  not silently swapped.

- **"Default" / "Undefault" needs reconciling with its translations.** The
  long-press menu on a contact's number toggles whether that number is the
  default, and the English labels are `Default` and `Undefault` — the second is
  not a word, and neither says what it acts on. The translations landed as the
  *sense* rather than the letter ("Set as default" / "Remove as default", per
  locale idiom), so 63 locales now read better than the source they came from.

  Candidates for the English, none yet chosen: **Set default / Unset default**
  (shortest, symmetric, mildly technical), **Set as default / Remove as
  default** (Android-standard), **Use by default / Don't use by default**
  (warmest, mirrors the consent card's Allow / Don't allow — rejected as too
  long). Changing it is English-only and needs no re-translation, since the
  locales already say the right thing.

- **The Yoruba SMS label is `SMS`, not a Yoruba word.** `contact_action_message`
  first shipped as `Iṣẹ́`, which is "work"; the Yoruba for a message is `ìṣẹ́`,
  differing only by the tone mark on the first vowel. Correcting the mark would
  have left the label's whole meaning resting on one diacritic, in a font stack
  that doesn't always render Yoruba tone marks — one mark away from saying
  "Work" again. `SMS` is accurate (the action launches `smsto:`), unambiguous,
  and short. Replacing it with a fully Yoruba term needs a native speaker; the
  alternative is reversible in one string.

- **Analytics is opt-in now, as a trial.** Nothing is collected until the user
  taps **Allow** on the Settings consent card; an unanswered question behaves as
  a refusal. The repo owner asked for this explicitly and called it a trial —
  "I think it's fine but I don't know if it's the permanent I just want to try
  it" — so it is recorded here rather than treated as settled.

  What it costs: every existing install stops reporting crashes on the update
  that carries it, until its user answers. Expect crash volume to fall and stay
  down for whatever fraction never opens Settings. That is the point of the
  change, but it is also the thing to watch before deciding whether to keep it.

  Reversing it is **three edits, not one**, and this note exists because the
  constant's name suggests otherwise: flip `TELEMETRY_REQUIRES_CONSENT` to
  `false`, flip `DockSettingsStore.isTelemetryEnabled`'s default back to `true`,
  and remove the two `firebase_*_collection_enabled` meta-data entries from the
  manifest. The constant alone leaves the preference defaulting off and both SDKs
  starting disabled, which is not the old behavior — it is the new behavior with
  the card hidden, which would be worse than either. `PRIVACY.md`'s "Nothing is
  sent until you say yes" section reverts with the three — it is written to be
  removable as a block.

- **The opt-out's durable write blocks the main thread, on purpose.**
  `setCollectionGate(false, …)` reaches `SharedPreferences.commit()` before
  returning, from Compose's `onCheckedChange`. Codex asked for it to be moved
  off the UI thread (#666, round 2) having asked on round 1 for it to be
  written *before the asynchronous gap* — the two cannot both hold, since the
  gap is the boundary between the tap and any background work.

  Kept on the main thread. AGENTS.md L15 aims at cold-start, first-frame,
  scroll and transition paths, and a Settings switch is none of them; the cost
  is one boolean into an already-loaded instance, on the opt-out branch only;
  and the alternative is either losing durability — reopening the hole #666
  exists to close — or a second durable channel written asynchronously, which
  is more machinery than the thing it protects, with the same failure mode.

  Reversible: moving the write later is a one-line change if a real frame trace
  ever shows it. What is *not* cheap to undo is a report uploaded after the user
  asked for it to be deleted.

- [x] **Make the debug log default-safe, so a new call site can't send user
      data off device.** Done. A log call is now a hard-coded format string
      plus arguments (`LauncherDebugLog.event` / `warning` / `failure`), and an
      argument reaches the Crashlytics mirror only if its type cannot name
      anything of the user's — numbers, booleans, chars, enums. Every `String`
      is withheld by default, which is what closes the categories nobody
      anticipates: the on-device log is unchanged and still renders everything
      in full. `TelemetryRedaction` and the three call sites that had to seed
      it are deleted.

      **Correction to what this entry used to claim.** It said a `contactId`
      "is a `String` argument, so it would be redacted with no rule written for
      it". That was wrong — `contactId`, `eventId` and `dataId` are all `Long`,
      so the type rule would have *carried* them. They are tagged
      `sensitive(...)` at their nine call sites instead. The lesson is the one
      the repo owner had already drawn: the type is the default, not the
      verdict, and each value still gets a judgment.

      Two things the conversion turned up that were leaking and are now fixed:
      `KeyEvent.debugSummary()` mirrored `keyCode`, which on a type-to-search
      launcher reconstructs what the user typed; and `Intent.debugSummary()`
      mirrored the component and package. Both now split their fields — the
      identifying ones stay on device, the action, flags, timing and URI scheme
      still ride along, so a failed launch is still diagnosable.

      Still worth doing: nothing enforces that the format string is a literal
      rather than a built string. The property is a rule the call sites keep by
      convention, and one interpolation reintroduces the whole class of leak
      this design removed.

      **Decided approach** (repo owner): a plain unit test that parses
      `app/src/main` and asserts the first argument of `LauncherDebugLog.event`
      / `warning` / `failure` is a string literal with no interpolation. No new
      tooling — it runs in the `./gradlew test` CI already has. A custom Android
      Lint detector would be semantic rather than textual but needs its own
      Gradle module and tracks an API that breaks across AGP versions; `detekt`
      has friendlier rule authoring but is a whole plugin, config and CI step
      the repo does not otherwise want. Either remains an upgrade path if the
      test proves too blunt.

      **Open question to settle while building it**: a textual check can prove
      the argument is a literal, and can reject an interpolated one (a `"…"`
      containing `${` or `$name` is detectable). What it *cannot* prove is that
      a bare identifier is a `const val` we own rather than a `var` or a
      parameter — `logState`'s forwarded `reason` is exactly that shape, and it
      is legitimate. So the test needs a position on identifiers: reject them
      outright and require every format string be spelled at the call site, or
      allow a named constant and find some way to establish it really is one.
      Worth deciding deliberately rather than falling out of whatever the parser
      happens to do.

- [x] **Rework the Analytics opt-out so its pieces compose.** Done. The four
      mechanisms — an in-process gate, two persisted SDK flags, a lock that
      re-read the preference, and an edge-triggered deletion — are now one
      ordered transition in `LauncherTelemetry.applyCollectionPreference`,
      which owns the flags and the deletion together.

      Two changes carry it. The gate became a **tri-state**
      (`Unknown` / `Enabled` / `Disabled`), so an unread preference is no longer
      indistinguishable from an opt-out: breadcrumbs and keys are withheld in
      both, but traces run while unknown and stop once the user has actually
      said no. And the report deletion became a **sticky debt** recorded when an
      opt-out happens and cleared only when a delete succeeds, rather than
      recomputed from the latest preference.

      That closes both defects recorded here. A rapid off→on can no longer lose
      the deletion, because the debt is an event rather than a value and the
      transition discharges it *before* re-enabling upload. And a failed
      Performance opt-out can no longer leave traces flowing, because
      `startTrace` now refuses on a known opt-out — the case the old two-state
      gate could not express without also dropping every cold-start trace on a
      slow start.

      **The deletion debt now survives process death.** Closed in the
      follow-up. `DockSettingsStore.isReportDiscardOwed` records the promise
      beside the preference on opt-out and is cleared only once a discard
      succeeds, so a run that dies before servicing it hands the obligation to
      the next one. The preference seam widened from `() -> Boolean?` to
      `TelemetryPreferences`, which can read and clear that record, and the
      startup re-assert therefore services a debt it never incurred.

      Two readings fail toward *not* collecting: an unreadable choice changes
      nothing (assuming a value would let a corrupt file overwrite a stored
      opt-out), and an unreadable discard record counts as owed, because not
      knowing whether a promise was kept is not a reason to resume.

      This is also what retires the residual class below. Each of those windows
      is a moment where the flags could be left enabled with a discard still
      owed; with the debt durable, the next transition — or the next process —
      sees the obligation and services it, so they are repairable after the
      fact rather than needing to be prevented one gap at a time.

      The SDK side effects sit behind a small interface so the transition's
      ordering and failure semantics are unit-testable without Firebase — every
      defect this subsystem produced lived in the ordering rather than in the
      Firebase calls, and none of it was reachable from a JVM test while those
      calls were inlined. What still needs a device carrying the Firebase
      config is only whether the real SDKs honor the flags.

- **The widget picker's 36dp icons still rasterize during composition; only the
  preview image moved off it.** The follow-up asked for both. Moving the icons
  too would mean a `produceState` hop each, and their drawables are already in
  memory — `provider.icon()` is a field read, not IPC — so the cost being moved
  is a small `toBitmap` and the cost being added is a visible pop-in on the
  picker's opening frame, against the quality bar's "prefer showing the real
  thing instantly when it's already in memory". The preview image is the
  opposite case: whatever size the app shipped, and already progressive, so
  rasterizing it on the existing IO hop changes nothing a user sees.
  *Alternative:* make the icons async too and accept the pop-in, or cache the
  rasterized bitmaps (queued above) which removes the repeat cost without one.
  *Reversible:* the conversion is one line in each of two composables.

## Review and merge gates

- [x] Add `codex-review-check.yml` (mikelward/codex-review's consumer
      check): Codex reviews run here, but nothing verifies the workflow
      pin the ruleset should require.
- [ ] Verify the settings half of the fleet's bar: a ruleset on the
      default branch requiring the `lanes` commit status (App-published
      by `init`/`finalize`, `mikelward/lanes` migration stage 2 — `gate`
      is retired, it could never work as a required check under
      `pull_request_target`) and the `codex` status, plus conversation
      resolution and up-to-date branches, with the auto-merge setting
      enabled.
- [ ] **Add `zizmor` to the ruleset's required set** once it has reported
      on a pull request: the new `.github/workflows/zizmor.yml` runs
      unfiltered on every PR precisely so it can be required (a
      paths-filtered workflow creates no check run at all on a
      non-matching PR, which a ruleset waits on forever) — the posture
      piloted in mikelward/lanes and mikelward/ci-commit-artifact and
      rolled out fleet-wide. Add it alongside `lanes` and `codex` in the
      same ruleset update as the item above.
