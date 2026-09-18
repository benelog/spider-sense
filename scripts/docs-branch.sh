#!/bin/sh
# Cuts the docs branch of a release: docs/x.y.z from the tag vx.y.z, with the
# manual component versioned as x.y.z instead of main, pushed to origin.
# The docs site builds every docs/* branch beside main (see antora-playbook.yml).
set -e
version=$1
if [ -z "$version" ]; then
  echo "usage: $0 x.y.z" >&2
  exit 1
fi
worktree=$(mktemp -d)
git worktree add -q -b "docs/$version" "$worktree" "v$version"
sed -i -e "s/^version: .*/version: $version/" -e '/^display_version: /d' -e '/^prerelease: /d' "$worktree/manual/antora.yml"
git -C "$worktree" commit -qam "Publish the $version manual as a versioned component"
git -C "$worktree" push -q origin "docs/$version"
git worktree remove "$worktree"
echo "docs/$version pushed"
