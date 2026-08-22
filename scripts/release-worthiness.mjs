// Shared source of truth for "is this commit release-worthy" — the
// release-notes generator ("Build release notes" step, android-ci.yml)
// shells out to this file instead of carrying its own `case` statements,
// so a docs/PRIVACY.md-only diff being both docs-lane (.github/lanes.conf)
// and non-release-worthy can't silently drift apart by editing one copy
// and not the other (Codex's finding on PR #646).
//
// `case "$f" in .*) ;; *.md) ;; esac` in bash matches `*` against `/` too,
// so both patterns match at any depth. `path.matchesGlob` does not extend
// a bare `*` across `/`, so the dotfile check here looks at the first path
// segment explicitly, and the markdown check uses `**/*.md` rather than
// `*.md` (which would wrongly reject `PRIVACY.md` at a nested path).
import { matchesGlob } from "node:path";

export function isHousekeepingSubject(subject) {
  return /^(ci|docs|internal|refactor|test|tests):/.test(subject);
}

export function isHousekeepingPath(path) {
  const firstSegment = path.split("/")[0];
  return firstSegment.startsWith(".") || matchesGlob(path, "**/*.md");
}

// Mirrors this repo's own .github/lanes.conf declaration only — first
// matching `code`/`docs` glob rule wins, in file order — not a general
// implementation of the mikelward/lanes engine.
export function lanesConfLane(path, rules) {
  for (const [lane, glob] of rules) {
    if (matchesGlob(path, glob)) return lane;
  }
  return null;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [mode, value] = process.argv.slice(2);
  if (mode === "subject") {
    process.exit(isHousekeepingSubject(value) ? 0 : 1);
  } else if (mode === "path") {
    process.exit(isHousekeepingPath(value) ? 0 : 1);
  } else {
    console.error("usage: node release-worthiness.mjs subject|path <value>");
    process.exit(2);
  }
}
