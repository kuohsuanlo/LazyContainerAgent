#!/usr/bin/env python3
"""
mca_merge3.py —— 整格「三方合併」還原(取代「整格貼舊備份」)。

問題:整格把舊備份貼回去,會把備份時間點之後玩家蓋的**所有方塊**一起倒掉(不只容器)。
做法:對每個指定 chunk,三份來源逐方塊比:
    old    = 之前貼回去的那份舊備份(線上被重設成這個)
    live   = 線上現況(old + 還原之後玩家的新動作)
    mirror = 事故前最後一份好的鏡像(方塊最新,但容器可能被清空)
  方塊:live == old 的位置 ⟹ 沒人在還原後動過 ⟹ 拿 mirror(把被倒掉的建築補回來);否則保留 live(還原後的新動作優先)。
  方塊實體:跟著方塊的來源走;同一位置兩邊都有同種容器時,Items 取件數多的那份(平手留 live)。
  Heightmaps 整個拿掉(遊戲載入時自動重算),isLightOn 設 0(重算光照)。其餘欄位全部沿用 live。
只讀三份輸入、只寫 --out;不碰伺服器。用法:
    mca_merge3.py --live L.mca --old O.mca --mirror M.mca --out OUT.mca --chunks "196,-198 197,-198"
"""
import argparse, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca_restore as M

# ── NBT 寫入小工具 ─────────────────────────────────────────────
def t_name(s):
    b = s.encode("utf-8"); return struct.pack(">H", len(b)) + b
def entry(typ, name, payload):
    return bytes([typ]) + t_name(name) + payload
def p_byte(v): return struct.pack(">b", v)
def p_int(v): return struct.pack(">i", v)
def p_list(elem_type, payloads):
    return bytes([elem_type]) + p_int(len(payloads)) + b"".join(payloads)
def p_compound(entries):
    return b"".join(entries) + b"\x00"
def p_long_array(longs):
    return p_int(len(longs)) + b"".join(struct.pack(">q", v if v < (1 << 63) else v - (1 << 64)) for v in longs)

# ── 讀 ───────────────────────────────────────────────────────
def entries_of(data, p):
    return {k: (t, es, ps, pe) for k, t, es, ps, pe in M.compound_entries(data, p)}
def read_string(data, ps):
    n = M._u16(data, ps); return data[ps + 2:ps + 2 + n].decode("utf-8", "replace")
def palette_key(data, p):
    """palette 元素(compound payload 起點 p)→ (Name, ((prop, val), ...)) 正規化鍵。"""
    e = entries_of(data, p)
    name = read_string(data, e["Name"][2]) if "Name" in e and e["Name"][0] == 8 else "?"
    props = ()
    if "Properties" in e and e["Properties"][0] == 10:
        pe = entries_of(data, e["Properties"][2])
        props = tuple(sorted((k, read_string(data, v[2])) for k, v in pe.items() if v[0] == 8))
    return (name, props)

def read_sections(data):
    """→ {Y: dict(states=[4096 個 key], pal_raw={key: payload bytes}, es, pe(block_states entry 範圍), sec_ps)},
       以及 sections 清單本身的 entry 範圍。"""
    ps = M.root_payload_start(data)
    sec = M.find_entry(data, ps, "sections")
    if sec is None or sec[0] != 9:
        raise SystemExit("chunk 沒有 sections")
    p = sec[2]; et = data[p]; cnt = M._i32(data, p + 1); p += 5
    out = {}
    for _ in range(cnt):
        end = M.skip_payload(data, p, et)
        e = entries_of(data, p)
        y = struct.unpack_from(">b", data, e["Y"][2])[0]
        info = {"sec_ps": p, "sec_end": end, "states": None, "pal_raw": {}, "bs": None}
        if "block_states" in e and e["block_states"][0] == 10:
            bs = e["block_states"]; info["bs"] = bs
            sub = entries_of(data, bs[2])
            pal = sub["palette"]; pp = pal[2]; pet = data[pp]; pn = M._i32(data, pp + 1); pp += 5
            keys = []
            for _ in range(pn):
                pend = M.skip_payload(data, pp, pet)
                k = palette_key(data, pp); keys.append(k)
                info["pal_raw"].setdefault(k, data[pp:pend])
                pp = pend
            if pn == 1 or "data" not in sub:
                info["states"] = [keys[0]] * 4096
            else:
                dp = sub["data"][2]; ln = M._i32(data, dp)
                longs = struct.unpack_from(">%dq" % ln, data, dp + 4)
                bits = max(4, (pn - 1).bit_length()); per = 64 // bits; mask = (1 << bits) - 1
                st = []
                for idx in range(4096):
                    li, off = idx // per, (idx % per) * bits
                    v = (longs[li] >> off) & mask
                    st.append(keys[v] if v < pn else keys[0])
                info["states"] = st
        out[y] = info
        p = end
    return out

AIR = ("minecraft:air", ())

def encode_block_states(states, pal_raw):
    """4096 個 key + 各 key 的 palette 原始 bytes → block_states entry bytes。"""
    order = []
    seen = {}
    for k in states:
        if k not in seen:
            seen[k] = len(order); order.append(k)
    pal_payloads = [pal_raw[k] for k in order]
    ents = [entry(9, "palette", p_list(10, pal_payloads))]
    if len(order) > 1:
        bits = max(4, (len(order) - 1).bit_length()); per = 64 // bits
        longs = [0] * ((4096 + per - 1) // per)
        for idx, k in enumerate(states):
            li, off = idx // per, (idx % per) * bits
            longs[li] |= seen[k] << off
        ents.append(entry(12, "data", p_long_array(longs)))
    return entry(10, "block_states", p_compound(ents))

def items_total(data, info):
    if "Items" not in info or info["Items"][0] != 9: return -1
    t, es, ps, pe = info["Items"]; et = data[ps]; cnt = M._i32(data, ps + 1); p = ps + 5; total = 0
    for _ in range(cnt):
        end = M.skip_payload(data, p, et)
        if et == 10:
            for k, tt, ees, pps, ppe in M.compound_entries(data, p):
                if k == "count" and tt == 3: total += M._i32(data, pps)
        p = end
    return total

def read_bes(data):
    """→ {pos: dict(id, raw=整個 compound payload bytes, items=件數 or -1, info)}"""
    out = {}
    for info, pstart, pend in M.iter_block_entities(data):
        c = M.be_coords(data, info)
        if c is None: continue
        out[c] = {"id": M.be_id(data, info), "raw": data[pstart:pend], "items": items_total(data, info), "info": info, "data": data}
    return out

def merge_chunk(live, old, mirror, cx, cz, rep):
    L = read_sections(live); O = read_sections(old); Mi = read_sections(mirror)
    src = {}          # (y, idx) → 'live' | 'mirror'
    new_sections = {} # y → states list(合併後)
    n_from_mirror = n_conflict = 0
    for y in sorted(set(L) | set(Mi) | set(O)):
        ls = L[y]["states"] if y in L and L[y]["states"] else [AIR] * 4096
        os_ = O[y]["states"] if y in O and O[y]["states"] else [AIR] * 4096
        ms = Mi[y]["states"] if y in Mi and Mi[y]["states"] else [AIR] * 4096
        merged = []
        for i in range(4096):
            if ls[i] == os_[i]:
                merged.append(ms[i]); src[(y, i)] = "mirror" if ms[i] != ls[i] else "live"
                if ms[i] != ls[i]: n_from_mirror += 1
            else:
                merged.append(ls[i]); src[(y, i)] = "live"
                if ms[i] != os_[i] and ms[i] != ls[i]: n_conflict += 1
        new_sections[y] = merged
    # 方塊實體
    LB = read_bes(live); MB = read_bes(mirror)
    def src_of(pos):
        x, yy, z = pos; y = yy >> 4; idx = ((yy & 15) * 16 + (z & 15)) * 16 + (x & 15)
        return src.get((y, idx), "live")
    result = {}
    for pos, be in LB.items():
        if src_of(pos) == "live": result[pos] = be
    for pos, be in MB.items():
        if src_of(pos) == "mirror": result[pos] = be
    n_items_swapped = 0
    def with_items_from(dst, srcbe):
        """把 srcbe 的 Items entry 換進 dst 的 raw(其餘欄位沿用 dst)。"""
        si = srcbe["info"]; sdata = srcbe["data"]
        st, ses, sps, spe = si["Items"]; src_entry = sdata[ses:spe]
        ddata = dst["data"]; di = dst["info"]
        pstart = None
        for info2, ps2, pe2 in M.iter_block_entities(ddata):
            if M.be_coords(ddata, info2) == M.be_coords(sdata, si): pstart = ps2; break
        dlen = len(dst["raw"])
        if "Items" in di and di["Items"][0] == 9:
            _, des, _, dpe = di["Items"]
            new_raw = ddata[pstart:des] + src_entry + ddata[dpe:pstart + dlen]
        else:
            new_raw = src_entry + dst["raw"]
        return dict(dst, raw=new_raw)
    # 同位置、同種容器、兩邊都有 ⟹ 不管方塊來自哪邊,Items 一律取件數多的(平手留已選的那份)
    for pos in set(LB) & set(MB):
        if pos not in result: continue
        lb, mb = LB[pos], MB[pos]
        if lb["id"] != mb["id"] or "Items" not in lb["info"] or "Items" not in mb["info"]: continue
        chosen = result[pos]
        best = mb if mb["items"] > lb["items"] else lb
        if best["items"] > chosen["items"]:
            result[pos] = with_items_from(chosen, best); n_items_swapped += 1
    # ── 重組 chunk bytes:sections 逐段換 block_states、換 block_entities、拿掉 Heightmaps、isLightOn=0
    ps = M.root_payload_start(live)
    top = entries_of(live, ps)
    sec = top["sections"]
    # sections list 重建(沿用 live 每個 section 的其他欄位)
    p = sec[2]; et = live[p]; cnt = M._i32(live, p + 1); p += 5
    sec_payloads = []; ys_done = set()
    for _ in range(cnt):
        end = M.skip_payload(live, p, et)
        e = entries_of(live, p)
        y = struct.unpack_from(">b", live, e["Y"][2])[0]; ys_done.add(y)
        pal_raw = {}
        for S in (L, Mi, O):
            if y in S: pal_raw.update(S[y]["pal_raw"])
        if AIR not in pal_raw:
            pal_raw[AIR] = p_compound([entry(8, "Name", t_name("minecraft:air"))])
        new_bs = encode_block_states(new_sections[y], pal_raw)
        if "block_states" in e:
            _, es, _, pe = e["block_states"]
            payload = live[p:es] + new_bs + live[pe:end]
        else:
            payload = live[p:end - 1] + new_bs + b"\x00"
        sec_payloads.append(payload); p = end
    missing = [y for y in new_sections if y not in ys_done and any(k != AIR for k in new_sections[y])]
    if missing:
        raise SystemExit("live 缺 section Y=%s 但合併後那裡有方塊;本工具不新增 section,中止" % missing)
    new_sec_entry = entry(9, "sections", p_list(10, sec_payloads))
    be_entry = entry(9, "block_entities", p_list(10, [b["raw"] for _, b in sorted(result.items())]))
    # 逐 entry 重組頂層
    out = bytearray(live[:ps])
    for k, (t, es, ps2, pe) in sorted(top.items(), key=lambda kv: kv[1][1]):
        if k == "sections": out += new_sec_entry
        elif k == "block_entities": out += be_entry
        elif k == "Heightmaps": continue
        elif k == "isLightOn" and t == 1: out += live[es:ps2] + b"\x00"
        else: out += live[es:pe]
    if "block_entities" not in top: out += be_entry
    out += b"\x00"
    new = bytes(out)
    # 驗證:整份走得完、每格方塊等於預期、BE 都落在對的方塊上
    if M.skip_payload(new, M.root_payload_start(new), 10) != len(new):
        raise SystemExit("內部錯誤:合併後 NBT 走不完")
    chk = read_sections(new)
    for y, st in new_sections.items():
        got = chk[y]["states"] if y in chk and chk[y]["states"] else [AIR] * 4096
        if got != st: raise SystemExit("內部錯誤:section Y=%d 重編碼後不等於預期" % y)
    nb = read_bes(new)
    bad = 0
    for pos, be in nb.items():
        x, yy, z = pos; blk = chk[yy >> 4]["states"][((yy & 15) * 16 + (z & 15)) * 16 + (x & 15)][0] if (yy >> 4) in chk else "?"
        bid = be["id"].replace("minecraft:", "")
        if bid in ("chest", "barrel", "shulker_box", "hopper", "furnace", "dispenser", "dropper") and bid.split("_")[-1] not in blk:
            bad += 1
    rep.append("chunk %d,%d: 方塊從鏡像補回 %d 格、衝突(保留 live)%d 格;方塊實體 live %d / 鏡像 %d → 合併 %d(Items 取件數多的那份 %d 個;BE 與方塊不符 %d)"
               % (cx, cz, n_from_mirror, n_conflict, len(LB), len(MB), len(nb), n_items_swapped, bad))
    if bad: raise SystemExit("有方塊實體落在不對的方塊上,中止")
    return new

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", required=True); ap.add_argument("--old", required=True); ap.add_argument("--mirror", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--chunks", required=True, help='"cx,cz cx,cz ..."')
    a = ap.parse_args()
    live = M.Region(a.live); old = M.Region(a.old); mir = M.Region(a.mirror)
    raw = live.raw; rep = []
    for tok in a.chunks.split():
        cx, cz = map(int, tok.split(","))
        idx, _, _ = M.chunk_index(live.rx, live.rz, cx, cz)
        ld, od, md = live.decompressed(idx), old.decompressed(idx), mir.decompressed(idx)
        if ld is None or od is None or md is None:
            raise SystemExit("chunk %d,%d 三份裡有缺" % (cx, cz))
        M.check_same_version(md, ld)
        new = merge_chunk(ld, od, md, cx, cz, rep)
        raw = M.replace_chunk_blob(raw, idx, M.build_blob(new))
    with open(a.out, "wb") as f: f.write(raw)
    print("\n".join(rep)); print("寫出:", a.out)

if __name__ == "__main__":
    main()
