#!/usr/bin/env python3
"""開過的箱子,其 chunk 是否在「開箱之後」還被寫回磁碟?
否則「開過但沒改 ⇒ 輸出等於輸入」是空洞的相同。用 trace.log 的 OPEN 時間對照輸出 mca 的 chunk 時間戳。
用法:saved_after_open.py <fixtures_dir> <out_dir>"""
import sys, os, struct, json
FX, out = sys.argv[1], sys.argv[2]
census = json.load(open(os.path.join(FX, 'census.json')))
ts = {}
for label, c in census.items():
    p = f"{out}/out/{label}/{c['reg']}.mca"
    if not os.path.exists(p): continue
    b = open(p, 'rb').read(8192); t = struct.unpack('>1024I', b[4096:8192])
    for i in range(1024):
        ts[(c['dim'], c['rx'] * 32 + i % 32, c['rz'] * 32 + i // 32)] = t[i]
opens = {}
for line in open(f'{out}/trace.log', encoding='utf-8', errors='replace'):
    p = line.rstrip('\n').split('\t')
    if len(p) < 6 or p[1] != 'OPEN' or p[2] != 'LcProbe': continue
    opens.setdefault((p[3], p[4]), int(p[0]) // 1000)
after = before = unknown = 0
for (dim, key), t_open in opens.items():
    x, y, z = map(int, key.split(',')); c = ts.get((dim, x >> 4, z >> 4))
    if c is None: unknown += 1
    elif c >= t_open: after += 1
    else: before += 1
n = len(opens)
print(f"開過的不重複容器 {n}:其 chunk 在開箱之後被寫回 = {after}({after*100//max(1,n)}%),"
      f"開箱之後沒再寫 = {before},座標不在素材 region = {unknown}")
print("VERDICT-SAVEDAFTER", "PASS" if n and after >= 0.7 * n else "FAIL(開過的箱子多數沒被重新寫回 ⇒ 混合串流沒真的測到)")
