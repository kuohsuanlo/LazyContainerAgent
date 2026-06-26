# Runtime Integration Test Guide

Verify that LazyContainerAgent works correctly on a real Paper server.
Two phases: shadow mode (read-only) then live mode.

---

## Prerequisites

- Paper 26.2 server jar (or target version)
- JDK 25 (for 26.2; adjust per target)
- LazyContainerAgent JAR built from this repo
- A test world (copy your production world or generate fresh)
- `screen` or `tmux` to keep the server running

---

## Phase 1 — Shadow Mode (safe, no data written)

Attach the agent in shadow mode.  Everything is parsed and compared
but **never written back** — if there's a bug, the worst outcome is a
log warning, not data loss.

### 1. Build the agent

```bash
mvn -B package
# JAR at target/LazyContainerAgent.jar
```

### 2. Start the server with shadow mode

```bash
java -javaagent:target/LazyContainerAgent.jar \
     -Dlazycontainer.shadow=true \
     -Dlazycontainer.verbose=true \
     -Dlazycontainer.dump=true \
     -jar paper-26.2.jar --nogui
```

Flags:
- `shadow=true` — safe mode: compare raw vs eager, log mismatches, **write eager**
- `verbose=true` — print stats every 30s
- `dump=true` — dump raw/eager SNBT on mismatch (to `lc-mismatch-*.snbt`)

### 3. Load containers

Join the server, walk around, open chests/barrels/shulkers, let hoppers
run.  Check the server log:

```
[LazyContainer] stash=42 ensure=12 rawSave=0...
[LazyContainer] shadowMismatch=0   ← this should stay 0
```

### 4. Expected results

| Metric | Pass | Investigate |
|--------|------|-------------|
| `stash` > 0 | containers are being lazy-loaded | Agent not active |
| `ensure` > 0 | containers are being materialized on access | — |
| `shadowMismatch=0` | raw matches eager byte-for-byte | Check dump files |
| `rawSave` > 0 | raw write-back is being used (skip encode) | Only in live mode |
| No `VerifyError`/`NoSuchMethodError` | bytecode injection is correct | Check transformer logs |

If `shadowMismatch > 0`:
1. Check the dump files: `ls lc-mismatch-*.snbt`
2. Diff raw vs eager to see what differs
3. If it's a benign reorder (same items/slots, different list order),
   the log says `benignReorder` — this is safe.
4. If it's a real mismatch, the agent should NOT be used in live mode
   on this server version until the issue is fixed.

---

## Phase 2 — Live Mode (actually skips decode/encode)

Once shadow mode passes with `shadowMismatch=0`, test live mode.

### 1. Start the server without shadow

```bash
java -javaagent:target/LazyContainerAgent.jar \
     -Dlazycontainer.dump=true \
     -jar paper-26.2.jar --nogui
```

### 2. Round-trip test (data integrity)

1. Put known items in a chest: e.g. diamond×42, sword{damage:10}, netherite×7
2. Note the coordinates
3. Restart the server
4. Teleport back, open the chest — items should be exactly as placed
5. Repeat 3 times — items must survive repeated lazy-load → raw-writeback cycles

### 3. Hopper / Dispenser / Furnace test

1. Place a hopper under a chest, pointing into a furnace
2. Put items in the chest
3. Verify items flow through hopper → furnace → smelted correctly
4. Restart server, verify furnace contents survived

### 4. Expected results

- All items survive restart (byte-identical)
- `rawSave` counter increments on unload of untouched containers
- No `VerifyError`, `NoSuchMethodError`, or `ClassNotFoundException`
- Container interactions (open, hopper push/pull, hopper minecart) all work

---

## What If Something Fails

1. **VerifyError / NoSuchMethodError on boot**:
   - Wrong Paper version?  Check `-Dlazycontainer.version` matches
   - Wrong JDK version?  Check classfile major compatibility
   - Report with the full error log

2. **shadowMismatch > 0 (not benign reorder)**:
   - Run with `-Dlazycontainer.dump=true`, share the `lc-mismatch-*.snbt`
   - New Paper version with changed NBT format?  May need mapping update

3. **Items lost on restart**:
   - Stop testing immediately, revert to vanilla (remove `-javaagent:`)
   - This should NEVER happen — if it does, file a bug with reproduction steps

---

## Automating (optional)

See `tools/test_local.sh` for a script that automates the shadow
mode test: downloads Paper, starts server, applies load, checks log.
