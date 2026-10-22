---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-22
---

# Type Launcher

Android home screen launcher app (Kotlin, single `:app` module). `CLAUDE.md` and `GEMINI.md`
are symlinks to this file; edit `AGENTS.md` and bump `last_modified`.

**At the start of every session, print the full absolute path of the `AGENTS.md` you loaded
and its `last_modified` date** (front matter), so a stale or wrong copy is caught first.

Product and architecture decisions live in `SPEC.md`, open work in `TODO.md`, and the
procedures behind these rules (CI diagnosis, pre-CI pitfalls, visual diffs, environment) in
`docs/agents-reference.md`.

Keep this file short — one-liners over essays, **under 24,000 bytes** (`wc -c AGENTS.md`):
Antigravity caps rule files at 24 KB and silently truncates the tail. State the rule and
its failure; incident narratives belong in commits. Add a rule the first time
something bites, rewrite or trim rather than append, and delete rules that stopped biting.

## Project documentation

- Keep `SPEC.md` current when changing product behavior, architecture, persistence,
  permissions, navigation, or testing strategy.
- **`SPEC.md` records product, functionality, and architecture decisions, not
  implementation detail**: *what* the launcher does and *why* (the shapes an icon can take
  and how `System` resolves, the carousel commit rules, the keyboard-reservation state
  machine). Exact dp values, private helper names, per-locale wording, which composable holds
  a flag, or whether a string is translated stay in code ("still true if the implementation
  were rewritten?"). A change touching only such detail adds no `SPEC.md` line; one that
  moves the product or architecture states the decision at that altitude and stops.

## Engineering quality bar

Every change holds the line on **correctness, fast loading, and jank-free layouts and
animations** — hard acceptance criteria: a launcher is the first thing the user sees after
unlocking and the surface they scroll dozens of times a day.

- **Correctness**: matches `SPEC.md` and intent; handles empty state, work profile, locale,
  configuration change, process death, missing permission; preserves invariants. New
  behavior gets a unit test, UI a screenshot test wired into CI; a bug fix gets a test that
  fails before and passes after.
- **Fast loading**: never block the main thread on disk, IPC, `PackageManager`,
  `LauncherApps`, `AppWidgetHost`, content resolvers, or any I/O. Cold-start and first-frame
  paths are sacred: lazy, off-main, cached aggressively (`Cache widget size hints to skip
  redundant IPCs` / `Defer widget RemoteViews apply during carousel transitions` are the
  template). A new data source loads asynchronously behind a placeholder.
- **Jank-free**: hold 60/90/120 Hz with no dropped frames. Avoid recomposition storms
  (`remember`, `derivedStateOf`, stable types, `key`) and layout thrash (no measuring in
  draw or reading animated state outside its scope); defer binds, icon loads and IPC during
  transitions. Touching a `LazyList`, `Pager`, carousel, drag-and-drop or
  `animate*AsState` / `Transition`: walk a frame through the hot path and flag anything that
  could cost > 1 ms of main thread per frame.

Say which pillar you couldn't verify locally ("jank unmeasured — no emulator").

## Spacing, padding, and margins

Measure padding and margins on every UI change; spacing is part of the deliverable.

- **4dp grid** (`4`, `8`, `12`, `16`, `20`, `24`, `32`, `40`, `48`; commonest 4/8/12/16/24).
  Reaching for `5`/`7`/`10`/`14`, take the nearest grid value or justify it in a line
  (baseline alignment, hairline divider). No spacing tokens exist, so check each file you
  touch: `grep -nE "\\.dp\\b" app/src/main/java/app/typelauncher/ui/<file>.kt`.
- **Reuse values** from peer composables (`SectionCard.kt`, `HomeScreen.kt`,
  `WidgetsScreen.kt`) — no 14/16/18 drift on similar elements.
- **Symmetry by default** (`padding(horizontal = 16.dp, vertical = 8.dp)`); each asymmetry
  gets a one-sentence justification in the PR or chat.
- **Verify visually** in the Roborazzi diff (correct dp that renders uneven is still wrong);
  if screenshot tests can't run, say so and ask for a device check.
- **Flag inconsistencies outside the diff** (file, line, what's off, fix) without fixing them
  in the same commit; the user decides.

## Git workflow

- Assumes `origin`; without remote Git (Codex cloud) continue from the checked-out commit,
  invent no remote, and say so.
- **Branch naming**: `<agent>/<short-topic>` — `claude/…` Claude Code, `gemini/…` Gemini /
  Antigravity, `codex/…` Codex. One topic per branch; never commit to `main`.
- **Your branches are yours**: create, reset, force-push, delete the ones this session
  created or was assigned, freely — merged names and reviewed commits included (this file is
  the standing grant). Don't fold unrelated work into a pinned branch; the pinned name is a
  default, not a ceiling. Another tool's prefix, anyone else's branch, or yours from another
  session: check first. `main` is never force-pushed or rewritten.
- **Sync before you start**: `git fetch origin +refs/heads/main:refs/remotes/origin/main`
  (`git fetch origin main` leaves single-branch `origin/main` stale), then `git rebase
  origin/main` before the first commit, even on an existing branch; resolve conflicts rather
  than abandoning the rebase. Never push on a stale base when a rebase was possible.
- **Use `git worktree`** when available, one per branch.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene first**: fetch
  main by refspec, cut a fresh `<agent>/<short-topic>` off `origin/main`, announce it. Without
  a remote, ask for a synced checkout rather than branching off a stale `main`.
- **After a merge, take a fresh branch**, not the merged name reset (its remote ref keeps
  `origin/<branch>..HEAD` spanning merged commits); reset only a sandbox-pinned name. No short
  check tells merged from unmerged, and `--force-with-lease` is no safety net after a fetch.
- **Clean history before review and merge** (`git rebase -i origin/main`): no `fix CI` /
  `address review` / `wip` / reverted-experiment commits — rebase-merge lands every commit on
  `main` with its own subject, blame and bisect step. `--force-with-lease`, never bare `--force`.
- **Committer line**: the agent authors; a merge rewrites the committer — expected. Never
  re-author or amend merged commits, and never raise it: no PR note, chat caveat, or
  offer to fix.

## Commit messages

- **Every commit subject in a push to `main` ships to Play's "What's new"**: `ci.yml` skips
  prefixed and pure docs/dotfile commits and writes the rest oldest-first into
  `whatsnew-en-US` as `• ` bullets (always bulleted; repeats once), bodies dropped. The PR
  title never lands (rebase merge), so title **every** commit for end users.
- **A prefix promises no user-visible effect** (CI still builds it): `ci:` CI/workflow
  (snapshot regenerations, action bumps); `docs:` documentation only, `PRIVACY.md` included
  ("What's new" is a store field nobody sees at update time); `internal:` build config and
  plumbing; `refactor:` behavior-preserving; `test:` / `tests:` test-only. A commit that also
  changes production behavior goes bare.
- **A dependency the app ships is bare** (it changes what the app runs); a test- or build-only
  bump you write keeps `internal:`; the weekly batch can't tell them apart and lands bare.
- **Sentence case, plain English, ≤ ~70 characters**: "Fix drag and drop in the dock", not
  "Arm dock carousel-suppression on long-press" — no internal symbols (`pointerInput`,
  `RemoteViews`), mechanism detail, or "Defer"/"Gate"/"Arm" imperatives. CI measures the full
  notes against Play's 500-character cap and drops trailing subjects. Engineering detail
  (mechanism, bug, file:line) goes in the body.
- **No model identifier** — never name the coding agent's model or version (`Opus 4.8`,
  `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR, comment, or pushed
  artifact; keep it to chat. Generic attribution (`Co-Authored-By: Claude` / `Gemini`,
  `Claude-Session:`, "Generated with Claude Code") is fine.

## Talking to the user

- **One question at a time** — in plain chat or the client's question tool
  (`AskUserQuestion` in Claude Code, `ask_question` in Antigravity). Then stop and wait: no
  assumed answer, no self-picked "recommended" option, no work on the part it affects. Never
  ask while the user is typing.
- **Answer a mid-turn message first**, before any further tool call (except a merge cue,
  whose hygiene runs first and whose announced switch is the answer).
- **Keep replies short**; lead with what matters most.
- **Message only when the user needs to see it** (maintainer, 2026-10-15): each one notifies
  their phone. Work and listen in silence; speak up for a question or blocker, something to
  merge or act on, a merge, an autopilot decision (to review when seen, never waited on), or a
  change from the plan, `SPEC.md` or their instructions, a review-prompted one included.
  Never for progress (a client's nudge doesn't count), a review or a push alone (its report
  rides the next message), routine machinery, or a mistake caught and fixed before it reached
  the repo. The session-start `AGENTS.md` line still opens every session.
- **End the turn by restating any pending decision** — the last line is the question (or
  autopilot guess) you're waiting on, written out, every turn until answered. Nothing pending,
  no line; a scheduled check that finds nothing re-arms silently.

## Error handling

- **Don't silently swallow exceptions.** Every catch **logs** the exception through the
  usual logger, with sanitized context (*Privacy* applies — "querying activities failed for
  1 package", never which), **cleans up** what the `try` acquired (closeables,
  `LauncherApps` / `AppWidgetHost` handles, partial writes, UI state — `use { … }` /
  `finally`), and **handles the edge case explicitly** (default, null, sentinel, domain
  exception). Narrow the type or rethrow `CancellationException` first. To ignore a failure
  (often an uninstalled or work-locked package), say why in a one-line comment and still log
  at debug.

## Privacy

- **Never put user data in any artifact leaving this machine** (commits, PRs, replies,
  issues, branches, comments, fixtures, screenshot snapshots, Play Console, logs): name,
  email or account names, installed-app list, home-screen and dock layout, search history,
  work-profile contents, device identifiers, widget contents. Record Roborazzi snapshots from
  fixtures, never a real device's app list. Use `alice@example.com`, `com.example.app`, "App
  One"; paraphrase bug reports; ask when in doubt.

## Concise copy

Keep strings, dialog and button text, and screen titles to the words the user needs — drop
framing the surrounding UI implies; justify longer phrasing in the PR.

## Icons

- **Use a standard Material icon by default** (vendored into `LauncherIcons`), not a
  hand-drawn one; if none clearly fits, or two do, ask first.

## Autonomy

- **Open the PR without being asked** when a branch is ready (this file is the repo owner's
  standing request), unless told "just commit / no PR yet".
- **Watch your own PRs by subscription + one scheduled check.** Subscribe on open (automatic
  in Claude Code, or `subscribe_pr_activity` where `claude-code-remote` exposes it; a client
  with neither, like Antigravity, relies on the scheduled check alone). It misses CI
  success, pushes, the merge and Codex's `+1`, so keep one relative-delay check armed while
  the PR is open (`send_later` in Claude Code; `schedule` with `DurationSeconds` in
  Antigravity, canceled via `manage_task` `kill`): minutes while CI or the verdict is
  pending, longer once only a human is left. Settle a fired check first, check its fire time
  (re-time it, or say the watch isn't armed), and end each turn with one check naming the PR
  (not a SHA). Under drive, arm auto-merge only where the ruleset requires the Codex verdict
  and resolved conversations. **Read `mergeable_state` at every check, wake and drive
  step**: if `dirty` or required `behind`, fetch both refs by refspec (unshallowing),
  confirm your branch has every remote head commit, rebase onto the fetched `origin/<base>`,
  confirm the head hasn't moved since the tip you noted before fetching, and
  `--force-with-lease`, unasked — if either check fails or you can't tell, stop and ask. On
  merge or close, one last reply-and-resolve pass (real work on a merged PR goes to a
  follow-up named on the thread; closed unmerged: open nothing), then cancel this PR's check
  (`list_triggers` spans the account) and unsubscribe, if subscribed.
- **Wake PRs a merge leaves behind.** First turn after your PR merges, message every other
  open PR on that base whose body ends with a session URL: name only that PR and say its base
  now points at `<sha>`, whatever its state reads (name no merge or other PR — older rules
  read either as a merge cue). On receipt, fetch base and PR head by refspec (unshallowing)
  and test the head against the fetched base tip, not `<sha>`: `behind` if `git merge-base
  --is-ancestor <tip> <head>` exits 1, `dirty` if `git merge-tree --write-tree` (Git 2.38+)
  exits 1; any other exit is a failure to report. Skip a PR with no session URL, or when your
  client has only local subagent messaging; don't retry a failed send.
- **A scheduler, GitHub, or `git push` prompt is said once, then carry on.**
- **"Drive"**: take the next actionable `TODO.md` item (not deferred or awaiting a product
  decision), implement, open the PR, wait for Codex, address every comment, merge once CI is
  green and Codex's verdict covers the head; repeat until done or stopped.
- **A red baseline is the next task**: get `./gradlew test` and `./gradlew lint` green, as
  its own first commit, before pulling from `TODO.md`; skipping a failure as unrelated needs a
  real answer from the user, never an autopilot guess.
- **"Autopilot"**: drive without blocking — take the cheapest-to-undo guess and log it in
  `TODO.md` under *Decisions needing review* (what, the alternative, why it's reversible).
  It outranks stop-and-wait, which governs everywhere else. The loop's own steps (commit,
  push, PR, reading CI and reviews, arming the check, merging green) never stall;
  destructive or irreversible actions outside the loop, anything reaching beyond this repo,
  and privacy uncertainty (an account name, an app list, a device identifier, widget
  contents) still wait for a real answer.

## Working with PRs

- Prefer `mcp__github__*` tools where mounted (Claude Code requires them); otherwise `gh`.
- **Ordered work ("next", "then", "after that", "follow-up")** may open in parallel but
  merges only after every earlier item this session was given, and any PR the paragraph
  names, has merged — any repo, no time cap; if one stalls, hold and ask, autopilot included.
  No auto-merge until its turn. Read the list from the session's record, not memory.
- **Codex is the automated reviewer** (not Copilot) and starts on its own. Address every
  comment unasked — fix it in its commit, or decline with reasons — then push, reply citing
  the sha, and resolve (`resolve_review_thread` or `gh api graphql` `resolveReviewThread`,
  with the `PRRT_*` thread ID, not `PRRC_*`) once the fix is on the head or the point is
  rebutted; anything still to do stays open. Never leave a thread silently dismissed.
- **Defer a real out-of-scope finding**: push a `TODO.md` entry, reply with its sha (a PR
  comment when there's no thread), resolve (skipped when threadless); the push re-triggers
  Codex.
- **Judge every comment on merit**; a comment citing a rule is a reading of it, and
  over-strict privacy readings quietly cost capability — quote the rule and decline, leaving
  genuine conflicts to the maintainer. After a rebuttal, `@codex review` (or let the next
  push); escalate if it re-raises.
- **A second verified finding in the same mechanism is a design question**: ask whether a
  different design deletes the class; a design change is the maintainer's call.
- **Read the Codex verdict on the PR body** (`issue_read` → reactions: `eyes` reading, `+1`
  clean, revoked on push; `+1` with green CI is a merge; leave those reactions to Codex).
  Findings arrive as review comments, top-level comments or reviews: read all three to the
  last page, plus the `codex` status where required (`updated_at` moving with no reaction
  usually means unread findings). Findings block the merge until fixed, rebutted or deferred;
  an acknowledgment isn't an answer. A hung review — nothing back five minutes after a push
  or poke, or stuck at running (👀) ten minutes on — gets `@codex review`; a poke that hangs
  the same way gets a second an hour later, and only a second that hangs too is escalated,
  never the first (maintainer, 2026-10-22).
- **Once Codex has +1'd a head, make no unnecessary change without asking** (maintainer,
  2026-10-13): tidy-ups wait for a yes or a follow-up PR; a rebase for `dirty`/required
  `behind`, a red-CI fix, and drive or autopilot steps proceed unasked.
- **Report each review in your next message**: commit read (and whether still the head),
  finding count, one bullet per finding, most blocking first — outranking short replies; ask
  only the top question.
- **Refresh the PR title and body with every push — body first** — to match the branch diff;
  with no PR yet, push, then do it as soon as one is opened. **Link every open PR in the
  stack, one URL per line.**
- **UI-affecting PRs carry a visual diff** (`before | after | changed pixels` contact sheet,
  refreshed every UI push; procedure in `docs/agents-reference.md`).
- **After every merge to `main`, report the versionCode** — `git rev-list --count
  origin/main` after a refspec fetch (with `--unshallow` while shallow, or it undercounts):
  `Need versionCode 72 (b81c23d) or higher to test PR #52's fix`.
- **"Release notes since N"**: up to five `• ` end-user bullets in one code block for commits
  N+1..`origin/main` (freshly fetched), ranked by impact, one feature per bullet, reading
  bodies where a subject is unclear. Skip prefixed, docs and CI commits, dependency bumps
  (unless nothing else changed — then one bullet), and anything the range added then undid;
  describe a reworked feature by its final state.
  Range above, one line below naming what was left out. Offline, only from a complete local
  `main`, saying it may be behind; a shallow one can't answer.
- **On a CI failure**, read the failing-tests PR comment first. No comment from `build`
  means no test failed (unless `report-build-failure` warns it couldn't post one), not a
  compile error: open the failed step — compile, resource merge, lint (runs after tests), or
  a missing release secret on `main`. `connected-tests` never comments: if it alone fails
  and `build` is green, the screenshot suite failed. PR `build` builds
  `refs/pull/<N>/merge`: reproduce the merge with `origin/main` before bisecting, and check
  whether the base was already red. Full order in `docs/agents-reference.md`.
- Skip your own webhook echoes silently — the test is "did *I* just post this body?", not
  who the author is.

## Cost and reliability

Call out cost and reliability up front for new infrastructure or external calls (third-party
API, analytics, more Firebase, a fetch on a launch path): dollar figure, failure modes, rate
limits, latency, what the user sees when it's down — latency on cold start, first frame, a
scroll or a transition stated against the pillars. Say so when the impact is nil.

## Language and spelling

**US English everywhere** (strings, commits, PRs, comments, KDoc, identifiers, docs) — base
`values/strings.xml` is `en-US` on Play and subjects feed `whatsnew-en-US`. Watch `color`,
`-ize`, `behavior`, `center`, `canceled`, `gray`, `dialog`, `license`; framework spellings
stay. Spelling, not locales: don't create `values-en-rGB/`.

## Translations

The user approves English copy in chat before anything is translated; where the approved
translation lands (same PR or follow-up) is never a finding, nor is English changed
alongside its locale entries. A base string shipping ahead of
its translations gets a per-string
`tools:ignore="MissingTranslation"` (root `xmlns:tools`) and a `<!-- TODO: translate -->`
comment above it, both removed at fan-out; a reworded existing key needs only the comment.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- JDK 21 is pre-installed; the SDK lives at `/opt/android-sdk` (`ANDROID_HOME`),
  pre-installed on Cursor Cloud (`source ~/.bashrc` in non-login shells). In Claude Code on
  the web, `.claude/hooks/session-start.sh` provisions it and runs `scripts/unshallow.sh`,
  but only Claude Code invokes the hook automatically: other agents run
  `scripts/unshallow.sh` themselves and, if `/opt/android-sdk` is empty in a remote
  container, `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` (then export
  `ANDROID_HOME` and `ANDROID_SDK_ROOT` as the path it reports ready: only Claude Code keeps
  the hook's environment) — never hand-install.
- `./gradlew assembleDebug` / `test` / `lint` / `clean`. No KVM, so no practical emulator:
  frame timing and connected tests need a device.

## Testing expectations

- Code changes include unit tests. Run `./gradlew test` and `./gradlew lint` when you can;
  otherwise check the pre-CI pitfalls below by inspection before pushing and say what was
  verified by inspection only.
- **Fix preexisting failures as the first commit** (skipping one needs the user's OK). **No sleeps, retries, or bumped timeouts** for racy tests — make the
  ordering explicit (advanced test dispatcher, `runTest`, a gate you release). **Never
  disable a failing check** (test, lint, Roborazzi comparison).
- **Verify the sandbox before assuming either way**: `command -v sdkmanager`, `ls
  /opt/android-sdk`, `curl -s -o /dev/null -w "%{http_code}" https://maven.google.com/`
  (200/302/404 reachable; 403 from the egress TLS-inspection CA blocked — flag it).
- **UI changes include screenshot tests in `.github/workflows/ci.yml`'s `--tests`
  allow-list**, one `run:` step per `*ScreenshotTest` class, recording under
  `app/src/test/snapshots/images/`. CI auto-commits drift to same-repo PR branches (`ci:
  refresh recorded screenshots`): `git pull` before pushing again; never hand-commit PNGs.
- **A new screenshot class can't record on its own PR**: `pull_request_target` runs `main`'s
  allow-list, and the auto-commit deletes hand-committed PNGs. Add captures to a class `main`
  lists; a new class takes two PRs — class plus its allow-list step (capturing nothing; never
  the step alone, which fails), then the captures. A `main` push records to the artifact only.
- **Opaque pre-CI pitfalls** (`docs/agents-reference.md`): copy Compose test imports from a
  sibling test (`assertExists` and friends are members); escape `'` as `\'` in strings; a new
  required `@Composable` parameter also needs `ui/Previews.kt`; a `TextField` in a `Dialog`
  hangs Robolectric — screenshot an extracted `XContent`.

## CI timing

Report a significant slowdown (>25% or >30 s on a job under ~5 min) with its likely cause
(a heavy dependency, Robolectric cold start, a slow test, a cold cache), comparing job
totals for the same job on the same kind of ref — PR against PR, `main` against `main`.
