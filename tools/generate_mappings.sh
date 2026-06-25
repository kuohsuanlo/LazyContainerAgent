#!/usr/bin/env bash
# generate_mappings.sh — Download Mojang mapping files for all supported versions,
# extract NMS class/method names, and output NmsRegistry.java entries.
#
# Usage: bash tools/generate_mappings.sh
# Output: stdout contains ready-to-paste NmsRegistry.register(...) calls.

set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p mojang-jars/mappings

VERSIONS=("1.16.5" "1.17.1" "1.18.2" "1.19.4" "1.20.4" "1.21.11")
# 1.12.2 excluded: ContainerHelper does not exist in that version

get_version_json() {
  local v="$1"
  curl -s "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json" \
    2>/dev/null | python3 -c "
import json,sys
d=json.load(sys.stdin)
for v0 in d.get('versions',[]):
    if v0['id']=='$v': print(v0['url'])" 2>/dev/null
}

get_mapping_url() {
  local vjson="$1"
  curl -s "$vjson" 2>/dev/null | python3 -c "
import json,sys
d=json.load(sys.stdin)
m=d.get('downloads',{}).get('server_mappings',{}).get('url','')
print(m)" 2>/dev/null
}

# Extract key mapping entries
extract_class_name() {
  grep -E "^$1 -> " | sed 's/.* -> //' | sed 's/://'
}

for v in "${VERSIONS[@]}"; do
  echo "=== $v ===" >&2
  map_file="mojang-jars/mappings/$v.txt"
  if [ ! -f "$map_file" ]; then
    vjson=$(get_version_json "$v")
    [ -z "$vjson" ] && { echo "  no version json for $v" >&2; continue; }
    map_url=$(get_mapping_url "$vjson")
    [ -z "$map_url" ] && { echo "  no mapping url for $v" >&2; continue; }
    echo "  downloading mapping..." >&2
    curl -sL -o "$map_file" "$map_url"
    echo "  $(wc -l < "$map_file") lines" >&2
  fi

  # Extract obfuscated class names
  CH=$(grep -E "^net\.minecraft\.world\.ContainerHelper -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  TVI=$(grep -E "^net\.minecraft\.world\.level\.storage\.TagValueInput -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  TVO=$(grep -E "^net\.minecraft\.world\.level\.storage\.TagValueOutput -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  VI=$(grep -E "^net\.minecraft\.world\.level\.storage\.ValueInput -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  VO=$(grep -E "^net\.minecraft\.world\.level\.storage\.ValueOutput -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  CT=$(grep -E "^net\.minecraft\.nbt\.CompoundTag -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  TAG=$(grep -E "^net\.minecraft\.nbt\.Tag -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  LT=$(grep -E "^net\.minecraft\.nbt\.ListTag -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  NNL=$(grep -E "^net\.minecraft\.core\.NonNullList -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  PR=$(grep -E "^net\.minecraft\.util\.ProblemReporter -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  IS=$(grep -E "^net\.minecraft\.world\.item\.ItemStack -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  MS=$(grep -E "^net\.minecraft\.server\.MinecraftServer -> " "$map_file" | sed 's/.* -> //' | sed 's/://')
  RA=$(grep -E "^net\.minecraft\.core\.RegistryAccess -> " "$map_file" | sed 's/.* -> //' | sed 's/://')

  if [ -z "$CH" ]; then
    echo "// $v: ContainerHelper not found (pre-1.13 or missing mapping)" >&2
    continue
  fi

  # Extract method names and descriptors from the mapping
  # Format: "    mojmap_name(mojmap_params)mojmap_ret -> obf_name"
  LOAD_LINE=$(grep "loadAllItems(" "$map_file" | grep "ContainerHelper" | head -1)
  SAVE2_LINE=$(grep -E "saveAllItems\(.*NonNullList\)" "$map_file" | grep "ContainerHelper" | head -1)
  SAVE3_LINE=$(grep -E "saveAllItems\(.*boolean\)" "$map_file" | grep "ContainerHelper" | head -1)

  # Convert method descriptors from mojmap to internal form
  # We need the INTERNAL descriptor (Lnet/minecraft/...; format)
  # This is harder to get from the mapping file without parsing the actual class

  # Output version info
  echo "// $v — ContainerHelper obf=$CH, TagValueInput obf=$TVI"
  echo ""
done

echo ""
echo "// ========================================================="
echo "// Manual mapping required: extract method descriptors directly from"
echo "// the obfuscated jar using: javap -p -cp server.jar \$CH"
echo "// ========================================================="
