#!/usr/bin/env bash
# Tags a release of ld-mcp. Only tags and pushes the tag: the workflow
# .github/workflows/release.yml then sets the version, builds and tests from the tag, and publishes
# the jar with its SHA-256 checksum. Nothing is built from the working tree.
#
#   scripts/tag-release.sh <version> [--dry-run]      e.g. scripts/tag-release.sh 0.2.2
set -euo pipefail

fail() { echo "tag-release: $*" >&2; exit 1; }

version="${1:-}"
dry_run="${2:-}"
[[ -n "$version" ]] || fail "usage: scripts/tag-release.sh <version> [--dry-run]"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must look like 1.2.3, not '$version'"
[[ -z "$dry_run" || "$dry_run" == "--dry-run" ]] || fail "unknown option '$dry_run'"
tag="v$version"

cd "$(git rev-parse --show-toplevel)"
branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "main" ]] || fail "on branch '$branch'; releases are tagged on main"
git diff --quiet && git diff --cached --quiet || fail "uncommitted changes in tracked files"

git fetch --quiet --tags origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] \
    || fail "main differs from origin/main; push or pull first"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    fail "tag $tag exists already"
fi
if [[ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]]; then
    fail "tag $tag exists already on origin"
fi

if [[ "$dry_run" == "--dry-run" ]]; then
    echo "would tag $(git rev-parse --short HEAD) as $tag and push it to origin"
    exit 0
fi
git tag -a "$tag" -m "$tag"
git push origin "$tag"
echo "pushed $tag; the release workflow builds and publishes it"
