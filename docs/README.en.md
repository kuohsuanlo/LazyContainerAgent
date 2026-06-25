# LazyContainerAgent

**English** ｜ [繁體中文](../README.md)

> **A Java agent that lazily deserializes container items, and writes untouched containers back byte-for-byte.**
> It removes two pieces of wasted work: unpacking every chest's items from NBT on chunk load, and re-packing them on unload.

⚠️ **This is a Java agent, NOT a plugin** — attach it with `-javaagent:`. **Do not drop it in `plugins/`**.

---

## Quick start

**1. Drop the jar** somewhere the node can see it (next to your server jar is easiest).

**2. Add the flag** on the `java` line, **before** `-jar`:

```bash
java ... \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar <your Paper>.jar nogui
```

**3. Validate first** — run with `shadow=true` for a few days until `shadowMismatch` stays **0**, then remove the flag for real performance.

**4. Rollback** — remove the `-javaagent` flag and restart → back to 100% vanilla, **no data migration needed**.

---

## Version support

| Version | Java | Status |
|---------|------|--------|
| Paper 1.21.11 | Java 21 | ✅ **verified** — tested on real server |
| Paper 1.20.4 | Java 17 | 🟡 mapping defined |
| Paper 1.19.4 | Java 17 | 🟡 mapping defined |
| Paper 1.18.2 | Java 17 | 🟡 mapping defined |
| Paper 1.17.1 | Java 16 | 🟡 mapping defined |
| Paper 1.16.5 | Java 8  | 🟡 mapping defined (Spigot) |
| 1.12.2 | Java 8  | ❌ not supported (no ContainerHelper) |

Auto-detection reads `MinecraftServer` classfile major + constant pool scan.
Override with `-Dlazycontainer.version=1.21.11`. Status definitions in [`docs/PROGRESS.md`](PROGRESS.md).

---

## File map

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerAgentMain.java       premain / agentmain entry
  LazyContainerRuntime.java         JDK-only counters + verbose logger
  ContainerHelperInterceptor.java   interceptor core (pending map + ensure + shadow roundtrip)
  LazyContainerTransformer.java     ASM: ContainerHelper 2 methods + leaf getItems guard
  VersionDetector.java              MC version detection (classfile major + CP scan)
  NmsRegistry.java                  Per-version NMS mapping table
  DetachManager.java                safe detach/reload (flush pending + unregister + retransform)
template/                           v1.0 splice reference (kept for history)
build.sh                            build script
```

---

## How it works

```
Chunk load:
  ContainerHelper.loadAllItems(input, items)
    → input instanceof TagValueInput?
        ├─ yes → stash raw "Items" tag → skip decode
        └─ no  → normal decode

Container access:
  getItems()/getContents()
    → ContainerHelperInterceptor.ensure(this)
        → find items field → pending? → materialize via ContainerHelper.loadAllItems

Chunk save:
  ContainerHelper.saveAllItems(output, items)
    → pending? + canWriteRaw?
        ├─ yes → raw writeback
        └─ no  → normal encode (ensure first if needed)
```

Shadow mode performs an additional eager roundtrip (parse→encode) before writing raw.
If byte-identical or benign reorder (same items, different list order) → write raw.
If real mismatch → write eager version (safe).

---

## Safety guarantees

- Untouched containers → write back the exact bytes read (byte-identical)
- Touched containers → ensure + normal encode (identical to vanilla)
- Shadow mode → output is mathematically byte-identical to vanilla (mismatch → automatically writes eager)
- Only container `Items` are affected — never terrain, blocks, entities, lighting, or other BEs

---

## Flags

| Flag | Effect |
|------|--------|
| `-Dlazycontainer.shadow=true` | Shadow validation mode (compare before raw write, fallback to eager on mismatch) |
| `-Dlazycontainer.verbose=true` | Background thread prints counters periodically |
| `-Dlazycontainer.verbose.ms=8000` | Verbose interval in ms (default 30000) |
| `-Dlazycontainer.dump=true` | Dump raw/eager SNBT on mismatch/benign reorder (first 30) |
| `-Dlazycontainer.version=1.21.11` | Override MC version auto-detection |

---

## Build

```bash
bash build.sh        # JDK 21+; outputs target/LazyContainerAgent.jar
```

No `nms-lib/` directory or template compilation needed.

### CI artifact naming

GitHub Actions builds on JDK 21, 22, and 23, producing:

```
LazyContainerAgent-<version>-jdk21.jar
LazyContainerAgent-<version>-jdk22.jar
LazyContainerAgent-<version>-jdk23.jar
```

Version rules:
- **Tag build** (`git tag v1.0`) → produces `LazyContainerAgent-1.0-jdk21.jar` (`v` stripped)
- **Dev nightly** → `pom.xml` version + short sha, e.g. `LazyContainerAgent-1.1-SNAPSHOT-abc1234-jdk21.jar`

### Release workflow

Use `tools/bump_version.sh` for all version operations:

```bash
# Development: bump to next version
bash tools/bump_version.sh minor        # 1.0 → 1.1-SNAPSHOT, commit without tag

# Ready to release: strip -SNAPSHOT, tag
bash tools/bump_version.sh release      # 1.1-SNAPSHOT → 1.1, tag v1.1
git push && git push --tags             # CI creates the release

# After release: bump to next development cycle
bash tools/bump_version.sh minor        # 1.1 → 1.2-SNAPSHOT
git push
```

**All three JARs are identical** (agent targets Java 21 bytecode). The matrix build
only verifies compatibility across JDK versions. Pick the one that matches your
server's JDK major version:

- Server on JDK 21 → `*-jdk21.jar`
- Server on JDK 22 → `*-jdk22.jar`
- Server on JDK 23 → `*-jdk23.jar`

Run `java -version` to check your server JDK. Any of them works; the suffix
is just for convenience.

---

## Full flag reference

- `-Dlazycontainer.shadow=true` — **Required validation mode before production**. Run for a few days confirming `shadowMismatch=0`, then disable for real performance.
- `-Dlazycontainer.version=1.21.11` — Only needed when auto-detection fails.
