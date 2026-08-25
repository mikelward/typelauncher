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
- Add an AGPL license gate to `android-ci.yml`: fail if a dependency declares
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
- Move widget picker preview and app-icon bitmap generation out of composition. Add async loaders/caches keyed by provider and requested preview/icon size, render placeholders first, and rasterize drawables away from the UI thread.
- Use lazy or otherwise bounded rendering inside the widget picker list. The picker currently materializes matching app groups in a regular `Column`; flattening into the outer lazy list or using a bounded nested lazy list would scale better on devices with many widget providers.
- Narrow app-list scroll-state reads to the overflow chevrons. Wrap `canScrollBackward` / `canScrollForward` in `derivedStateOf` near the chevron UI so scroll-driven invalidation stays localized.
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

- [ ] **Make the debug log default-safe, so a new call site can't send user data
      off device.** `TelemetryRedaction` closes today's leak by matching each
      log token against the packages the launcher has seen, which is exact and
      covers call sites added later — but it is still a *filter*, so it fails
      open: it knows only about package names, and only ones already seen. A
      contact id, a calendar row id, or a package the launcher has never loaded
      passes straight through.

      **Known still-open when the filter shipped** (PR #655, review round 8):
      nine call sites carry stable provider row ids into breadcrumbs —
      `openAgendaEvent eventId=`, `openContactResult contactId=`,
      `toggleContactStarred contactId=`, `openContactCard contactId=`,
      `setNumberDefault dataId=`, and the matching failure paths in
      `ContactActions` and `writeStarred` / `writeNumberDefault`.
      `TelemetryRedaction` matches installed package names only, so none are
      touched. Deliberately not patched with a list of key names to blank:
      that is the same fail-open shape this item exists to replace, and it
      would say nothing about the next site added under a name nobody
      anticipated. The design below closes them without a rule per site.

      **Decided shape** (repo owner, over the PR that added the filter): invert
      the default. A log call becomes a hard-coded format string plus
      arguments; the format string and every **non-string** argument (ints,
      enums, booleans) are safe by default and go off device as they are.
      **`String` arguments are sensitive by default** and render in full on
      device but as a placeholder in the Crashlytics mirror, unless the call
      site tags them non-sensitive. A tag the other way marks a non-string
      argument sensitive where one turns out to be identifying.

      That fails safe by construction and covers every category rather than
      just packages: the leak that started this — a phone number reaching a
      breadcrumb through `Intent.dataString` — is a `String` argument and would
      have been redacted with no rule written for it. The cost is converting
      the existing call sites to the format form, which is mechanical.

      `TelemetryRedaction` retires once this lands — it exists only because the
      filter had to close the leak before the structural fix was built.

## Decisions needing review

- **Dropped the authority from logged URIs, not just the path.** Review flagged
  that a hierarchical URI's authority carries any userinfo
  (`https://alice:secret@host/…`) and that a `content://` authority names an
  installed app. Taken the safe way — scheme only — rather than keeping an
  allowlist of hosts. The cost is on-device diagnostics: the log no longer says
  whether a failing `content://` was contacts or calendar. Reversible by adding
  a host allowlist if that distinction turns out to matter; the alternative was
  keeping the authority and accepting the leak, which it isn't.

- [ ] **Rework the Analytics opt-out so its pieces compose.** Shipped in PR #655
      and correct for the ordinary paths, but eleven review rounds established
      that the current shape — an in-process gate, two persisted SDK flags, a
      process-wide lock that re-reads the preference, and an edge-triggered
      report deletion — does not compose cleanly, and each mechanism added to
      fix one interaction produced the next finding.

      **Known open when it shipped** (round 11): a rapid off→on toggle can lose
      the deletion request. Both queued application-scope jobs re-read the final
      `true` preference inside the lock, so neither reaches
      `discardUnsentReports()`, and reports pending at the moment of the opt-out
      survive it. Latest-value convergence (added to fix a rapid-toggle race)
      and edge-triggered deletion (added to honor the `PRIVACY.md` promise) are
      individually right and mutually exclusive as written. Narrow — the window
      is one uncontended lock acquisition and the user ends opted *in* — but
      real.

      A pending-deletion flag would close it and would be the fourth mechanism
      in a subsystem where the previous three each produced a finding. The
      rework should instead make the opt-out a single ordered state transition
      that owns the deletion, rather than a value re-read by racing jobs.
      Untestable in the sandbox: none of these properties are observable
      without a device carrying the Firebase config.

      **Also open** (round 12): the two SDK opt-outs each run under their own
      `try`/`catch`, so a `RuntimeException` from
      `isPerformanceCollectionEnabled = false` leaves Performance collecting
      while the switch and the stored preference read off — and `startTrace`
      no longer consults the in-process gate (ungated so a trace started
      before the preference is read isn't silently dropped), so nothing
      downstream stops it. Both obvious patches re-open something an earlier
      round closed: re-gating traces restores the dropped-trace case, and a
      retry path adds work behind the lock that already mis-sequences the
      deletion above. The single ordered transition is what fixes both.
      Bounded meanwhile: Performance carries durations and counts under
      hard-coded names, so a failed opt-out here leaks timing, not user data;
      Crashlytics, which carries the breadcrumbs, opts out on its own `try`.

- **The widget picker filter matches app names and widget labels, and stops
  there — no match against a widget's `android:description`.** The filter item
  that asked for label matching (now shipped) floated description matching as a
  "possibly". Left undone deliberately: the picker renders only the app name and
  the widget label, so a description hit would surface a result with the query
  visible nowhere on screen, and descriptions are prose — a sentence like "Shows
  your upcoming events" turns incidental words into matches and dilutes the
  ranking tiers, which are built for names. *Alternative:* read
  `AppWidgetProviderInfo.loadDescription` (available at `minSdk 34`) and match it
  at a tier below the label. *Reversible:* one extra `launcherMatchTier` call in
  `WidgetPickerCard`'s group fold; nothing persists and no UI moves.

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
