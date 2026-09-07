#!/usr/bin/env python3
"""純 stdlib 的 .mca → 容器 Items 擷取(給 G4 三模式結構比對用)。

不依賴伺服器:直接解 region 檔、走 NBT,取出每個容器方塊實體的 Items,
並做「結構正規化」(compound 的 key 排序)——因為 Paper 的 CompoundTag 是雜湊表,
key 的迭代順序本來就不保證,那不是資料差異。"""
import sys, zlib, struct, io, json, hashlib

def rd(b, t):
    if t == 1: return struct.unpack('>b', b.read(1))[0]
    if t == 2: return struct.unpack('>h', b.read(2))[0]
    if t == 3: return struct.unpack('>i', b.read(4))[0]
    if t == 4: return struct.unpack('>q', b.read(8))[0]
    if t == 5: return struct.unpack('>f', b.read(4))[0]
    if t == 6: return struct.unpack('>d', b.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', b.read(4))[0]; return ('BA', b.read(n).hex())
    if t == 8:
        n = struct.unpack('>H', b.read(2))[0]; return b.read(n).decode('utf-8', 'replace')
    if t == 9:
        et = b.read(1)[0]; n = struct.unpack('>i', b.read(4))[0]; return ('L', et, [rd(b, et) for _ in range(n)])
    if t == 10:
        d = {}
        while True:
            tt = b.read(1)[0]
            if tt == 0: return ('C', d)
            n = struct.unpack('>H', b.read(2))[0]
            k = b.read(n).decode('utf-8', 'replace'); d[k] = (tt, rd(b, tt))
    if t == 11:
        n = struct.unpack('>i', b.read(4))[0]; return ('IA', [struct.unpack('>i', b.read(4))[0] for _ in range(n)])
    if t == 12:
        n = struct.unpack('>i', b.read(4))[0]; return ('LA', [struct.unpack('>q', b.read(8))[0] for _ in range(n)])
    raise ValueError(t)

def canon(v):
    if isinstance(v, tuple):
        if v[0] == 'C': return ('C', sorted((k, tt, canon(x)) for k, (tt, x) in v[1].items()))
        if v[0] == 'L': return ('L', v[1], [canon(x) for x in v[2]])
        return v
    return v

CONT = {'minecraft:chest', 'minecraft:barrel', 'minecraft:trapped_chest'} | {
    f'minecraft:{c}shulker_box' for c in ['', 'white_', 'orange_', 'magenta_', 'light_blue_', 'yellow_', 'lime_',
                                          'pink_', 'gray_', 'light_gray_', 'cyan_', 'purple_', 'blue_', 'brown_',
                                          'green_', 'red_', 'black_']}

def timestamps(path):
    b = open(path, 'rb').read(8192)
    return struct.unpack('>1024I', b[:4096]), struct.unpack('>1024I', b[4096:8192])

def scan(path):
    raw = open(path, 'rb').read(); out = {}
    for i in range(1024):
        off = struct.unpack('>I', b'\0' + raw[i * 4:i * 4 + 3])[0] * 4096
        if off == 0: continue
        ln = struct.unpack('>I', raw[off:off + 4])[0]; ct = raw[off + 4]
        try:
            data = zlib.decompress(raw[off + 5:off + 4 + ln]) if ct == 2 else None
        except Exception:
            continue
        if data is None: continue
        b = io.BytesIO(data); t = b.read(1)[0]; n = struct.unpack('>H', b.read(2))[0]; b.read(n)
        try:
            root = rd(b, t)
        except Exception:
            continue
        bes = root[1].get('block_entities')
        if not bes: continue
        for be in bes[1][2]:
            d = be[1]; bid = d.get('id', (0, ''))[1]
            if bid not in CONT: continue
            key = f"{d.get('x',(0,0))[1]},{d.get('y',(0,0))[1]},{d.get('z',(0,0))[1]}"
            items = d.get('Items')
            # 只留「結構的雜湊」,不留整棵樹:一份 region 的 canonical 樹在 Python 裡是 GB 級,
            # 同時比五個版本會把記憶體吃光(實測 26GB 還在漲,差點拖垮同機其他伺服器)。
            # 雜湊算在 canon() 之後,所以 compound 的 key 順序不影響——那本來就不是資料差異。
            out[key] = (bid, None if items is None else hashlib.md5(repr(canon(items[1])).encode()).hexdigest())
    return out

if __name__ == '__main__':
    a = scan(sys.argv[1])
    json.dump({k: [v[0], v[1]] for k, v in a.items()}, open(sys.argv[2], 'w'))
    print(f"{sys.argv[1].split('/')[-1]}: 容器 {len(a)}")
