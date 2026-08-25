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
- Stop searching for the screenshot-diff comment altogether. The lookup now paginates (`gh api --paginate`), which is correct at any comment count but is still a search costing O(comments) API calls per run on a long PR. Stashing the comment id somewhere stable — a workflow-run output, a branch note, the check-run summary — would make the upsert a direct PATCH. Not urgent; this is about cost and simplicity, not correctness.

## Layout, caching, rendering, and recomposition follow-ups

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

## Not planned

- **Showing work-profile calendar events on the agenda.** Investigated 2026-07: not feasible in practice, because access is gated by MDM admin policy that neither the user nor the app can grant. Findings, in case the policy landscape changes:
  - The agenda's `CalendarContract.Instances` query reads only the personal profile's calendar provider; the work profile runs an isolated provider instance under a separate Android user, and `READ_CALENDAR` does not cross that boundary.
  - Android 10+ has a purpose-built mechanism — `CalendarContract.Calendars/Events/Instances.ENTERPRISE_CONTENT_URI` — letting a personal-profile app query the work profile's calendar. Three gates must all be open: (1) the work profile's device policy controller must allowlist the calling package via `DevicePolicyManager.setCrossProfileCalendarPackages()` — the default is an **empty** allowlist, only the org's MDM admin can change it, and there is no user-side or app-side way to self-grant; (2) the user must enable the work profile's cross-profile calendar setting; (3) the personal-profile app needs `READ_CALENDAR` (we have it). Gate (1) is the showstopper: admins commonly allowlist Google Calendar, not arbitrary launchers, so shipping this would light up for approximately no one.
  - If it ever becomes worth doing, the shape is small: issue the same instances query a second time against `Instances.ENTERPRISE_CONTENT_URI` in `loadAgendaEvents` and merge before organizing (the search index inherits it via the same function); tag rows with an `isWorkEvent` flag so the tap path uses `CalendarContract.startViewCalendarEventInManagedProfile()` instead of the personal `ACTION_VIEW` intent (which cannot open a work event). Constraints: the cross-profile provider only permits an allowlisted projection (our current columns appear to be on it — verify against a real managed profile) and returns an empty cursor rather than throwing when access is disallowed or no work profile exists, so the query can run unconditionally. `minSdk = 34` means no API-level guard is needed.
  - The `INTERACT_ACROSS_PROFILES` / connected-apps route does not help — it grants no cross-profile content-provider access, and full cross-user provider queries need system-only permissions a launcher cannot hold.
  - **User workarounds (no code needed):** (1) add the work calendar app's widget to a widget page — the launcher already hosts work-profile widgets, so this works today and is the recommendation to give users; (2) share the work calendar with the personal account server-side so it syncs into the personal provider — but many orgs block sharing work calendars with non-work accounts, so this one often isn't available either.

## Privacy

- [ ] **Hold Crashlytics off before it auto-starts, when a discard is owed.**
      Crashlytics auto-initializes from a `ContentProvider` *before*
      `Application.onCreate`, so its own persisted collection flag decides what
      happens before any launcher code runs. A launch that inherits an
      unserviced discard (opt-out → opt-in → process death) therefore races its
      own startup transition: the SDK can upload a queued report before the
      coroutine disables collection and deletes it. Raised by Codex on #666 and
      real. Not a defect #666 introduced — the window predates it; what changed
      is that the durable debt makes the race visible.

      **Codex's round-6 finding belongs here too.** If the opt-out's own write
      *throws*, the stored choice stays `true` while only the in-memory state
      says otherwise — and the queued transition then re-reads the store,
      discharges the debt, and re-enables collection in the same process, with
      the Settings switch still showing off. Same root: our record can fail to
      take, and a re-read of the store then contradicts what the user actually
      chose. The inversion below is what makes the record authoritative instead
      of advisory.

      **Codex's round-5 finding on #666 is the same defect from the other end**,
      and belongs to this item rather than to that PR. `applySdkFlags` logs a
      throw from either SDK and carries on, so a failed *disable* leaves
      collection on while the discharge runs and then clears the durable marker
      anyway — no debt left for a later transition to retry. Both that and the
      auto-start window above come from the same wrong assumption: that the
      SDK's persisted flag can be set on demand and therefore reflects our
      intent. It cannot, and no amount of ordering inside the transition fixes
      a write that did not take.

      The fix is to stop relying on the SDK's own flag as the source of truth:
      declare `firebase_crashlytics_collection_enabled=false` in the manifest so
      nothing auto-collects, and have the transition turn it on once it has
      established that nothing is owed. That inverts the initialization contract
      for the whole app, and getting it wrong means telemetry is silently off
      for everyone — the failure this repo is least able to detect — so it wants
      its own PR and its own verification on a device carrying the config.
      When it lands, the discharge should also decline to clear the marker
      unless the disable it depends on actually succeeded — which that design
      makes knowable, and today's does not.

### Decisions needing review

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
