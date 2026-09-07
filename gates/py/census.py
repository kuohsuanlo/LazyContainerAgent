#!/usr/bin/env python3
"""素材普查:每份 region 有多少容器、非空 entry、哪些格有東西、漏斗在哪。
這份數字之後當「反空洞」門檻用(裁判讀到的容器數必須等於普查數,否則就是沒真的載入)。
用法:census.py <fixtures_dir>"""
import sys, os, io, json
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools')); import mca_restore as M
sys.path.insert(0, HERE); import nbtscan

FX = sys.argv[1]
ITEMS_BE = {'minecraft:hopper', 'minecraft:dispenser', 'minecraft:dropper', 'minecraft:furnace',
            'minecraft:blast_furnace', 'minecraft:smoker', 'minecraft:crafter'}
out = {}
for line in open(os.path.join(FX, 'fixtures.tsv')):
    label, dim, reg, rx, rz = line.rstrip('\n').split('\t')
    path = os.path.join(FX, label, f'{reg}.mca')
    r = M.Region(path); cont = {}; movers = {}; chunks_with = set()
    for idx in range(1024):
        d = r.decompressed(idx)
        if d is None: continue
        for info, _, _ in M.iter_block_entities(d):
            bid = M.be_id(d, info); c = M.be_coords(d, info)
            if not c: continue
            key = f'{c[0]},{c[1]},{c[2]}'
            if bid in M.CONTAINERS or bid in ITEMS_BE:
                slots, n = [], 0
                if 'Items' in info:
                    t, es, ps, pe = info['Items']
                    b = io.BytesIO(d[ps:pe]); et = b.read(1)[0]; n = int.from_bytes(b.read(4), 'big')
                    for _ in range(n):
                        v = nbtscan.rd(b, et)
                        if isinstance(v, tuple) and v[0] == 'C':
                            sl = v[1].get('Slot')
                            if sl and isinstance(sl[1], int): slots.append(sl[1])
                rec = {'id': bid, 'slots': sorted(set(slots)), 'entries': n, 'chunk': [c[0] >> 4, c[2] >> 4]}
                (cont if bid in M.CONTAINERS else movers)[key] = rec
                chunks_with.add((c[0] >> 4, c[2] >> 4))
    out[label] = {'dim': dim, 'reg': reg, 'rx': int(rx), 'rz': int(rz), 'path': path,
                  'containers': cont, 'movers': movers, 'chunksWithContainers': len(chunks_with),
                  'nonEmptyEntries': sum(v['entries'] for v in cont.values())}
    print(f"普查 {label} {dim} {reg}: 容器 {len(cont)} 搬運型 {len(movers)} "
          f"entry {out[label]['nonEmptyEntries']} 有容器的 chunk {len(chunks_with)}")
json.dump(out, open(os.path.join(FX, 'census.json'), 'w'))
print("census.json 已寫出")
