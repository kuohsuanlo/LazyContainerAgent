#!/usr/bin/env python3
"""G5 逐格裁判判定:讀 LcCompare 報告,零容忍。
用法:judge_frozen.py <fixtures_dir> <reports_dir>
通過條件:逐格解碼差異 0、缺少 0、多出 0、物品總數相等、未被碰的容器 Items 結構原封、
         裁判讀到的容器數 == 普查數(否則就是沒真的載入)。"""
import sys, os, json
FX, R = sys.argv[1], sys.argv[2]
census = json.load(open(os.path.join(FX, 'census.json')))
fails = 0
for f in sorted(os.listdir(R)):
    if not (f.startswith('report-') and f.endswith('.json')): continue
    r = json.load(open(os.path.join(R, f))); lab = r['label']
    slotdiff = [d for d in r['diffs'] if d.get('slots')]
    otherdiff = [d for d in r['diffs'] if d['kind'] == 'DIFF' and not d.get('slots')]
    print(f"[{lab}] chunk {r['chunksIn']}/{r['chunksOut']} 容器 {r['in']}→{r['out']} same={r['same']} diff={r['diff']} "
          f"missing={r['missing']} extra={r['extra']} 物品 {r['itemsTotalIn']}→{r['itemsTotalOut']} "
          f"種類差={r['multisetDeltaKinds']} 未碰結構原封={r['untouchedStructSame']}/{r['untouchedN']}")
    def g(cond, msg):
        global fails
        print(("  OK   " if cond else "  FAIL ") + msg)
        if not cond: fails += 1
    g(not slotdiff, f"逐格解碼差異 0(實際 {len(slotdiff)})")
    for d in slotdiff[:5]: print("       ", d['key'], d.get('id'), d.get('slots'))
    g(r['missing'] == 0 and r['extra'] == 0, f"容器沒有消失/多出(missing={r['missing']} extra={r['extra']})")
    g(r['itemsTotalIn'] == r['itemsTotalOut'] and r['multisetDeltaKinds'] == 0, "物品多重集合完全相同")
    g(r['untouchedN'] > 0 and r['untouchedStructSame'] == r['untouchedN'],
      f"未被碰的容器 Items 結構逐位元組原封 {r['untouchedStructSame']}/{r['untouchedN']}")
    src = lab.split('-')[-1]
    if src in census:
        n_be = len(census[src]['containers']) + len(census[src]['movers'])
        g(r['in'] == n_be, f"裁判讀到的容器數 {r['in']} == 普查 {n_be}(反空洞)")
    if otherdiff:
        print(f"  WARN 只有非 Items 欄位不同的容器 {len(otherdiff)} 個(例:{[d['key'] for d in otherdiff[:3]]})")
print("FROZEN JUDGE", "PASS" if not fails else f"FAIL({fails} 條)")
sys.exit(0 if not fails else 1)
