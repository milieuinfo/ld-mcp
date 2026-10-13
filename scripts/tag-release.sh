#!/usr/bin/env bash
# Releases ld-mcp from this machine (the organisation has no GitHub Actions): checks the repository,
# builds and tests the commit to be tagged in a fresh git worktree (never the working tree, so that
# uncommitted changes or local files cannot end up in the jar), and only if everything is green tags
# it, pushes the tag and creates the GitHub release with ld-mcp-vocab.jar and its SHA-256. Then it
# sets all poms to the next development version, commits that and pushes it.
#
#   scripts/tag-release.sh <version> [--next <version>] [--dry-run]
#       e.g. scripts/tag-release.sh 0.4.0          (next: 0.4.1-SNAPSHOT)
#            scripts/tag-release.sh 0.4.0 --next 0.5.0
#
# --dry-run does the checks, the build and the tests, and stops before tagging: nothing is pushed,
# published or changed. Needs git, mvn (Java 21), sha256sum, unzip and gh (logged in, see
# gh auth status).
set -euo pipefail

fail() { echo "tag-release: $*" >&2; exit 1; }

version=""
next=""
dry_run=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run="--dry-run"; shift ;;
        --next) [[ $# -ge 2 ]] || fail "--next needs a version"; next="$2"; shift 2 ;;
        -*) fail "unknown option '$1'" ;;
        *) [[ -z "$version" ]] || fail "unexpected argument '$1'"; version="$1"; shift ;;
    esac
done
[[ -n "$version" ]] || fail "usage: scripts/tag-release.sh <version> [--next <version>] [--dry-run]"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must look like 1.2.3, not '$version'"
if [[ -z "$next" ]]; then
    IFS=. read -r major minor patch <<< "$version"
    next="$major.$minor.$((patch + 1))"
fi
next="${next%-SNAPSHOT}"
[[ "$next" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "--next must look like 1.2.3, not '$next'"
[[ "$(printf '%s\n%s\n' "$version" "$next" | sort -V | tail -1)" == "$next" && "$next" != "$version" ]] \
    || fail "the next version ($next) must be higher than $version"
next="$next-SNAPSHOT"
tag="v$version"

for tool in git mvn sha256sum unzip gh; do
    command -v "$tool" >/dev/null || fail "$tool is not installed"
done

cd "$(git rev-parse --show-toplevel)"
branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "main" ]] || fail "on branch '$branch'; releases are tagged on main"
git diff --quiet && git diff --cached --quiet || fail "uncommitted changes in tracked files"

# The modules must name the root's version as their parent, or they build against an old parent
# from the local Maven repository (and the jar gets the wrong version).
pom_version() { awk '/<parent>/{p=1} /<\/parent>/{p=0} !p && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$1"; }
parent_version() { awk '/<parent>/{p=1} p && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$1"; }
current="$(pom_version pom.xml)"
for module in ld-mcp-core ld-mcp-vocab; do
    [[ "$(parent_version "$module/pom.xml")" == "$current" ]] \
        || fail "$module/pom.xml names parent $(parent_version "$module/pom.xml"), the root pom is $current"
done

git fetch --quiet --tags origin main
commit="$(git rev-parse HEAD)"
[[ "$commit" == "$(git rev-parse origin/main)" ]] || fail "main differs from origin/main; push or pull first"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    fail "tag $tag exists already"
fi
if [[ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]]; then
    fail "tag $tag exists already on origin"
fi
if [[ "$dry_run" != "--dry-run" ]]; then
    gh auth status >/dev/null 2>&1 || fail "gh is not logged in (gh auth login)"
    if gh release view "$tag" >/dev/null 2>&1; then
        fail "a GitHub release $tag exists already"
    fi
fi

# Build and test the commit in a worktree of its own; removed again whatever happens.
work="$(mktemp -d)"
cleanup() {
    git worktree remove --force "$work/src" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
git worktree add --quiet --detach "$work/src" "$commit"
echo "building $(git rev-parse --short "$commit") as $version in $work/src …"
if ! (
    cd "$work/src"
    mvn -B -q versions:set -DnewVersion="$version" -DgenerateBackupPoms=false
    mvn -B package
) > "$work/build.log" 2>&1; then
    tail -40 "$work/build.log" >&2
    fail "build or tests failed (end of the log above)"
fi

jar="$work/src/ld-mcp-vocab/target/ld-mcp-vocab.jar"
[[ -f "$jar" ]] || fail "no jar built at $jar"
built="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Version: //p')"
[[ "$built" == "$version" ]] || fail "the jar reports version '$built', not '$version'"
mkdir "$work/out"
cp "$jar" "$work/out/ld-mcp-vocab.jar"
(cd "$work/out" && sha256sum ld-mcp-vocab.jar > ld-mcp-vocab.jar.sha256)
tests="$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures' "$work/build.log" | grep -v ' -- in ' \
    | awk -F'[:,]' '{t+=$2} END {print t+0}')"
echo "built and tested ($tests tests): $(cat "$work/out/ld-mcp-vocab.jar.sha256")"

if [[ "$dry_run" == "--dry-run" ]]; then
    echo "dry run: would tag $(git rev-parse --short "$commit") as $tag, push it, publish the release,"
    echo "         and set the poms from $current to $next"
    exit 0
fi

git tag -a "$tag" -m "$tag" "$commit"
git push --quiet origin "$tag"
if ! gh release create "$tag" "$work/out/ld-mcp-vocab.jar" "$work/out/ld-mcp-vocab.jar.sha256" \
        --title "$tag" --generate-notes --verify-tag; then
    keep="$(mktemp -d)"
    cp "$work/out/"* "$keep/"
    fail "tag $tag is pushed, but the release could not be created; the files are in $keep; retry with:
  gh release create $tag $keep/ld-mcp-vocab.jar $keep/ld-mcp-vocab.jar.sha256 --title $tag --generate-notes --verify-tag"
fi
echo "released $tag: $(gh release view "$tag" --json url --jq .url)"

# The next development version, in all poms (versions:set changes the modules through their parent).
mvn -B -q versions:set -DnewVersion="$next" -DgenerateBackupPoms=false
for pom in pom.xml ld-mcp-core/pom.xml ld-mcp-vocab/pom.xml; do
    found="$([[ "$pom" == pom.xml ]] && pom_version "$pom" || parent_version "$pom")"
    [[ "$found" == "$next" ]] || fail "the release is out, but $pom is at $found instead of $next; fix and commit it by hand"
done
git add pom.xml ld-mcp-core/pom.xml ld-mcp-vocab/pom.xml
git commit -q -m "ld-mcp $next"
git push --quiet origin main || fail "the poms are at $next and committed, but the push failed; push main by hand"
echo "poms at $next, committed and pushed"
