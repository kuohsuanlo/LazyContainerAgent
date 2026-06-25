#!/usr/bin/env python3
"""
scan_nms.py — Paper server jar → NmsRegistry mapping auto-detection.

Usage:
    python tools/scan_nms.py --paper paper-1.21.11.jar
    python tools/scan_nms.py --paper paper-1.21.11.jar --output mapping.java

Scans a Paper server jar, identifies NMS classes and methods needed by
LazyContainerAgent, and outputs a ready-to-use NmsRegistry.NmsMapping
builder snippet plus a compatibility report.

Supports both mojmap (1.17+) and obfuscated (1.12-1.16) jars.
"""

import argparse
import io
import re
import sys
import zipfile
from pathlib import Path
from typing import Optional


# ──────────────────────────────────────────────
# Minimal class file parser (constant pool only)
# ──────────────────────────────────────────────

def read_u1(b: bytes, pos: int) -> tuple[int, int]:
    return b[pos], pos + 1

def read_u2(b: bytes, pos: int) -> tuple[int, int]:
    return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF), pos + 2

def read_u4(b: bytes, pos: int) -> tuple[int, int]:
    return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF), pos + 4

CONSTANT_Utf8 = 1
CONSTANT_Integer = 3
CONSTANT_Float = 4
CONSTANT_Long = 5
CONSTANT_Double = 6
CONSTANT_Class = 7
CONSTANT_String = 8
CONSTANT_Fieldref = 9
CONSTANT_Methodref = 10
CONSTANT_InterfaceMethodref = 11
CONSTANT_NameAndType = 12
CONSTANT_MethodHandle = 15
CONSTANT_MethodType = 16
CONSTANT_Dynamic = 17
CONSTANT_InvokeDynamic = 18
CONSTANT_Module = 19
CONSTANT_Package = 20


class ClassFile:
    """Minimal class file: name + super + interfaces + methods + fields."""
    def __init__(self, data: bytes):
        self.raw = data
        self.cp: list[Optional[dict]] = []
        self.access_flags = 0
        self.this_class = 0
        self.super_class = 0
        self.interfaces: list[int] = []
        self.methods: list[dict] = []
        self.fields: list[dict] = []
        self._parse(data)

    def _parse(self, b: bytes):
        if len(b) < 8 or b[0:4] != b'\xca\xfe\xba\xbe':
            raise ValueError("Not a valid class file (no CAFEBABE magic)")
        major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF)
        minor = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF)
        self.classfile_major = major
        self.classfile_minor = minor
        pos = 8
        # Constant pool
        cp_count, pos = read_u2(b, pos)
        self.cp = [None] * cp_count
        i = 1
        while i < cp_count:
            tag, pos = read_u1(b, pos)
            entry: dict = {'tag': tag}
            if tag == CONSTANT_Utf8:
                length, pos = read_u2(b, pos)
                entry['str_value'] = b[pos:pos + length].decode('utf-8', errors='replace')
                pos += length
            elif tag in (CONSTANT_Integer, CONSTANT_Float):
                entry['int_value'], pos = read_u4(b, pos)
            elif tag in (CONSTANT_Long, CONSTANT_Double):
                val, pos = read_u4(b, pos)
                val2, pos = read_u4(b, pos)
                entry['long_value'] = (val << 32) | (val2 & 0xFFFFFFFF)
                i += 1  # takes two CP slots
            elif tag == CONSTANT_Class:
                entry['name_index'], pos = read_u2(b, pos)
            elif tag == CONSTANT_String:
                entry['string_index'], pos = read_u2(b, pos)
            elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref):
                entry['class_index'], pos = read_u2(b, pos)
                entry['nat_index'], pos = read_u2(b, pos)
            elif tag == CONSTANT_NameAndType:
                entry['name_index'], pos = read_u2(b, pos)
                entry['descriptor_index'], pos = read_u2(b, pos)
            elif tag == CONSTANT_MethodHandle:
                entry['kind'], pos = read_u1(b, pos)
                entry['index'], pos = read_u2(b, pos)
            elif tag == CONSTANT_MethodType:
                entry['descriptor_index'], pos = read_u2(b, pos)
            elif tag in (CONSTANT_Dynamic, CONSTANT_InvokeDynamic):
                entry['bootstrap_attr_index'], pos = read_u2(b, pos)
                entry['nat_index'], pos = read_u2(b, pos)
            elif tag in (CONSTANT_Module, CONSTANT_Package):
                entry['name_index'], pos = read_u2(b, pos)
            else:
                raise ValueError(f"Unknown constant pool tag {tag} at offset {pos - 1}")
            self.cp[i] = entry
            i += 1

        # Access flags + this + super + interfaces
        self.access_flags, pos = read_u2(b, pos)
        self.this_class, pos = read_u2(b, pos)
        self.super_class, pos = read_u2(b, pos)
        icount, pos = read_u2(b, pos)
        for _ in range(icount):
            idx, pos = read_u2(b, pos)
            self.interfaces.append(idx)

        # Fields
        fcount, pos = read_u2(b, pos)
        for _ in range(fcount):
            f_access, pos = read_u2(b, pos)
            f_name, pos = read_u2(b, pos)
            f_desc, pos = read_u2(b, pos)
            attr_count, pos = read_u2(b, pos)
            for _ in range(attr_count):
                _, pos = read_u2(b, pos)  # attr_name_index
                attr_len, pos = read_u4(b, pos)
                pos += attr_len
            self.fields.append({
                'name_index': f_name,
                'descriptor_index': f_desc,
                'access': f_access,
            })

        # Methods
        mcount, pos = read_u2(b, pos)
        for _ in range(mcount):
            m_access, pos = read_u2(b, pos)
            m_name, pos = read_u2(b, pos)
            m_desc, pos = read_u2(b, pos)
            attr_count, pos = read_u2(b, pos)
            for _ in range(attr_count):
                _, pos = read_u2(b, pos)
                attr_len, pos = read_u4(b, pos)
                pos += attr_len
            self.methods.append({
                'name_index': m_name,
                'descriptor_index': m_desc,
                'access': m_access,
            })

        # Skip remaining attributes (class-level)
        attr_count, pos = read_u2(b, pos)
        for _ in range(attr_count):
            _, pos = read_u2(b, pos)
            attr_len, pos = read_u4(b, pos)
            pos += attr_len

    def cp_string(self, index: int) -> str:
        """Resolve a CP entry to a string (for Utf8, Class, etc.)."""
        if index < 0 or index >= len(self.cp):
            return f"<?{index}>"
        e = self.cp[index]
        if e is None:
            return "<null>"
        if e['tag'] == CONSTANT_Utf8:
            return e.get('str_value', '<?>')
        if e['tag'] == CONSTANT_Class:
            return self.cp_string(e.get('name_index', 0)).replace('/', '.')
        if e['tag'] == CONSTANT_String:
            return self.cp_string(e.get('string_index', 0))
        if e['tag'] in (CONSTANT_Methodref, CONSTANT_Fieldref, CONSTANT_InterfaceMethodref):
            cls = self.cp_string(e.get('class_index', 0))
            nat = self.cp_info(e.get('nat_index', 0))
            return f"{cls}.{nat}"
        return f"<cp[{index}] tag={e['tag']}>"

    def cp_info(self, index: int) -> str:
        """Resolve a NameAndType CP entry to 'name:desc'."""
        if index < 0 or index >= len(self.cp):
            return f"<?{index}>"
        e = self.cp[index]
        if e is None:
            return "<null>"
        if e['tag'] == CONSTANT_NameAndType:
            return f"{self.cp_string(e['name_index'])}:{self.cp_string(e['descriptor_index'])}"
        return self.cp_string(index)

    @property
    def class_name(self) -> str:
        return self.cp_string(self.this_class)

    @property
    def super_name(self) -> str:
        return self.cp_string(self.super_class)

    @property
    def major_version(self) -> int:
        return self.classfile_major

    def methods_info(self) -> list[tuple[str, str, int]]:
        """List of (name, descriptor, access_flags)."""
        return [(self.cp_string(m['name_index']), self.cp_string(m['descriptor_index']), m['access']) for m in self.methods]

    def fields_info(self) -> list[tuple[str, str, int]]:
        return [(self.cp_string(f['name_index']), self.cp_string(f['descriptor_index']), f['access']) for f in self.fields]


# ──────────────────────────────────────────────
# Scanner logic
# ──────────────────────────────────────────────

# Known mojmap class names (1.17+)
MOJMAP_TARGETS = {
    'container_helper': [
        'net.minecraft.world.ContainerHelper',
        'net.minecraft.server.ContainerHelper',
    ],
    'tag_value_input': [
        'net.minecraft.world.level.storage.TagValueInput',
    ],
    'tag_value_output': [
        'net.minecraft.world.level.storage.TagValueOutput',
    ],
    'value_input': [
        'net.minecraft.world.level.storage.ValueInput',
    ],
    'value_output': [
        'net.minecraft.world.level.storage.ValueOutput',
    ],
    'compound_tag': [
        'net.minecraft.nbt.CompoundTag',
        'net.minecraft.nbt.NBTTagCompound',
    ],
    'tag': [
        'net.minecraft.nbt.Tag',
        'net.minecraft.nbt.NBTBase',
    ],
    'list_tag': [
        'net.minecraft.nbt.ListTag',
        'net.minecraft.nbt.NBTTagList',
    ],
    'non_null_list': [
        'net.minecraft.core.NonNullList',
        'net.minecraft.util.NonNullList',
    ],
    'problem_reporter': [
        'net.minecraft.util.ProblemReporter',
    ],
    'item_stack': [
        'net.minecraft.world.item.ItemStack',
        'net.minecraft.item.ItemStack',
    ],
    'minecraft_server': [
        'net.minecraft.server.MinecraftServer',
    ],
    'registry_access': [
        'net.minecraft.core.RegistryAccess',
    ],
    'container_fields': ['items', 'itemStacks'],
}

# Container base class names to search for
CONTAINER_BASES = [
    'net.minecraft.world.level.block.entity.BaseContainerBlockEntity',
    'net.minecraft.tileentity.TileEntityChest',  # 1.12
    'net.minecraft.world.level.block.entity.ChestBlockEntity',
    'net.minecraft.world.level.block.entity.BarrelBlockEntity',
    'net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity',
]


def find_class(jar: zipfile.ZipFile, name: str) -> Optional[ClassFile]:
    """Find a class by full class name in the jar."""
    path = name.replace('.', '/') + '.class'
    for entry in jar.namelist():
        if entry.endswith('.class') and entry.startswith(path.replace('.class', '')):
            # Exact match
            if entry == path or entry == f'/{path}':
                return ClassFile(jar.read(entry))
            # Also check internal name with /
            if entry.replace('/', '.')[:-6] == name:
                return ClassFile(jar.read(entry))
    return None


def find_class_by_name_suffix(jar: zipfile.ZipFile, suffix: str) -> list[ClassFile]:
    """Find classes whose name ends with a given suffix."""
    result = []
    for entry in jar.namelist():
        if entry.endswith('.class'):
            try:
                cf = ClassFile(jar.read(entry))
                if cf.class_name.endswith(suffix):
                    result.append(cf)
            except Exception:
                pass
    return result


def find_mojmap_class(jar: zipfile.ZipFile, candidates: list[str]) -> Optional[str]:
    """Try to find a class by its mojmap name in the jar."""
    for name in candidates:
        for entry in jar.namelist():
            if entry.endswith('.class') and name.replace('.', '/') in entry:
                return name
    return None


def scan_jar(jar_path: str):
    """Main scan routine."""
    p = Path(jar_path)
    if not p.exists():
        print(f"ERROR: {p} not found")
        sys.exit(1)

    print(f"🔍 Scanning: {p.name}")
    print()

    with zipfile.ZipFile(str(p), 'r') as jar:
        # Index: parse all classes from top-level + nested jars
        class_index: dict[str, ClassFile] = {}

        # Phase 1: top-level .class files
        top_classes = [e for e in jar.namelist() if e.endswith('.class')]
        for entry in top_classes:
            try:
                cf = ClassFile(jar.read(entry))
                class_index[cf.class_name] = cf
            except Exception:
                pass

        # Phase 2: classes inside nested .jar files (paperclip format)
        nested_jars = [e for e in jar.namelist() if e.endswith('.jar') and not e.startswith('META-INF/maven/')]
        for nj in nested_jars:
            try:
                nested_data = jar.read(nj)
                with zipfile.ZipFile(io.BytesIO(nested_data)) as nz:
                    for ne in nz.namelist():
                        if ne.endswith('.class'):
                            try:
                                cf = ClassFile(nz.read(ne))
                                class_index[cf.class_name] = cf
                            except Exception:
                                pass
            except Exception:
                pass

        total_entries = len(top_classes) + sum(
            1 for nj in nested_jars
            for ne in _nested_class_count(jar, nj)  # just for stats
        ) if False else 0  # skip stats, count at the end

        nested_class_count = 0
        for nj in nested_jars:
            try:
                nested_data = jar.read(nj)
                with zipfile.ZipFile(io.BytesIO(nested_data)) as nz:
                    nested_class_count += sum(1 for e in nz.namelist() if e.endswith('.class'))
            except Exception:
                pass

        print(f"   Parsed {len(class_index)} classes (top: {len(top_classes)}, nested: {nested_class_count})")

        # 1. Detect MC version from MinecraftServer
        mc_server = None
        for name, cf in class_index.items():
            if name.endswith('MinecraftServer') or name == 'net.minecraft.server.MinecraftServer':
                mc_server = cf
                break

        if mc_server is None:
            # Fallback: find by superclass searching
            for name, cf in class_index.items():
                if cf.super_name.endswith('MinecraftServer') or cf.super_name.endswith('DedicatedServer'):
                    mc_server = cf
                    break

        if mc_server:
            major = mc_server.major_version
            print(f"\n📦 MinecraftServer: {mc_server.class_name}")
            print(f"   classfile major: {major}")

            MAJOR_MAP = {
                52: "1.12.x – 1.16.x", 61: "1.18.x – 1.19.x",
                60: "1.17.x",           63: "1.20.4",
                65: "1.21.x",           66: "1.22+?",
            }
            guessed = MAJOR_MAP.get(major, f"unknown (major {major})")
            print(f"   version guess: {guessed}")
        else:
            print("   ⚠ Could not find MinecraftServer class")
            return

        # 2. Try to find ContainerHelper by name first, then structural heuristic
        ch = find_mojmap_class(jar, MOJMAP_TARGETS['container_helper'])
        if ch:
            print(f"\n📦 ContainerHelper: {ch}")
            ch_cf = class_index.get(ch.replace('.', '/'))
            if ch_cf:
                for name, desc, acc in ch_cf.methods_info():
                    print(f"   method: {name}{desc}")
        else:
            print("\n📦 ContainerHelper: NOT FOUND by mojmap name, trying heuristic...")
            ch_cf = find_container_helper_heuristic(class_index)
            if ch_cf:
                print(f"   Found: {ch_cf.class_name}")
                ch = ch_cf.class_name.replace('/', '.')
                for name, desc, acc in ch_cf.methods_info():
                    print(f"   method: {name}{desc}")
            else:
                print("   ❌ Could not find ContainerHelper")

        # 3. Find TagValueInput / TagValueOutput
        tvi = find_mojmap_class(jar, MOJMAP_TARGETS['tag_value_input'])
        tvo = find_mojmap_class(jar, MOJMAP_TARGETS['tag_value_output'])
        print(f"\n📦 TagValueInput: {tvi or 'NOT FOUND (1.12 style — no TagValueInput)'}")
        print(f"📦 TagValueOutput: {tvo or 'NOT FOUND (1.12 style)'}")

        # 4. Find leaf container classes
        print("\n📦 Container leaf classes:")
        for base in CONTAINER_BASES:
            suffix = base.split('.')[-1]
            matches = [cf for cf in class_index.values() if cf.class_name.endswith(suffix)]
            for cf in matches:
                # Check if it extends BaseContainerBlockEntity chain
                fields = cf.fields_info()
                field_names = [f[0] for f in fields]
                has_items = any(f in field_names for f in ['items', 'itemStacks', 'inventory'])
                print(f"   {cf.class_name} (fields: {field_names})" if has_items else f"   {cf.class_name} (no items field)")

        # 5. Output NmsRegistry mapping snippet
        print("\n" + "=" * 60)
        print("📋 NmsRegistry mapping snippet:")
        print("=" * 60)
        output_mapping(mc_server, ch, ch_cf, tvi, tvo, class_index)


def find_container_helper_heuristic(class_index: dict[str, ClassFile]) -> Optional[ClassFile]:
    """Find ContainerHelper by structural matching for obfuscated jars.

    Strategy: ALL mojmap names are 100% obfuscated in Mojang's server jars.
    We identify ContainerHelper by:
    1. It has MANY static methods (more than any other utility class)
    2. Several of those methods take (NBT-class, List-class) or (NBT-class, List-class, boolean)
    3. It does NOT extend any significant class (no superclass chain)
    4. It is a utility class (no fields that look like data)
    """
    from collections import Counter

    # Heuristic: find classes whose static method descriptors reference
    # types that look like they could be collections/lists (end with 'List' in
    # their class name or have typical collection methods).
    # For obfuscated jars, we identify ItemStack-like and List-like classes
    # by looking at which classes are commonly passed to static methods.

    # Step 1: Find classes that have static methods with 2+ params
    # Group by parameter type co-occurrence
    static_methods_by_class = Counter()
    for name, cf in class_index.items():
        static_count = 0
        for m_name, m_desc, m_acc in cf.methods_info():
            if not (m_acc & 0x0008):  # ACC_STATIC
                continue
            params = re.findall(r'L([^;]+);', m_desc)
            # loadAllItems-like: 2 params where one is a list-like
            # saveAllItems-like: 2-3 params
            if len(params) in (2, 3):
                static_count += 1
        if static_count >= 4:
            static_methods_by_class[name] = static_count

    # Step 2: Identify the most likely ContainerHelper
    # Look for a class with MANY static methods that also has
    # at least one method with 3 params where the third is a boolean
    # (the saveAllItems with allowEmpty param)
    best_candidate = None
    best_score = 0

    for name, count in static_methods_by_class.most_common(50):
        cf = class_index.get(name)
        if not cf:
            continue

        # Check for boolean-param methods (saveAllItems signature)
        has_bool_method = False
        for m_name, m_desc, m_acc in cf.methods_info():
            if m_acc & 0x0008:  # static
                if 'Z' in m_desc:
                    has_bool_method = True
                    break

        # Score: static method count * 2 if has boolean param
        score = count
        if has_bool_method:
            score *= 2

        if score > best_score:
            best_score = score
            best_candidate = cf

    if best_candidate and best_score >= 6:
        print(f"   heuristic: {best_candidate.class_name} (static_count={static_methods_by_class.get(best_candidate.class_name, 0)}, score={best_score})")
        return best_candidate
    return None


def output_mapping(mc_server: Optional[ClassFile], ch_name: Optional[str],
                   ch_cf: Optional[ClassFile], tvi_name: Optional[str], tvo_name: Optional[str],
                   class_index: dict[str, ClassFile]):
    """Output NmsRegistry.java mapping snippet."""
    if not ch_name:
        print("// ❌ Cannot generate mapping: ContainerHelper not found")
        return

    # Convert to internal names
    ch_internal = ch_name.replace('.', '/')

    # Find compound tag, tag, nonnulllist from container helper usage
    compound_tag = None
    value_input = None
    value_output = None
    load_name = "loadAllItems"
    load_desc = None
    save2_name = "saveAllItems"
    save2_desc = None
    save3_name = "saveAllItems"
    save3_desc = None
    non_null_list = None

    if ch_cf:
        for m_name, m_desc, m_acc in ch_cf.methods_info():
            if not (m_acc & 0x0008):
                continue
            params = re.findall(r'L([^;]+);', m_desc)
            if len(params) == 2:
                load_desc = m_desc
                load_name = m_name
                value_input = params[0]
                non_null_list = params[1]
            elif len(params) == 2:
                # Could be saveAllItems(ValueOutput, NonNullList)
                if 'output' in params[0].lower() or 'Output' in params[0] or 'value' in params[0].lower():
                    save2_desc = m_desc
                    save2_name = m_name
                    value_output = params[0]
            elif len(params) == 3:
                ret_type = m_desc.split(')')[1] if ')' in m_desc else ''
                # saveAllItems(ValueOutput, NonNullList, boolean)
                if ret_type == 'V':
                    save3_desc = m_desc
                    save3_name = m_name
                    value_output = params[0] if not value_output else value_output

    # Fallback: find compound tag from NBT type index
    compound_tag = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['compound_tag'])
    tag_type = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['tag'])
    list_tag = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['list_tag'])
    nll = non_null_list or find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['non_null_list'])
    pr = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['problem_reporter'])
    is_item = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['item_stack'])
    ms = mc_server.class_name.replace('/', '.') if mc_server else 'net.minecraft.server.MinecraftServer'
    ra = find_mojmap_class_by_index(class_index, MOJMAP_TARGETS['registry_access'])

    # Output
    print(f"// Generated by tools/scan_nms.py — {Path(sys.argv[2]).name if len(sys.argv) > 2 else '?'}")
    print(f"// java version: {classfile_major_to_java(mc_server.major_version) if mc_server else '?'}")
    print(f"// minecraft guess: {major_to_version(mc_server.major_version) if mc_server else '?'}")
    print()
    print("register(VersionDetector.McVersion.V_xxx, new NmsMapping()")
    if ch_internal:
        print(f"    .containerHelper(\"{ch_internal}\")")
    if tvi_name:
        print(f"    .tagValueInput(\"{tvi_name.replace('.', '/')}\")")
    elif value_input:
        print(f"    .tagValueInput(\"{value_input}\")")
    if tvo_name:
        print(f"    .tagValueOutput(\"{tvo_name.replace('.', '/')}\")")
    elif value_output:
        print(f"    .tagValueOutput(\"{value_output}\")")
    if value_input:
        print(f"    .valueInput(\"{value_input}\")")
    if value_output:
        print(f"    .valueOutput(\"{value_output}\")")
    if compound_tag:
        print(f"    .compoundTag(\"{compound_tag.replace('.', '/')}\")")
    if tag_type:
        print(f"    .tag(\"{tag_type.replace('.', '/')}\")")
    if list_tag:
        print(f"    .listTag(\"{list_tag.replace('.', '/')}\")")
    if nll:
        print(f"    .nonNullList(\"{nll.replace('.', '/')}\")")
    if pr:
        print(f"    .problemReporter(\"{pr.replace('.', '/')}\")")
    if is_item:
        print(f"    .itemStack(\"{is_item.replace('.', '/')}\")")
    print(f"    .minecraftServer(\"{ms.replace('.', '/')}\")")
    if ra:
        print(f"    .registryAccess(\"{ra.replace('.', '/')}\")")
    if load_name and load_desc:
        print(f"    .loadAllItems(\"{load_name}\", \"{load_desc}\")")
    if save2_name and save2_desc:
        print(f"    .saveAllItems2(\"{save2_name}\", \"{save2_desc}\")")
    if save3_name and save3_desc:
        print(f"    .saveAllItems3(\"{save3_name}\", \"{save3_desc}\")")
    print("    .leafClasses(Arrays.asList(")
    print("        // TODO: find leaf container class names")
    print("    ))")
    print("    .containerFieldNames(Arrays.asList(\"items\", \"itemStacks\")));")
    print()


def find_mojmap_class_by_index(class_index: dict[str, ClassFile], candidates: list[str]) -> Optional[str]:
    for name in candidates:
        internal = name.replace('.', '/')
        if internal in class_index:
            return name
    return None


def classfile_major_to_java(major: int) -> str:
    m = {52: "8", 53: "9", 54: "10", 55: "11", 56: "12", 57: "13", 58: "14",
         59: "15", 60: "16", 61: "17", 62: "18", 63: "19", 64: "20", 65: "21", 66: "22"}
    return m.get(major, f"unknown({major})")


def major_to_version(major: int) -> str:
    m = {52: "1.12.2", 59: "1.16.5", 60: "1.17.1", 61: "1.18.2",
         63: "1.20.4", 65: "1.21.11", 66: "1.22+"}
    return m.get(major, f"unknown({major})")


def main():
    parser = argparse.ArgumentParser(description="Scan Paper server jar for NMS mappings")
    parser.add_argument("--paper", required=True, help="Path to Paper server jar")
    parser.add_argument("--output", "-o", help="Output file (default: stdout)")
    args = parser.parse_args()

    if args.output:
        sys.stdout = open(args.output, 'w')
    scan_jar(args.paper)


if __name__ == '__main__':
    main()
