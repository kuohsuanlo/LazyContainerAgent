#!/usr/bin/env bash
# Automated local runtime integration test for LazyContainerAgent.
#
# Usage:
#   ./tools/test_local.sh [--paper-version 26.2] [--shadow-only]
#
# Downloads a Paper server, builds the agent, starts the server in
# shadow mode, applies a simple container load via the RCON console,
# checks the log for errors/mismatches, then reports pass/fail.
#
# ponytail: uses rcon for simplicity; a full player simulation needs
# something like mineflayer.  Add when hopper/furnace flow needs testing.

set -euo pipefail
cd "$(dirname "$0")/.."

# ── config ──
PAPER_VERSION="${PAPER_VERSION:-26.2}"
PAPER_URL="https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/latest/downloads/paper-${PAPER_VERSION}.jar"
RCON_PORT=25575
RCON_PASS="lazytest"
AGENT_JAR="target/LazyContainerAgent.jar"
SERVER_DIR="tmp-server-$$"
SERVER_JAR="paper-${PAPER_VERSION}.jar"
SHADOW_ONLY="${SHADOW_ONLY:-false}"

cleanup() {
  echo "=== cleanup ==="
  if [ -f "${SERVER_DIR}/server.pid" ]; then
    kill "$(cat "${SERVER_DIR}/server.pid")" 2>/dev/null || true
  fi
  sleep 2
  rm -rf "${SERVER_DIR}"
}
trap cleanup EXIT

echo "== 1. build agent =="
mvn -B -q package

echo "== 2. download Paper ${PAPER_VERSION} =="
mkdir -p "${SERVER_DIR}"
if [ ! -f "${SERVER_DIR}/${SERVER_JAR}" ]; then
  wget -q -O "${SERVER_DIR}/${SERVER_JAR}" "${PAPER_URL}"
fi

echo "== 3. configure server =="
cd "${SERVER_DIR}"
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
server-port=25566
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASS}
level-seed=42
max-players=0
online-mode=false
PROPS

echo "== 4. start server with shadow mode =="
java -javaagent:"../${AGENT_JAR}" \
     -Dlazycontainer.shadow=true \
     -Dlazycontainer.verbose=true \
     -Dlazycontainer.dump=true \
     -jar "${SERVER_JAR}" --nogui &
SERVER_PID=$!
echo $SERVER_PID > server.pid

# Wait for server to finish starting (look for "Done" in log)
echo "== waiting for server startup =="
tail -f logs/latest.log 2>/dev/null &
TAIL_PID=$!
for i in $(seq 1 120); do
  if grep -q "Done" logs/latest.log 2>/dev/null; then
    echo "server ready after ${i}s"
    break
  fi
  sleep 1
done
kill $TAIL_PID 2>/dev/null || true

# ── Phase A: Place and trigger containers ──
echo "== 5. trigger container operations =="

# Place a chest using setblock, put items in it, then simulate chunk unload/reload
RCON="mcrcon -H 127.0.0.1 -P ${RCON_PORT} -p ${RCON_PASS}"

# Wait for rcon to be ready
sleep 3
${RCON} "say LazyContainerAgent shadow test starting" 2>/dev/null || true

# Place containers
${RCON} "setblock 0 60 0 minecraft:chest" 2>/dev/null || true
${RCON} "setblock 1 60 0 minecraft:barrel" 2>/dev/null || true
${RCON} "setblock 2 60 0 minecraft:shulker_box" 2>/dev/null || true
${RCON} "setblock 3 60 0 minecraft:hopper" 2>/dev/null || true
${RCON} "setblock 4 60 0 minecraft:dispenser" 2>/dev/null || true
${RCON} "setblock 5 60 0 minecraft:furnace" 2>/dev/null || true

# Put items in first chest
${RCON} "data merge block 0 60 0 {Items:[{Slot:0,id:\"minecraft:diamond\",Count:42},{Slot:1,id:\"minecraft:netherite_sword\",Count:1,tag:{Damage:10}}]}" 2>/dev/null || true

# Force chunk reload (save + regen area)
${RCON} "save-all" 2>/dev/null || true
sleep 2
${RCON} "forceload remove 0 0" 2>/dev/null || true
sleep 1
${RCON} "forceload add 0 0" 2>/dev/null || true

# Open the chest (triggers ensure)
${RCON} "data get block 0 60 0" 2>/dev/null || true

echo "= waiting for logs to flush ="
sleep 5

# ── Phase B: Check results ──
echo "== 6. check results =="

# Check for errors in the log
echo "--- agent log lines ---"
grep -i '\[LazyContainer\]' logs/latest.log 2>/dev/null || echo "(no agent log lines found)"

echo "--- last 20 server log ---"
tail -20 logs/latest.log 2>/dev/null

# Extract key metrics
STASH=$(grep -oP 'stash=\K\d+' logs/latest.log 2>/dev/null | tail -1)
ENSURE=$(grep -oP 'ensure=\K\d+' logs/latest.log 2>/dev/null | tail -1)
MISMATCH=$(grep -oP 'shadowMismatch=\K\d+' logs/latest.log 2>/dev/null | tail -1)
ERRORS=$(grep -ciE '(error|exception|verifyerror|nosuchmethod|classnotfound)' logs/latest.log 2>/dev/null || true)

echo "--- metrics ---"
echo "stash=$STASH ensure=$ENSURE shadowMismatch=$MISMATCH errors=$ERRORS"

PASS=true
if [ "${MISMATCH:-0}" != "0" ]; then
  echo "FAIL: shadowMismatch=${MISMATCH} (should be 0)"
  PASS=false
fi
if [ "${ERRORS:-0}" -gt 0 ]; then
  echo "FAIL: ${ERRORS} errors in log"
  grep -iE '(error|exception|verifyerror|nosuchmethod|classnotfound)' logs/latest.log 2>/dev/null | head -10
  PASS=false
fi

cd ..

if [ "$PASS" = true ]; then
  echo "=== ALL SHADOW TESTS PASSED ==="
  exit 0
else
  echo "=== SHADOW TESTS FAILED ==="
  exit 1
fi
