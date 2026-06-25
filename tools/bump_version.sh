#!/usr/bin/env bash
# bump_version.sh — Bump LazyContainerAgent version in pom.xml.
#
# Usage:
#   bash tools/bump_version.sh              # show current version
#   bash tools/bump_version.sh release      # 1.1-SNAPSHOT → 1.1, tag v1.1
#   bash tools/bump_version.sh patch        # 1.0 → 1.0.1-SNAPSHOT
#   bash tools/bump_version.sh minor        # 1.0 → 1.1-SNAPSHOT
#   bash tools/bump_version.sh major        # 1.0 → 2.0.0-SNAPSHOT
#   bash tools/bump_version.sh 1.5.0        # explicit
#
# After `bump_version.sh release`:
#   git push --tags   → trigger CI Create Release

set -euo pipefail
cd "$(dirname "$0")/.."

CURRENT=$(grep -oP '<version>\K[^<]+' pom.xml | head -1)
echo "Current version: $CURRENT"

if [ $# -eq 0 ]; then
    echo "Usage: $0 [release|patch|minor|major|<explicit>]"
    exit 0
fi

# Strip -SNAPSHOT for parsing
BASE="${CURRENT%-SNAPSHOT}"

# Parse as X.Y or X.Y.Z
if [[ "$BASE" =~ ^([0-9]+)\.([0-9]+)(\.([0-9]+))?$ ]]; then
    MAJOR="${BASH_REMATCH[1]}"
    MINOR="${BASH_REMATCH[2]}"
    PATCH="${BASH_REMATCH[4]:-0}"
else
    echo "ERROR: cannot parse version: $CURRENT"
    echo "  expected format: X.Y or X.Y.Z (optional -SNAPSHOT)"
    exit 1
fi

case "${1:-}" in
    release)
        if [[ "$CURRENT" != *-SNAPSHOT ]]; then
            echo "ERROR: release requires a -SNAPSHOT version, got: $CURRENT"
            echo "  Already a release version? Nothing to do."
            exit 1
        fi
        NEW="$BASE"
        sed -i "s|<version>${CURRENT}</version>|<version>${NEW}</version>|" pom.xml
        git add pom.xml
        git commit -m "release: ${CURRENT} → ${NEW}"
        git tag -a "v${NEW}" -m "LazyContainerAgent v${NEW}"
        echo "---"
        echo "Committed and tagged: v${NEW}"
        echo "Now push:  git push && git push --tags"
        echo ""
        echo "After release, bump to next dev version:"
        echo "  bash tools/bump_version.sh minor"
        ;;
    patch)
        NEW_PATCH=$((PATCH + 1))
        if [ "$PATCH" -eq 0 ]; then
            NEW="${MAJOR}.${MINOR}.${NEW_PATCH}-SNAPSHOT"
        else
            NEW="${MAJOR}.${MINOR}.${NEW_PATCH}-SNAPSHOT"
        fi
        sed -i "s|<version>${CURRENT}</version>|<version>${NEW}</version>|" pom.xml
        git add pom.xml
        git commit -m "bump version: ${CURRENT} → ${NEW}"
        echo "---"
        echo "Committed: ${NEW}"
        echo "Now push:  git push"
        ;;
    minor)
        NEW_MINOR=$((MINOR + 1))
        NEW="${MAJOR}.${NEW_MINOR}-SNAPSHOT"
        sed -i "s|<version>${CURRENT}</version>|<version>${NEW}</version>|" pom.xml
        git add pom.xml
        git commit -m "bump version: ${CURRENT} → ${NEW}"
        echo "---"
        echo "Committed: ${NEW}"
        echo "Now push:  git push"
        ;;
    major)
        NEW_MAJOR=$((MAJOR + 1))
        NEW="${NEW_MAJOR}.0.0-SNAPSHOT"
        sed -i "s|<version>${CURRENT}</version>|<version>${NEW}</version>|" pom.xml
        git add pom.xml
        git commit -m "bump version: ${CURRENT} → ${NEW}"
        echo "---"
        echo "Committed: ${NEW}"
        echo "Now push:  git push"
        ;;
    *)
        NEW="${1}"
        if [[ "$NEW" != *-SNAPSHOT ]]; then
            NEW="${NEW}-SNAPSHOT"
        fi
        sed -i "s|<version>${CURRENT}</version>|<version>${NEW}</version>|" pom.xml
        git add pom.xml
        git commit -m "bump version: ${CURRENT} → ${NEW}"
        echo "---"
        echo "Committed: ${NEW}"
        echo "Now push:  git push"
        ;;
esac
