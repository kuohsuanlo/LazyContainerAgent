#!/usr/bin/env python3
"""區域檔(.mca)檢查與還原工具 —— 純 Python stdlib,離線使用。

用途:當某個 chunk 或某個容器的資料壞掉/被覆蓋時,從備份把它貼回來。

設計原則(重要,決定了這支工具為什麼這樣寫):

  1. **只搬位元組,絕不重新編碼。** 還原 Items 時不是「解析成樹 → 改 → 重寫」,而是找出目標 tag 在
     解壓後 chunk 資料中的**位元組區間**,直接換成備份檔裡對應的區間。理由:Java 的 NBT 字串是
     modified-UTF-8(U+0000 寫成 C0 80、增補字元寫成兩個三位元組的代理對),Python 的 utf-8 不是;
     float/double 的 NaN 位元樣式、compound 的 key 順序也都會在重寫時改變。整份重寫等於在每個
     欄位上重新賭一次,而位元組拼接只搬 Java 自己寫出來的東西。

  2. **寫入前確認沒人在用這個世界。** Paper 會把開過的 RegionFile(含 8 KB 檔頭與 sector 點名表)
     留在記憶體快取裡;伺服器在跑時我們改磁碟檔頭,下一次任何 chunk 寫入就會用記憶體裡的舊表把它蓋掉,
     甚至把我們附加在檔尾的資料分配給別的 chunk。所以所有寫入動作都要求:
       - 世界目錄的 session.lock 沒有被鎖住(用 fcntl.lockf 試鎖,這正是 Minecraft 用的機制),
       - 沒有任何程序開著這個 .mca(掃 /proc/*/fd)。
     verify / list 是唯讀的,線上跑沒問題,但要知道磁碟內容可能落後於伺服器記憶體。

  3. **改任何檔案前先備份**(<檔名>.bak-<時間戳>),而且不覆蓋既有備份。

用法:
    python3 mca_restore.py verify  <region.mca> [--deep]
    python3 mca_restore.py list    <region.mca> [--chunk X,Z]
    python3 mca_restore.py restore-chunk --from <備份.mca> --to <正式.mca> --chunk X,Z [--yes]
    python3 mca_restore.py restore-items --from <備份.mca> --to <正式.mca> --pos x,y,z [--yes]

座標:--chunk 用「區域內」或「世界」chunk 座標都可以(工具自己換算,超出該 region 會直接拒絕)。
      --pos 是方塊座標。
"""

import argparse
import os
import struct
import sys
import time
import zlib
import gzip

SECTOR = 4096
COMPRESSION = {1: "gzip", 2: "zlib", 3: "none", 4: "lz4", 127: "custom"}
CONTAINERS = {
    "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
    "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper",
    "minecraft:shulker_box",
}
for _c in ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
           "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"):
    CONTAINERS.add("minecraft:%s_shulker_box" % _c)


# ─────────────────────────── NBT 走訪(只算長度,不建樹) ───────────────────────────
#
# 回傳的是「位元組區間」,拼接就靠它。規則與伺服器讀取端對齊:型別 1..12、
# 陣列長度非負且 byte[]/int[] < 2^24、字串是 modified-UTF-8、compound 以 0 結尾。

class NbtError(Exception):
    pass


def _u16(b, o):
    return (b[o] << 8) | b[o + 1]


def _i32(b, o):
    v = struct.unpack_from(">i", b, o)[0]
    return v


def skip_payload(b, off, typ, depth=1):
    """回傳 payload 結束後的 offset。"""
    n = len(b)
    if depth > 512:
        raise NbtError("nesting too deep")
    if typ == 1:
        e = off + 1
    elif typ == 2:
        e = off + 2
    elif typ in (3, 5):
        e = off + 4
    elif typ in (4, 6):
        e = off + 8
    elif typ == 7:
        ln = _i32(b, off)
        if ln < 0 or ln >= 1 << 24:
            raise NbtError("byte array length %d" % ln)
        e = off + 4 + ln
    elif typ == 11:
        ln = _i32(b, off)
        if ln < 0 or ln >= 1 << 24:
            raise NbtError("int array length %d" % ln)
        e = off + 4 + ln * 4
    elif typ == 12:
        ln = _i32(b, off)
        if ln < 0:
            raise NbtError("long array length %d" % ln)
        e = off + 4 + ln * 8
    elif typ == 8:
        if off + 2 > n:
            raise NbtError("truncated string length")
        e = off + 2 + _u16(b, off)
    elif typ == 9:
        if off + 5 > n:
            raise NbtError("truncated list header")
        et = b[off]
        cnt = _i32(b, off + 1)
        if cnt < 0:
            raise NbtError("list count %d" % cnt)
        p = off + 5
        if cnt and (et == 0 or et > 12):
            raise NbtError("bad list element type %d" % et)
        for _ in range(cnt):
            p = skip_payload(b, p, et, depth + 1)
        e = p
    elif typ == 10:
        p = off
        while True:
            if p >= n:
                raise NbtError("unterminated compound")
            t = b[p]
            p += 1
            if t == 0:
                break
            if t > 12:
                raise NbtError("bad tag type %d" % t)
            if p + 2 > n:
                raise NbtError("truncated name")
            p += 2 + _u16(b, p)
            p = skip_payload(b, p, t, depth + 1)
        e = p
    else:
        raise NbtError("bad tag type %d" % typ)
    if e > n:
        raise NbtError("truncated payload (type %d)" % typ)
    return e


def compound_entries(b, off, depth=1):
    """走訪一個 compound 的 payload,逐項 yield (key, type, name_start, payload_start, payload_end)。"""
    n = len(b)
    p = off
    while True:
        if p >= n:
            raise NbtError("unterminated compound")
        t = b[p]
        entry_start = p
        p += 1
        if t == 0:
            return
        name_len = _u16(b, p)
        key = b[p + 2:p + 2 + name_len].decode("utf-8", "replace")
        p += 2 + name_len
        end = skip_payload(b, p, t, depth + 1)
        yield (key, t, entry_start, p, end)
        p = end


def root_payload_start(data):
    """chunk NBT 的根:[10][uint16 name][payload…];回傳 payload 起點。"""
    if not data or data[0] != 10:
        raise NbtError("root is not a compound (type %d)" % (data[0] if data else -1))
    return 3 + _u16(data, 1)


def find_entry(data, payload_start, key):
    for k, t, es, ps, pe in compound_entries(data, payload_start):
        if k == key:
            return (t, es, ps, pe)
    return None


# ─────────────────────────── 區域檔 ───────────────────────────

class Region:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self.raw = f.read()
        if len(self.raw) < 2 * SECTOR:
            raise SystemExit("%s: 檔案不足 8 KB,不是有效的區域檔" % path)
        self.rx, self.rz = parse_region_coords(path)

    def loc(self, idx):
        o = self.raw[idx * 4] << 16 | self.raw[idx * 4 + 1] << 8 | self.raw[idx * 4 + 2]
        cnt = self.raw[idx * 4 + 3]
        return o, cnt

    def timestamp(self, idx):
        return struct.unpack_from(">i", self.raw, SECTOR + idx * 4)[0]

    def blob(self, idx):
        """回傳 (length, compression, payload_bytes, external_flag) 或 None。"""
        off, cnt = self.loc(idx)
        if off == 0 or cnt == 0:
            return None
        s = off * SECTOR
        if s + 5 > len(self.raw):
            raise NbtError("chunk %d 的 offset 超出檔案" % idx)
        ln = struct.unpack_from(">I", self.raw, s)[0]
        comp = self.raw[s + 4]
        external = bool(comp & 0x80)
        comp &= 0x7F
        if ln < 1:
            raise NbtError("chunk %d 長度欄位 %d 不合法" % (idx, ln))
        payload = self.raw[s + 5:s + 4 + ln]
        if not external and len(payload) != ln - 1:
            raise NbtError("chunk %d 資料被截斷(宣告 %d,實得 %d)" % (idx, ln - 1, len(payload)))
        return ln, comp, payload, external

    def decompressed(self, idx):
        """回傳解壓後的 chunk NBT bytes;外部 .mcc 會自動去讀。"""
        b = self.blob(idx)
        if b is None:
            return None
        ln, comp, payload, external = b
        if external:
            cx, cz = idx % 32, idx // 32
            mcc = os.path.join(os.path.dirname(self.path),
                               "c.%d.%d.mcc" % (self.rx * 32 + cx, self.rz * 32 + cz))
            if not os.path.exists(mcc):
                raise NbtError("chunk %d 標記為外部儲存,但找不到 %s" % (idx, os.path.basename(mcc)))
            with open(mcc, "rb") as f:
                payload = f.read()
        if comp == 1:
            return gzip.decompress(payload)
        if comp == 2:
            return zlib.decompress(payload)
        if comp == 3:
            return payload
        raise NbtError("壓縮方式 %s(id %d)不支援,純 stdlib 解不了" % (COMPRESSION.get(comp, "?"), comp))


def parse_region_coords(path):
    base = os.path.basename(path)
    parts = base.split(".")
    if len(parts) < 4 or parts[0] != "r":
        raise SystemExit("%s: 檔名不是 r.<x>.<z>.mca" % base)
    return int(parts[1]), int(parts[2])


def chunk_index(rx, rz, cx, cz):
    """把世界 chunk 座標換算成 region 內索引;也接受 0..31 的區域內座標。"""
    if 0 <= cx < 32 and 0 <= cz < 32 and not (rx * 32 <= cx < rx * 32 + 32):
        lx, lz = cx, cz
    else:
        lx, lz = cx - rx * 32, cz - rz * 32
    if not (0 <= lx < 32 and 0 <= lz < 32):
        raise SystemExit("chunk (%d,%d) 不在 %s 這個區域檔裡" % (cx, cz, "r.%d.%d" % (rx, rz)))
    return lz * 32 + lx, lx, lz


# ─────────────────────────── 安全閘門 ───────────────────────────

def world_dir_of(region_path):
    d = os.path.dirname(os.path.abspath(region_path))
    return os.path.dirname(d)          # …/<world>/region/r.x.z.mca → …/<world>


def session_lock_held(region_path):
    """世界的 session.lock 被鎖著 ⟹ 伺服器正在用。回傳 (held, 說明)。"""
    for base in (world_dir_of(region_path),
                 os.path.dirname(os.path.dirname(os.path.dirname(world_dir_of(region_path))))):
        lock = os.path.join(base, "session.lock")
        if not os.path.exists(lock):
            continue
        try:
            import fcntl
            with open(lock, "rb+") as f:
                try:
                    fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.lockf(f, fcntl.LOCK_UN)
                except OSError:
                    return True, lock
        except Exception as e:
            return True, "%s(無法確認:%s)" % (lock, e)
    return False, None


def processes_holding(path):
    """掃 /proc/*/fd,回傳開著這個檔的 pid 清單。"""
    target = os.path.realpath(path)
    hits = []
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        fddir = "/proc/%s/fd" % pid
        try:
            for fd in os.listdir(fddir):
                try:
                    if os.path.realpath(os.path.join(fddir, fd)) == target:
                        hits.append(int(pid))
                        break
                except OSError:
                    pass
        except OSError:
            pass
    return hits


def require_offline(path, yes):
    held, lock = session_lock_held(path)
    if held:
        print("拒絕寫入:世界的 session.lock 正被鎖住(%s)—— 伺服器在跑。" % lock, file=sys.stderr)
        print("伺服器在跑時,它記憶體裡的區域檔檔頭會把我們改的東西整份蓋掉。請先停該分流。", file=sys.stderr)
        sys.exit(2)
    pids = processes_holding(path)
    if pids:
        print("拒絕寫入:還有程序開著這個檔(pid %s)。" % ", ".join(map(str, pids)), file=sys.stderr)
        sys.exit(2)
    if not yes:
        print("這會修改 %s(會先備份)。確定請加 --yes。" % path, file=sys.stderr)
        sys.exit(3)


def backup_file(path):
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dst = "%s.bak-%s" % (path, stamp)
    i = 0
    while os.path.exists(dst):
        i += 1
        dst = "%s.bak-%s.%d" % (path, stamp, i)
    with open(path, "rb") as s, open(dst, "wb") as d:
        d.write(s.read())
    print("已備份 → %s" % dst)
    return dst


# ─────────────────────────── 指令 ───────────────────────────

def cmd_verify(args):
    r = Region(args.region)
    present = bad = external = 0
    comps = {}
    used = {}
    problems = []
    for idx in range(1024):
        off, cnt = r.loc(idx)
        if off == 0 and cnt == 0:
            continue
        present += 1
        lx, lz = idx % 32, idx // 32
        name = "chunk (%d,%d)" % (r.rx * 32 + lx, r.rz * 32 + lz)
        if off < 2:
            problems.append("%s: offset %d 落在檔頭裡" % (name, off))
            bad += 1
            continue
        for s in range(off, off + cnt):
            if s in used:
                problems.append("%s: sector %d 與 %s 重疊" % (name, s, used[s]))
            used[s] = name
        try:
            b = r.blob(idx)
            if b is None:
                continue
            ln, comp, payload, ext = b
            comps[comp] = comps.get(comp, 0) + 1
            if ext:
                external += 1
            if 5 + ln > cnt * SECTOR and not ext:
                problems.append("%s: 資料 %d bytes 超出配置的 %d 個 sector" % (name, ln, cnt))
            data = r.decompressed(idx)
            if args.deep:
                ps = root_payload_start(data)
                end = skip_payload(data, ps, 10)
                if end != len(data):
                    problems.append("%s: NBT 走訪後還剩 %d bytes" % (name, len(data) - end))
                e = find_entry(data, ps, "xPos")
                z = find_entry(data, ps, "zPos")
                if e and z and e[0] == 3 and z[0] == 3:
                    gx = _i32(data, e[2])          # find_entry 回傳 (type, entry_start, payload_start, payload_end)
                    gz = _i32(data, z[2])
                    if (gx, gz) != (r.rx * 32 + lx, r.rz * 32 + lz):
                        problems.append("%s: 內部座標寫的是 (%d,%d)" % (name, gx, gz))
        except Exception as e:
            bad += 1
            problems.append("%s: %s" % (name, e))
    print("%s:%d 個 chunk;壓縮方式 %s;外部 .mcc %d 個" %
          (os.path.basename(args.region), present,
           ", ".join("%s×%d" % (COMPRESSION.get(k, k), v) for k, v in sorted(comps.items())), external))
    if problems:
        print("發現 %d 個問題:" % len(problems))
        for p in problems[:80]:
            print("  - %s" % p)
        if len(problems) > 80:
            print("  …(還有 %d 個)" % (len(problems) - 80))
    else:
        print("沒有發現問題%s。" % ("(含完整 NBT 走訪)" if args.deep else "(檔頭層級;加 --deep 走完整 NBT)"))
    return 1 if problems else 0


def iter_block_entities(data):
    ps = root_payload_start(data)
    be = find_entry(data, ps, "block_entities")
    if be is None or be[0] != 9:
        return
    p = be[2]
    et = data[p]
    cnt = _i32(data, p + 1)
    p += 5
    for _ in range(cnt):
        end = skip_payload(data, p, et)
        if et == 10:
            info = {}
            for k, t, es, pstart, pend in compound_entries(data, p):
                info[k] = (t, es, pstart, pend)
            yield info, p, end
        p = end


def be_coords(data, info):
    out = []
    for k in ("x", "y", "z"):
        if k not in info or info[k][0] != 3:
            return None
        out.append(_i32(data, info[k][2]))
    return tuple(out)


def be_id(data, info):
    if "id" not in info or info["id"][0] != 8:
        return "?"
    ps = info["id"][2]
    return data[ps + 2:ps + 2 + _u16(data, ps)].decode("utf-8", "replace")


def cmd_list(args):
    r = Region(args.region)
    only = None
    if args.chunk:
        cx, cz = map(int, args.chunk.split(","))
        only, _, _ = chunk_index(r.rx, r.rz, cx, cz)
    total = 0
    for idx in range(1024):
        if only is not None and idx != only:
            continue
        try:
            data = r.decompressed(idx)
        except Exception as e:
            print("chunk idx %d: %s" % (idx, e))
            continue
        if data is None:
            continue
        for info, _, _ in iter_block_entities(data):
            bid = be_id(data, info)
            if bid not in CONTAINERS:
                continue
            xyz = be_coords(data, info)
            items = info.get("Items")
            size = (items[3] - items[2]) if items else 0
            print("  %-28s %s  Items %d bytes" % (bid, xyz, size))
            total += 1
    print("共 %d 個容器" % total)
    return 0


def replace_chunk_blob(dst_raw, idx, new_blob):
    """回傳改好的整份 region bytes:能就地覆寫就就地,不夠位子就附加到檔尾。"""
    raw = bytearray(dst_raw)
    need = (len(new_blob) + SECTOR - 1) // SECTOR
    if need > 255:
        raise SystemExit("還原後的資料需要 %d 個 sector,超過區域檔上限 255(需要外部 .mcc,本工具不處理)" % need)
    off = raw[idx * 4] << 16 | raw[idx * 4 + 1] << 8 | raw[idx * 4 + 2]
    cnt = raw[idx * 4 + 3]
    if cnt >= need and off >= 2:
        start = off * SECTOR
    else:
        start = len(raw)
        if start % SECTOR:
            raw.extend(b"\0" * (SECTOR - start % SECTOR))
            start = len(raw)
        off = start // SECTOR
    raw.extend(b"\0" * max(0, start + need * SECTOR - len(raw)))
    raw[start:start + len(new_blob)] = new_blob
    for i in range(len(new_blob), need * SECTOR):
        raw[start + i] = 0
    raw[idx * 4] = (off >> 16) & 0xFF
    raw[idx * 4 + 1] = (off >> 8) & 0xFF
    raw[idx * 4 + 2] = off & 0xFF
    raw[idx * 4 + 3] = need
    struct.pack_into(">i", raw, SECTOR + idx * 4, int(time.time()))
    return bytes(raw)


def build_blob(data_bytes, compression=2):
    body = zlib.compress(data_bytes, 6) if compression == 2 else data_bytes
    return struct.pack(">IB", len(body) + 1, compression) + body


def check_same_version(src_data, dst_data):
    def dv(d):
        ps = root_payload_start(d)
        e = find_entry(d, ps, "DataVersion")
        return _i32(d, e[2]) if e and e[0] == 3 else None
    a, b = dv(src_data), dv(dst_data)
    if a is not None and b is not None and a != b:
        print("警告:備份的 DataVersion=%s,目標是 %s —— 版本不同的資料直接拼接會繞過存檔升級器。" % (a, b),
              file=sys.stderr)
        return False
    return True


def cmd_restore_chunk(args):
    src = Region(args.src)
    dst = Region(args.dst)
    cx, cz = map(int, args.chunk.split(","))
    if (src.rx, src.rz) != (dst.rx, dst.rz):
        raise SystemExit("兩個區域檔不是同一格:%s vs %s" % (os.path.basename(args.src), os.path.basename(args.dst)))
    idx, lx, lz = chunk_index(dst.rx, dst.rz, cx, cz)
    gx, gz = dst.rx * 32 + lx, dst.rz * 32 + lz
    sb = src.blob(idx)
    if sb is None:
        raise SystemExit("備份裡沒有 chunk (%d,%d)" % (gx, gz))
    ln, comp, payload, external = sb
    if external:
        raise SystemExit("備份的 chunk (%d,%d) 存在外部 .mcc,本工具不處理" % (gx, gz))
    # 內容檢查:能解壓、能走完 NBT、內部座標對得上
    sdata = src.decompressed(idx)
    ps = root_payload_start(sdata)
    if skip_payload(sdata, ps, 10) != len(sdata):
        raise SystemExit("備份的 chunk (%d,%d) 自己就是壞的(NBT 走不完)" % (gx, gz))
    ex, ez = find_entry(sdata, ps, "xPos"), find_entry(sdata, ps, "zPos")
    if ex and ez:
        if (_i32(sdata, ex[2]), _i32(sdata, ez[2])) != (gx, gz):
            raise SystemExit("備份那格的內部座標是 (%d,%d),不是 (%d,%d) —— 拒絕貼上"
                             % (_i32(sdata, ex[2]), _i32(sdata, ez[2]), gx, gz))
    try:
        ddata = dst.decompressed(idx)
        if ddata:
            check_same_version(sdata, ddata)
    except Exception:
        pass
    require_offline(args.dst, args.yes)
    backup_file(args.dst)
    blob = struct.pack(">IB", ln, comp) + payload
    out = replace_chunk_blob(dst.raw, idx, blob)
    with open(args.dst, "wb") as f:
        f.write(out)
    print("已把 chunk (%d,%d) 從備份還原到 %s(%d bytes)" % (gx, gz, args.dst, len(payload)))
    return 0


def cmd_restore_items(args):
    src = Region(args.src)
    dst = Region(args.dst)
    x, y, z = map(int, args.pos.split(","))
    cx, cz = x >> 4, z >> 4
    idx, lx, lz = chunk_index(dst.rx, dst.rz, cx, cz)
    sdata = src.decompressed(idx)
    ddata = dst.decompressed(idx)
    if sdata is None or ddata is None:
        raise SystemExit("備份或目標裡沒有這個 chunk")
    check_same_version(sdata, ddata)

    def find_items(data):
        for info, _, _ in iter_block_entities(data):
            if be_coords(data, info) == (x, y, z):
                return info
        return None

    sinfo, dinfo = find_items(sdata), find_items(ddata)
    if sinfo is None:
        raise SystemExit("備份裡座標 (%d,%d,%d) 沒有方塊實體" % (x, y, z))
    if dinfo is None:
        raise SystemExit("目標裡座標 (%d,%d,%d) 沒有方塊實體(要先用 restore-chunk 還原整格)" % (x, y, z))
    sid, did = be_id(sdata, sinfo), be_id(ddata, dinfo)
    if sid != did:
        raise SystemExit("方塊種類不同:備份是 %s,目標是 %s" % (sid, did))
    if "Items" not in sinfo:
        raise SystemExit("備份裡那個 %s 沒有 Items" % sid)
    st, ses, sps, spe = sinfo["Items"]
    src_entry = sdata[ses:spe]                       # [type][name][payload] 整段,原樣搬
    if "Items" in dinfo:
        _, des, _, dpe = dinfo["Items"]
        new = ddata[:des] + src_entry + ddata[dpe:]
    else:
        # 目標沒有 Items:插在該方塊實體 compound 的開頭(compound 無序,插哪裡都合法)
        first = None
        for info, pstart, pend in iter_block_entities(ddata):
            if be_coords(ddata, info) == (x, y, z):
                first = pstart
                break
        new = ddata[:first] + src_entry + ddata[first:]
    # 拼完再整份走一次,確認結構完好
    ps = root_payload_start(new)
    if skip_payload(new, ps, 10) != len(new):
        raise SystemExit("內部錯誤:拼接後的 NBT 走不完,已中止(沒有寫入任何東西)")
    require_offline(args.dst, args.yes)
    backup_file(args.dst)
    out = replace_chunk_blob(dst.raw, idx, build_blob(new))
    with open(args.dst, "wb") as f:
        f.write(out)
    print("已把 (%d,%d,%d) 的 %s Items(%d bytes)從備份貼回 %s" % (x, y, z, sid, spe - sps, args.dst))
    return 0


def main():
    ap = argparse.ArgumentParser(description="區域檔檢查與還原(離線;寫入前會確認伺服器沒在跑)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("verify", help="檢查區域檔:檔頭、sector 重疊、壓縮、可解壓;--deep 另做完整 NBT 走訪")
    p.add_argument("region")
    p.add_argument("--deep", action="store_true")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("list", help="列出容器方塊實體與其 Items 大小")
    p.add_argument("region")
    p.add_argument("--chunk", help="只看某個 chunk,格式 X,Z")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("restore-chunk", help="從備份把整個 chunk 貼回去")
    p.add_argument("--from", dest="src", required=True)
    p.add_argument("--to", dest="dst", required=True)
    p.add_argument("--chunk", required=True, help="X,Z")
    p.add_argument("--yes", action="store_true")
    p.set_defaults(func=cmd_restore_chunk)

    p = sub.add_parser("restore-items", help="從備份把單一容器的 Items 貼回去(位元組拼接,不重新編碼)")
    p.add_argument("--from", dest="src", required=True)
    p.add_argument("--to", dest="dst", required=True)
    p.add_argument("--pos", required=True, help="x,y,z")
    p.add_argument("--yes", action="store_true")
    p.set_defaults(func=cmd_restore_items)

    a = ap.parse_args()
    sys.exit(a.func(a) or 0)


if __name__ == "__main__":
    main()
