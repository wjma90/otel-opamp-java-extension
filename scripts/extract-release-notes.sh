#!/usr/bin/env bash

set -euo pipefail

version="${1:?usage: extract-release-notes.sh VERSION [CHANGELOG]}"
changelog="${2:-CHANGELOG.md}"

awk -v version="${version}" '
  index($0, "## [" version "]") == 1 {
    found = 1
    next
  }
  found && /^## \[/ {
    exit
  }
  found {
    print
    emitted = 1
  }
  END {
    if (!found || !emitted) {
      exit 1
    }
  }
' "${changelog}"
