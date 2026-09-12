#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 LazyContainer 的 MASS EMPTY 落檔(lc-massempty-*.nbt)貼回 region 檔。

落檔格式:{entries:[{x,y,z,Items:<原始 ListTag>}]},每筆的 Items 是存檔當下、被清空**之前**的內容。
同一個 chunk 可能有多份落檔(每次 autosave 清一批),所以按 (x,y,z) 取聯集、同座標取 bytes 最大那份。
貼回用 mca_restore 的位元組拼接(不重新編碼)。

用法:massempty_apply.py --to <r.X.Z.mca> <dump.nbt ...> [--yes] [--dry]
"""
import sys, os, struct, argparse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca_restore as M

def u16(b, o): return struct.unpack('>H', b[o:o+2])[0]

def parse_dump(data):
    """→ {(x,y,z): items_entry_bytes}  entry = [type][u16 5]"Items"[payload]"""
    off = 0
    assert data[off] == 10; off += 1; off += 2 + u16(data, off)          # root compound, 空名
    out = {}
    while data[off] != 0:
        t = data[off]; off += 1; n = u16(data, off); off += 2; name = data[off:off+n].decode('utf-8', 'replace'); off += n
        if name != 'entries' or t != 9:
            off = M.skip_payload(data, off, t); continue
        et = data[off]; off += 1; cnt = struct.unpack('>i', data[off:off+4])[0]; off += 4
        assert et == 10
        for _ in range(cnt):
            x = y = z = None; items = None
            while data[off] != 0:
                tt = data[off]; s = off; off += 1; ln = u16(data, off); off += 2; key = data[off:off+ln].decode('utf-8', 'replace'); off += ln
                pend = M.skip_payload(data, off, tt)
                if key == 'x': x = struct.unpack('>i', data[off:off+4])[0]
                elif key == 'y': y = struct.unpack('>i', data[off:off+4])[0]
                elif key == 'z': z = struct.unpack('>i', data[off:off+4])[0]
                elif key == 'Items': items = data[s:pend]
                off = pend
            off += 1                                                    # compound end
            if items is not None and None not in (x, y, z):
                out[(x, y, z)] = items
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--to', required=True); ap.add_argument('dumps', nargs='+')
    ap.add_argument('--yes', action='store_true'); ap.add_argument('--dry', action='store_true')
    a = ap.parse_args()
    best = {}
    for f in a.dumps:
        for pos, ent in parse_dump(open(f, 'rb').read()).items():
            if pos not in best or len(ent) > len(best[pos][0]):
                best[pos] = (ent, os.path.basename(f))
    dst = M.Region(a.to)
    raw = dst.raw
    by_chunk = {}
    for (x, y, z), (ent, src) in best.items():
        by_chunk.setdefault((x >> 4, z >> 4), []).append((x, y, z, ent, src))
    applied = skipped = 0
    for (cx, cz), lst in sorted(by_chunk.items()):
        idx, _, _ = M.chunk_index(dst.rx, dst.rz, cx, cz)
        try: ddata = M.Region(a.to).decompressed(idx) if raw is dst.raw else None
        except Exception as e: ddata = None
        # 重新從目前的 raw 解壓(前一個 chunk 的修改不影響別的 chunk)
        tmp = M.Region(a.to); tmp.raw = raw
        try: ddata = tmp.decompressed(idx)
        except Exception as e: print('chunk (%d,%d) 讀不到:%s' % (cx, cz, e)); continue
        if ddata is None: print('chunk (%d,%d) 目標裡不存在,跳過' % (cx, cz)); continue
        new = ddata
        for (x, y, z, ent, src) in lst:
            dinfo = None
            for info, pstart, pend in M.iter_block_entities(new):
                if M.be_coords(new, info) == (x, y, z): dinfo = (info, pstart); break
            if dinfo is None: print('  (%d,%d,%d) 目標無此方塊實體,跳過' % (x, y, z)); skipped += 1; continue
            info, pstart = dinfo
            if 'Items' in info:
                _, des, _, dpe = info['Items']; new = new[:des] + ent + new[dpe:]
            else:
                new = new[:pstart] + ent + new[pstart:]
            applied += 1
            if a.dry: print('  會貼 (%d,%d,%d) %s ← %s (%d bytes)' % (x, y, z, M.be_id(new, info), src, len(ent)))
        ps = M.root_payload_start(new)
        if M.skip_payload(new, ps, 10) != len(new):
            raise SystemExit('chunk (%d,%d) 拼接後 NBT 走不完,中止(未寫入)' % (cx, cz))
        if not a.dry:
            raw = M.replace_chunk_blob(raw, idx, M.build_blob(new))
            print('chunk (%d,%d):貼回 %d 個容器' % (cx, cz, len(lst)))
    if a.dry: print('dry:可貼 %d,跳過 %d' % (applied, skipped)); return 0
    M.require_offline(a.to, a.yes); M.backup_file(a.to)
    open(a.to, 'wb').write(raw)
    print('完成:貼回 %d 個容器,跳過 %d,寫入 %s' % (applied, skipped, a.to))
    return 0

if __name__ == '__main__': sys.exit(main())
