#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""容器內容流失偵測(外部視角,不依賴 agent 自己是對的)。

拿「同一個世界的兩份 region 檔」對帳 —— 通常是每日備份 vs 線上現況 —— 找出
「上一份有東西、這一份變空」的容器,並且以 chunk 為單位加總。單一 chunk 內大量
容器同時歸零,是玩法幾乎不可能造成的形狀(2026-09-08 s3 商場 chunk (196,-198)
一口氣清掉 154 個容器、五百多萬件),適合當警報線。

這支刻意不看伺服器 log、不問 agent 的計數器:agent 自己出錯時,它的自我回報也不能信。

用法:
    python3 container_loss_watch.py <舊 region 目錄> <新 region 目錄> [選項]
    python3 container_loss_watch.py old/r.6.-7.mca new/r.6.-7.mca [選項]

選項:
    --min-containers N   單一 chunk 至少幾個容器歸零才報(預設 8)
    --min-items N        單一 chunk 至少流失幾件才報(預設 10000)
    --json <path>        另外輸出 JSON(給面板/cron 吃)
    --all                不套門檻,全部列出

離開碼:0 = 沒有超過門檻的 chunk;1 = 有(給 cron 判斷要不要發警報);2 = 用法錯誤。
"""
import sys, os, io, json, glob, struct, zlib, gzip, collections

CONT = {'minecraft:chest', 'minecraft:barrel', 'minecraft:trapped_chest'} | {
    'minecraft:%sshulker_box' % c for c in
    ['', 'white_', 'orange_', 'magenta_', 'light_blue_', 'yellow_', 'lime_', 'pink_', 'gray_',
     'light_gray_', 'cyan_', 'purple_', 'blue_', 'brown_', 'green_', 'red_', 'black_']}


# ── 極簡 NBT 讀取(只取需要的欄位,不建完整樹以外的東西)────────────────────────────
def _rd(b, t):
    if t == 1:  return struct.unpack('>b', b.read(1))[0]
    if t == 2:  return struct.unpack('>h', b.read(2))[0]
    if t == 3:  return struct.unpack('>i', b.read(4))[0]
    if t == 4:  return struct.unpack('>q', b.read(8))[0]
    if t == 5:  return struct.unpack('>f', b.read(4))[0]
    if t == 6:  return struct.unpack('>d', b.read(8))[0]
    if t == 7:  n = struct.unpack('>i', b.read(4))[0]; b.read(n); return None
    if t == 8:  n = struct.unpack('>H', b.read(2))[0]; return b.read(n).decode('utf-8', 'replace')
    if t == 9:
        et = b.read(1)[0]; n = struct.unpack('>i', b.read(4))[0]
        return ('L', [_rd(b, et) for _ in range(n)])
    if t == 10:
        d = {}
        while True:
            tt = b.read(1)[0]
            if tt == 0: return d
            n = struct.unpack('>H', b.read(2))[0]
            # 名字必須先讀進變數:`d[b.read(...)] = _rd(...)` 在 Python 是先算右邊,
            # 會把 payload 當成名字讀掉,整份 NBT 從這裡開始錯位。
            k = b.read(n).decode('utf-8', 'replace')
            d[k] = _rd(b, tt)
    if t == 11: n = struct.unpack('>i', b.read(4))[0]; b.read(4 * n); return None
    if t == 12: n = struct.unpack('>i', b.read(4))[0]; b.read(8 * n); return None
    raise ValueError('unknown tag type %d' % t)


def deep_count(v):
    """一個 Items entry 的總件數:自己的 count 加上巢狀容器(界伏盒裡的東西)的 count。
    只數 count,不管是什麼物品——警報線要的是「掉了多少」,不是清單。"""
    if isinstance(v, dict):
        total = v.get('count', 1) if 'id' in v else 0
        for k, x in v.items():
            if k != 'count':
                total += deep_count(x)
        return total or 0
    if isinstance(v, tuple) and v and v[0] == 'L':
        return sum(deep_count(x) for x in v[1])
    return 0


def _decomp(data, ctype):
    if ctype == 1: return gzip.decompress(data)
    if ctype == 2: return zlib.decompress(data)
    if ctype == 3: return data
    raise ValueError('compression %d' % ctype)


def scan_region(path):
    """→ {(x,y,z): (id, 非空格數, 件數, (cx,cz))}"""
    raw = open(path, 'rb').read()
    out = {}
    if len(raw) < 8192:
        return out
    for i in range(1024):
        off = struct.unpack('>I', b'\0' + raw[i * 4:i * 4 + 3])[0] * 4096
        if off == 0 or off + 5 > len(raw):
            continue
        ln = struct.unpack('>I', raw[off:off + 4])[0]
        try:
            data = _decomp(raw[off + 5:off + 4 + ln], raw[off + 4] & 0x7f)
        except Exception:
            continue
        try:
            b = io.BytesIO(data)
            b.read(1)                                   # root TAG_Compound
            b.read(struct.unpack('>H', b.read(2))[0])   # root name
            root = _rd(b, 10)
        except Exception:
            continue
        bes = root.get('block_entities')
        if not bes or bes[0] != 'L':
            continue
        cx, cz = root.get('xPos', 0), root.get('zPos', 0)
        for be in bes[1]:
            if not isinstance(be, dict) or be.get('id') not in CONT:
                continue
            entries = be.get('Items')
            slots = items = 0
            if entries and entries[0] == 'L':
                for e in entries[1]:
                    if isinstance(e, dict):
                        slots += 1
                        items += deep_count(e)
            out[(be.get('x', 0), be.get('y', 0), be.get('z', 0))] = (be['id'], slots, items, (cx, cz))
    return out


def pairs(old, new):
    """把兩邊配對成 [(標籤, 舊路徑, 新路徑)];支援目錄或單檔。"""
    if os.path.isfile(old) and os.path.isfile(new):
        return [(os.path.basename(new), old, new)]
    if not (os.path.isdir(old) and os.path.isdir(new)):
        sys.exit('兩邊必須同時是目錄,或同時是 .mca 檔')
    res = []
    for p in sorted(glob.glob(os.path.join(new, '*.mca'))):
        q = os.path.join(old, os.path.basename(p))
        if os.path.exists(q):
            res.append((os.path.basename(p), q, p))
    return res


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    opts = [a for a in sys.argv[1:] if a.startswith('--')]
    if len(args) < 2:
        sys.exit(__doc__)

    def opt(name, default):
        for i, a in enumerate(sys.argv):
            if a == name and i + 1 < len(sys.argv):
                return sys.argv[i + 1]
        return default

    min_c = 0 if '--all' in opts else int(opt('--min-containers', 8))
    min_i = 0 if '--all' in opts else int(opt('--min-items', 10000))
    out_json = opt('--json', None)

    chunks = collections.defaultdict(lambda: {'emptied': 0, 'reduced': 0, 'items_lost': 0, 'worst': []})
    total_files = 0
    for label, oldp, newp in pairs(args[0], args[1]):
        total_files += 1
        o, n = scan_region(oldp), scan_region(newp)
        for pos, (bid, oslots, oitems, och) in o.items():
            if pos not in n:
                continue
            _bid, nslots, nitems, nch = n[pos]
            if oitems <= 0 or nitems >= oitems:
                continue
            key = '%s %s' % (label, nch)
            c = chunks[key]
            c['items_lost'] += oitems - nitems
            if nslots == 0:
                c['emptied'] += 1
            else:
                c['reduced'] += 1
            c['worst'].append((oitems - nitems, '%d %d %d' % pos, bid))

    hits = []
    for key, c in chunks.items():
        if c['emptied'] >= min_c or c['items_lost'] >= min_i:
            c['worst'] = sorted(c['worst'], reverse=True)[:5]
            hits.append((c['items_lost'], key, c))
    hits.sort(reverse=True)

    print('比對 %d 個 region 檔;有流失的 chunk %d 個,超過門檻 %d 個'
          % (total_files, len(chunks), len(hits)))
    print('門檻:單一 chunk 歸零容器 ≥ %d 個,或流失 ≥ %s 件' % (min_c, format(min_i, ',')))
    for loss, key, c in hits:
        print('\n[警報] %s' % key)
        print('   歸零容器 %d 個、減少但未歸零 %d 個、流失 %s 件'
              % (c['emptied'], c['reduced'], format(loss, ',')))
        for d, pos, bid in c['worst']:
            print('      %s  %s  −%s' % (pos, bid.replace('minecraft:', ''), format(d, ',')))

    if out_json:
        with open(out_json, 'w') as f:
            # 不用 {**v}:正式站的 python 舊到不支援字典字面量展開(實測 SyntaxError)
            rows = []
            for _loss, k, v in hits:
                row = dict(v)
                row['chunk'] = k
                rows.append(row)
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print('\nJSON → %s' % out_json)

    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
