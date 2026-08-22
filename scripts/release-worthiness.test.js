// Regression coverage for the Codex P2 on PR #646: nothing verified that a
// PRIVACY.md-only change is both docs-only (the lanes.conf CI lane) and
// non-release-worthy (the notes generator), or that the two stay in sync.
// The suite's own failure mode is a false pass — a matcher that never
// actually excludes anything looks the same as one that does — so every
// case here asserts the concrete verdict, not just "no throw".
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  isHousekeepingSubject,
  isHousekeepingPath,
  lanesConfLane,
} from "./release-worthiness.mjs";

describe("isHousekeepingSubject", () => {
  for (const prefix of ["ci", "docs", "internal", "refactor", "test", "tests"]) {
    it(`treats a \`${prefix}:\` subject as housekeeping`, () => {
      assert.equal(isHousekeepingSubject(`${prefix}: something`), true);
    });
  }

  it("treats a bare subject as release-worthy", () => {
    assert.equal(isHousekeepingSubject("Add a new launcher feature"), false);
  });

  it("does not match a prefix as a substring mid-subject", () => {
    assert.equal(isHousekeepingSubject("refactoring the docs pipeline"), false);
  });
});

describe("isHousekeepingPath", () => {
  it("treats PRIVACY.md as housekeeping — the case this PR fixes", () => {
    assert.equal(isHousekeepingPath("PRIVACY.md"), true);
  });

  it("treats any top-level dotfile or dotdir path as housekeeping", () => {
    assert.equal(isHousekeepingPath(".github/workflows/android-ci.yml"), true);
    assert.equal(isHousekeepingPath(".gitignore"), true);
  });

  it("treats any *.md path as housekeeping regardless of depth", () => {
    assert.equal(isHousekeepingPath("SPEC.md"), true);
    assert.equal(isHousekeepingPath("docs/nested/README.md"), true);
  });

  it("treats app source as release-worthy", () => {
    assert.equal(
      isHousekeepingPath("app/src/main/java/app/typelauncher/MainActivity.kt"),
      false,
    );
  });
});

describe("lanesConfLane against this repo's own .github/lanes.conf", () => {
  // Parses only the `code`/`docs` glob lines this test cares about — not a
  // general lanes.conf parser. See mikelward/lanes for the real engine.
  const rules = readFileSync(new URL("../.github/lanes.conf", import.meta.url), "utf8")
    .split("\n")
    .map((line) => line.match(/^(code|docs)\s+(\S+)/))
    .filter(Boolean)
    .map((m) => [m[1], m[2]]);

  it("found the two declared rules", () => {
    assert.deepEqual(rules, [
      ["code", "app/**"],
      ["docs", "**/*.md"],
    ]);
  });

  it("classifies PRIVACY.md as docs — the case this PR fixes", () => {
    assert.equal(lanesConfLane("PRIVACY.md", rules), "docs");
  });

  it("classifies app source as code", () => {
    assert.equal(
      lanesConfLane("app/src/main/java/app/typelauncher/MainActivity.kt", rules),
      "code",
    );
  });

  it("classifies markdown under app/ as code — order matters", () => {
    assert.equal(lanesConfLane("app/README.md", rules), "code");
  });

  it("both classifiers agree on PRIVACY.md", () => {
    assert.equal(lanesConfLane("PRIVACY.md", rules), "docs");
    assert.equal(isHousekeepingPath("PRIVACY.md"), true);
  });
});
