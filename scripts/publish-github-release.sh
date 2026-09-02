#!/usr/bin/env bash
#
# Publishes the signed release bundle as a GitHub prerelease. Called from the
# `Publish a GitHub release` step of `deploy` in `.github/workflows/ci.yml`,
# which owns the gating and the comment explaining why the step exists.
#
# A separate file rather than an inline `run:` block because `deploy` never
# executes on a pull request — it is main-only by `if:` and by
# `environment: production` — so an inline body could only be verified by
# merging it. Here `scripts/publish_github_release_test.py` drives it against
# a stubbed `gh` and a throwaway git repository, and CI runs that on every PR.
#
# Reads from the environment: GH_TOKEN, HEAD_SHA, RELEASE_NOTES, RUNNER_TEMP.
# Expects to run from the repository root of an unshallow checkout, with the
# bundle at app/build/outputs/bundle/release/app-release.aab.

set -euo pipefail

# Named by versionCode, which is the identifier this repo
# already uses to ask for a build ("need versionCode 512 or
# higher"), and `git rev-list --count HEAD` is that identifier's
# own definition — the same command `app/build.gradle.kts` runs.
# So nothing about the version scheme is restated here: no base
# version to keep in step, no versionName format to reassemble.
# The bundle carries its own versionName either way.
#
# Depends on the unshallow checkout above (fetch-depth: 0): a
# shallow count is silently short, and the tag would then name a
# versionCode no APK carries.
code=$(git rev-list --count HEAD)
tag="v${code}"
name="typelauncher-${code}.aab"
asset="${RUNNER_TEMP}/${name}"
cp app/build/outputs/bundle/release/app-release.aab "$asset"

# Same text as the Play "What's new" card, already truncated to
# Play's cap upstream. Through a file rather than an argument:
# the subjects are commit-authored and must never be parsed as
# options.
notes="${RUNNER_TEMP}/gh-release-notes.md"
{
  printf '%s\n\n' "${RELEASE_NOTES}"
  printf 'versionCode %s, built from %s.\n\n' "$code" "$HEAD_SHA"
  # Says what the notes cover, because they are not a changelog of
  # everything new in the bundle: `Build release notes` measures
  # from the last run that actually published to Play, and falls
  # back to this push alone when there is none — so a push that
  # failed before `deploy` has its subjects omitted here as well as
  # from the Play card. The commit is named above either way, and
  # `git log` from it is the complete answer.
  printf "Notes list this push's release-worthy commits — the same set as the Play \"What's new\" card, not a full changelog of the bundle.\n\n"
  printf 'The attached bundle is what goes to Play, not an installable APK.\n'
} > "$notes"

# Idempotent: a re-run, or a workflow_dispatch on a tip already
# released, finds its own release rather than failing on the
# existing tag.
#
# It uploads only when the asset is absent, and never with
# `--clobber`: that deletes the existing asset before sending
# the new one, so a transient failure mid-upload would leave
# the release with no bundle at all — `gh release upload`'s own
# documentation warns the original is then unrecoverable. And
# there is nothing to gain by replacing it: the commit count
# strictly increases along main, so a tag names exactly one
# commit and an asset already under it is this same build. The
# case idempotency exists for is the opposite one — a first
# attempt that created the release and died before its upload
# landed.
if gh release view "$tag" >/dev/null 2>&1; then
  # The asset's *state*, not just its name. An upload that fails
  # partway can leave GitHub holding an empty asset under the
  # intended name — the release-asset API documents this and says
  # to delete it and retry — so a name-only check would call a
  # broken upload done and publish an unusable bundle. Only
  # `uploaded` counts as present.
  #
  # Its own command rather than a pipeline, because under
  # `pipefail` a failed `gh` would read as "asset missing"; and
  # the name reaches jq through the environment rather than
  # spliced into the program.
  state=$(ASSET_NAME="$name" gh release view "$tag" --json assets \
    --jq '.assets[] | select(.name == env.ASSET_NAME) | .state')
  case "$state" in
    uploaded)
      : # already there, and whole
      ;;
    "")
      gh release upload "$tag" "$asset"
      ;;
    *)
      # Some other state means the bundle under that name is not
      # usable, so there is nothing to preserve and `--clobber`'s
      # delete-then-upload is the right move — the one case it is.
      gh release upload "$tag" "$asset" --clobber
      ;;
  esac
  # `--draft=false` because `gh release create` with an asset
  # creates the release as a draft, uploads, and only then
  # publishes it — so a create interrupted mid-upload leaves an
  # invisible draft that no amount of re-running would publish
  # on its own. Last in the branch, after the upload, so the
  # release never becomes visible without its bundle.
  gh release edit "$tag" \
    --title "versionCode ${code}" --notes-file "$notes" \
    --prerelease --draft=false
else
  gh release create "$tag" "$asset" \
    --title "versionCode ${code}" --notes-file "$notes" \
    --prerelease --target "$HEAD_SHA"
fi
