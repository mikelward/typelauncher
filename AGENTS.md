# Type Launcher

Android home screen launcher app (Kotlin, single `:app` module).

## Project documentation

- Keep `SPEC.md` up to date when changing product behavior, architecture, persistence, permissions, navigation, or testing strategy.

## Git workflow

- Always start work from the latest `origin/main`. Before the first commit on any feature branch, run `git fetch origin main` and rebase the working branch onto `origin/main` (`git rebase origin/main`). This applies even when the branch already exists — a stale branch must be brought current before new work is added.
- If the rebase produces conflicts, resolve them rather than abandoning the rebase or branching from an older base.
- Never push commits authored on top of an out-of-date base when a fast-forward rebase onto `origin/main` was possible.

## Working with PRs

- Use the `mcp__github__*` MCP tools for all GitHub operations. The `gh` CLI is not available in this sandbox.
- Never leave a review comment thread silently dismissed. Either reply on the thread or resolve it — the user wants every thread to end in one of those two states, not "left open and ignored." When you think a comment is a false positive, say why on the thread (one or two sentences is fine): the reasoning is exactly what the user wants surfaced, and "Linux-only CI, doesn't apply" is more useful on the PR than buried in chat history. Acknowledgement noise ("good catch, will do") is fine and preferred over silence; the discipline is "say something or resolve," not "say nothing."
- Always link every open PR in the stack. Any time you push, summarise CI, or invite the user to review, list every currently-open PR on the feature by URL — one per line — not just the topmost one. The Claude Code mobile UI only renders the first PR card in a message and treats later links as plain text, so a single link can hide the rest of the stack (and may surface an already-merged PR while obscuring the live one). Worth the extra two lines.
- Always include visual diff links in PRs for UI/screenshot-affecting changes. If a Roborazzi/snapshot image changes or a screenshot test is used to demonstrate a UI change, generate a before/after/diff artifact from `origin/main` versus the branch using the same screenshot path, prefer a single PNG contact sheet (`before | after | changed pixels`) over HTML, place it under `/opt/cursor/artifacts`, and embed it in the PR body with an `<img>` tag. Restore any generated snapshot files before committing unless the snapshot update itself is intentional.
- Report when Copilot finishes reviewing a fresh push. Copilot's review runs asynchronously after each push; once its review event lands for the latest commit, surface a one-liner naming the SHA and comment count — e.g. `Copilot reviewed 87d9f02 — 0 comments` or `Copilot reviewed 87d9f02 — 3 comments, addressing now`. Tie it to the latest pushed SHA so a stale review of a superseded commit isn't conflated with the current state. The user uses this to know when the automated pass is done vs. still pending.
- Report Android `versionCode` after every merge to main. When a PR merges, fetch main and run `git rev-list --count origin/main` to get the `versionCode` (`app/build.gradle.kts` derives it from this count). Report it as e.g. `Need versionCode 72 (b81c23d) or higher to test PR #52's HTTP-error surfacing` — number, short SHA, and a one-clause summary of what the change gates. The user uses this to know which Firebase / locally-built APK contains their fix. Sandbox clones are usually shallow (`git rev-parse --is-shallow-repository` returns `true`), which silently truncates `rev-list --count` and makes the reported number lower than the real APK's. Run `git fetch --unshallow origin main` once at the start of any session that will report `versionCode`s — the user has been bitten by an under-by-15 count.
- Keep watching merged PRs for late review comments. Reviewers and bots routinely comment after merge (Copilot review, human follow-up). Stay subscribed to the PR's activity after the merge and handle each new comment per the "say something or resolve" rule above — reply, resolve, or open a follow-up PR with the fix. Stop watching once every comment posted on or after the merge commit has been answered or resolved, or after ~24h of silence with no new activity, whichever comes first. Don't drop the watch the moment the merge button is clicked.
- **On any CI failure, diagnose in this order:**
  1. **Check for a PR comment first.** The `build` job posts failing JUnit XML snippets as a PR comment when `./gradlew test` exits non-zero. If a comment appeared, read it — it contains the exact test class, method, and stack trace.
  2. **No PR comment after `build` failure = compile error.** The "print failing test details" step only runs when JUnit XMLs exist. A compile error produces no XMLs, so no comment is posted. Switch straight to code inspection; don't waste time polling for a comment that won't arrive.
  3. **`connected-tests` never posts PR comments.** The screenshot job prints failures to the Actions log only. When it is the *only* failing job and there is no PR comment, look at whether `build` is green — if so the failure is in the screenshot suite, not a compile error.
  4. **Fetch artifacts as a last resort.** Both jobs upload JUnit XMLs (`unit-test-reports`, `screenshot-test-reports`). Pull the run ID from the webhook's `details_url`, hit `https://api.github.com/repos/<owner>/<repo>/actions/runs/<runId>/artifacts`, follow `archive_download_url`, and read `TEST-*.xml`. Note: the GitHub API enforces an anonymous-request rate limit that can block this even for a public repo — if you get a 403, fall back to code inspection.
  5. **Before debugging, check whether the failure is pre-existing.** Run `mcp__github__pull_request_read` with `get_check_runs` for the PR's *base* commit (or the commit just before your push) to see if the job was already red. A pre-existing failure is not yours to fix; note it and move on.

## Cursor Cloud specific instructions

### Environment

- **JDK 21** is pre-installed on the VM. AGP 9.2.0 requires JDK 17+.
- **Android SDK** is installed at `/opt/android-sdk`. Environment variables `ANDROID_HOME`, `JAVA_HOME`, and `PATH` are set via `~/.bashrc`. In non-login/non-interactive shells (e.g. plain `Shell` tool calls), you may need to `source ~/.bashrc` or export these variables explicitly before running Gradle commands.
- The Gradle wrapper (`./gradlew`) auto-downloads Gradle 9.4.1 on first run.
- AGP auto-installs `Android SDK Platform 36.1` (compileSdk minor API level 1) on the first build if only `platforms;android-36` is present.

### Key commands

| Task | Command |
|------|---------|
| Build debug APK | `./gradlew assembleDebug` |
| Run unit tests | `./gradlew test` |
| Run lint | `./gradlew lint` |
| Clean build | `./gradlew clean` |

### Testing expectations

- Code changes must include or update relevant unit tests.
- UI changes must include or update screenshot tests that cover the changed UI state.
- Run the relevant tests before submitting changes and report any environment limitations clearly.
- **Don't invent Compose Test imports.** Several `SemanticsNodeInteraction` API points (`assertExists`, `assertDoesNotExist`, `assertIsDisplayed`) are member functions, *not* extensions, so writing `import androidx.compose.ui.test.assertDoesNotExist` produces an "Unresolved reference" compile error even though the call site `composeRule.onNodeWithTag(...).assertDoesNotExist()` works fine without an import. Rule: when adding a Compose test API call, only add the import if a sibling test in this repo already imports it (`grep "import androidx.compose.ui.test.<name>" app/src/test`); otherwise call it as a method and let Kotlin resolve it. This sandbox can't always run `./gradlew test` (Google Maven is blocked), so this class of bug ships if the rule isn't followed by inspection.

### Emulator and connected tests

- **Android Emulator** (v36.5.11) is installed at `$ANDROID_HOME/emulator/` and exposed on `PATH` via `~/.bashrc`.
- **System image**: `system-images;android-36;google_apis;x86_64` (Google APIs Intel x86_64, API 36).
- **KVM is not available** in the Cursor Cloud VM (`/dev/kvm` does not exist). The VM runs inside Firecracker, which does not expose nested virtualization.
- Without KVM the emulator falls back to software emulation (TCG/QEMU), which is extremely slow and may not boot reliably. Running `connectedDebugAndroidTest` or `installDebug` in the cloud VM is therefore **not practical** without a KVM-capable host.
- To create an AVD for local or KVM-enabled CI use:
  ```sh
  avdmanager create avd -n "pixel_api36" -k "system-images;android-36;google_apis;x86_64" -d "pixel_6"
  emulator -avd pixel_api36 -no-window -no-audio -gpu swiftshader_indirect
  ```
- On a KVM-capable host, verify with `emulator -accel-check` (should report "KVM is operational").

### Notes

- This is a pure Android client app with no backend services, databases, or Docker dependencies.
- The lint report is written to `app/build/reports/lint-results-debug.html`.
- First build takes ~1-2 minutes due to Gradle daemon startup and dependency downloads; subsequent builds are much faster.
