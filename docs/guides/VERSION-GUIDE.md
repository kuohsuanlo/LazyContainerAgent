# Adding a new Minecraft version

LazyContainerAgent uses a `NmsRegistry` mapping table to support multiple
Minecraft versions. Adding a new version is a 3-step process, most of which
can be automated with `tools/scan_nms.py`.

---

## Quick path (automated scanner)

### Prerequisites

- Paper server jar for the target version (e.g., `paper-1.21.4.jar`)
- Python 3.8+

### Step 1: Run the scanner

```bash
python tools/scan_nms.py --paper paper-1.21.4.jar -o /tmp/mapping.java
```

The scanner reads all classes in the jar and outputs a ready-to-use
`NmsRegistry.NmsMapping` builder snippet. It handles both mojmap (1.17+)
and obfuscated (1.12-1.16) jars.

### Step 2: Add to NmsRegistry.java

Open `src/main/java/io/github/kuohsuanlo/lazycontainer/NmsRegistry.java`.

1. Find the `register(...)` calls in the `static {}` block
2. Add a new entry after the existing ones:

```java
// 1.21.4 — added by scan_nms.py on 2025-06-25
register(VersionDetector.McVersion.V1_21_4, new NmsMapping()
    .containerHelper("net/minecraft/world/ContainerHelper")
    .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
    // ... paste the rest from scan_nms.py output
    .containerFieldNames(Arrays.asList("items", "itemStacks")));
```

3. If a `VersionDetector.McVersion` enum entry doesn't exist yet for this
   version, add one in `VersionDetector.java`:

```java
V1_21_4(63, "1.21.4"),  // classfile major 63 = Java 19
```

The classfile major number is printed by the scanner.

### Step 3: Build and smoke test

```bash
bash build.sh
java -javaagent:target/LazyContainerAgent.jar \
     -Dlazycontainer.shadow=true \
     -jar paper-1.21.4.jar nogui
```

Check the startup log for:
```
[LazyContainer] detected MC version: V1_21_4 (classfile major 63)
[LazyContainer] transformer registered [SHADOW mode]
```

Run with `shadow=true` for a few hours and verify `shadowMismatch=0`.

---

## Manual path (when scanner fails)

If the scanner can't find the right classes (e.g., obfuscation changes),
use the manual approach:

### 1. Find ContainerHelper

```bash
# Extract and search for classes with load/save item methods
unzip -l paper.jar | grep -E 'ContainerHelper|ItemHelper' | head -5
javap -p -cp paper.jar net.minecraft.world.ContainerHelper 2>/dev/null
```

Look for:
- Static method `loadAllItems` (or `a` in obfuscated)
  - Parameters: (some tag input, NonNullList)
- Static method `saveAllItems` (or `a`)
  - Parameters: (some tag output, NonNullList) or (some tag output, NonNullList, boolean)

### 2. Find TagValueInput / TagValueOutput

```bash
unzip -l paper.jar | grep -E 'TagValue|ValueInput|ValueOutput' | head -10
```

If these classes don't exist (pre-1.13), the agent falls back to
raw NBT tag handling.

### 3. Find leaf container classes

```bash
unzip -l paper.jar | grep -E 'ChestBlockEntity|BarrelBlockEntity|ShulkerBoxBlockEntity' | head -10
```

For 1.12-style obfuscated jars, look for:
- Classes extending `TileEntity` with an `items` or `inventory` field
- Classes in the `net.minecraft.tileentity` package

### 4. Verify field names in leaf classes

```bash
javap -p -cp paper.jar net.minecraft.world.level.block.entity.ChestBlockEntity
```

Look for `items` or `itemStacks` field of type `NonNullList`. If both
exist, add both to `.containerFieldNames()`.

---

## Mapping fields reference

| NmsRegistry field | How to find it | Required? |
|---|---|---|
| `containerHelper` | Static utility with loadAllItems/saveAllItems | ✅ Required |
| `tagValueInput` | Class with `createGlobal` method | 1.13+ (null for 1.12) |
| `tagValueOutput` | Class with `createWithContext` method | 1.13+ (null for 1.12) |
| `valueInput` | Superclass/interface of TagValueInput | 1.13+ |
| `valueOutput` | Superclass/interface of TagValueOutput | 1.13+ |
| `compoundTag` | NBT compound class | ✅ Required |
| `tag` | Base NBT class | ✅ Required |
| `listTag` | NBT list class | ✅ Required |
| `nonNullList` | List wrapper used by containers | ✅ Required |
| `problemReporter` | Error handling for NBT parsing | 1.13+ |
| `itemStack` | Item stack class | ✅ Required |
| `minecraftServer` | Server singleton | ✅ Required |
| `registryAccess` | Registry for data components | 1.20.5+ |
| `loadAllItems` | Method name + descriptor | ✅ Required |
| `saveAllItems2` | (VOUT, NNL) overload | ✅ Required |
| `saveAllItems3` | (VOUT, NNL, bool) overload | ✅ Required |
| `leafClasses` | Container BE class names | ✅ Required |
| `containerFieldNames` | Items field names in those classes | ✅ Required |

---

## Troubleshooting

### "unknown classfile major N"

The jar uses a newer class file format. Add the version to
`VersionDetector.McVersion`:

```java
V1_XX_X(N, "1.xx.x"),
```

Then update `classfile_major_to_java` and `major_to_version` maps.

### "reflect init failed — ClassNotFoundException"

The reflection cache (`ensureReflectReady` in `ContainerHelperInterceptor`)
can't find NMS classes via context classloader. Usually means the NMS class
name in the mapping is wrong. Verify with `javap`.

### "transform failed for net/minecraft/world/ContainerHelper"

The ASM transformer can't find the expected methods. The method descriptor
in the mapping doesn't match. Verify with:

```bash
javap -p -cp paper.jar net.minecraft.world.ContainerHelper
```

---

## CI integration

When you submit a new version mapping via pull request, the GitHub Actions
workflow automatically:

1. Builds the JAR on JDK 21, 22, and 23
2. Runs the NMS scanner syntax check
3. Uploads build artifacts

Tag a commit with `v1.21.4-alpha` to create a pre-release with all JARs
attached. When stable, tag with `v1.21.4` for a full release.

```bash
git tag v1.21.4-alpha && git push origin v1.21.4-alpha
```
