#!/usr/bin/env bash
# download_paper.sh — Download Paper server jars for NMS scanner.
#
# Usage:
#   bash tools/download_paper.sh                # list available versions
#   bash tools/download_paper.sh 1.21.11        # download specific version
#   bash tools/download_paper.sh all            # download all supported versions
#
# Downloads go to paper-jars/ (gitignored by default).
# Versions not in Paper's API (pre-1.13) fall back to other sources.

set -euo pipefail
cd "$(dirname "$0")/.."
OUTDIR="paper-jars"
mkdir -p "$OUTDIR"

# Map: version → Paper download URL (or build number)
# Paper API: https://api.papermc.io/v2/projects/paper/versions/<version>/builds/<build>/downloads/paper-<version>-<build>.jar
# We use the latest build for each version.
declare -A BUILDS
BUILDS["1.21.11"]="153"
BUILDS["1.21.4"]="82"
BUILDS["1.20.4"]="496"
BUILDS["1.19.4"]="550"
BUILDS["1.18.2"]="388"
BUILDS["1.17.1"]="411"

# For older versions (Paper didn't exist; use legacy Spigot/Paper)
# BUILDS["1.16.5"]=""  # Paper 1.16.5 build numbers
# BUILDS["1.12.2"]=""  # Very old, may need special handling

list_versions() {
    echo "Available versions:"
    for v in "${!BUILDS[@]}"; do
        url="https://api.papermc.io/v2/projects/paper/versions/$v/builds/${BUILDS[$v]}/downloads/paper-$v-${BUILDS[$v]}.jar"
        echo "  $v → $url"
    done
    echo ""
    echo "Usage: bash tools/download_paper.sh <version>"
    echo "       bash tools/download_paper.sh all"
}

download() {
    local v="$1"
    local b="${BUILDS[$v]:-}"
    if [ -z "$b" ]; then
        echo "ERROR: no build number for version $v"
        return 1
    fi
    local url="https://api.papermc.io/v2/projects/paper/versions/$v/builds/$b/downloads/paper-$v-$b.jar"
    local out="$OUTDIR/paper-$v.jar"
    if [ -f "$out" ]; then
        echo "  already exists: $out"
        return 0
    fi
    echo "  downloading paper-$v..."
    if curl -sL -o "$out" "$url" --fail; then
        echo "  → $out ($(stat -c%s "$out" | numfmt --to=iec 2>/dev/null || stat -f%z "$out" 2>/dev/null || echo "?"))"
    else
        echo "  ERROR: failed to download $url"
        rm -f "$out"
        return 1
    fi
}

if [ $# -eq 0 ]; then
    list_versions
    exit 0
fi

if [ "$1" = "all" ]; then
    for v in "${!BUILDS[@]}"; do
        download "$v"
    done
else
    download "$1"
fi

echo ""
echo "Done. Now run scan:"
echo "  python3 tools/scan_nms.py --paper paper-jars/paper-<version>.jar"
