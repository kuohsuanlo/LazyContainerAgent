#!/usr/bin/env python3
"""G4 三模式結構比對 + 反空洞閘門。
用法:structcmp.py <fixtures_dir> <out_dir> <mode1,mode2,...>
對每份素材:輸入 vs 各模式輸出、模式兩兩之間,比「解出來的 Items 結構」(compound key 排序後)。
另外檢查輸出檔的 chunk 時間戳真的變了——沒變就是根本沒載入(空洞通過)。"""
import sys, os, json
from concurrent.futures import ProcessPoolExecutor
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import nbtscan

def _scan(path):
    return path, nbtscan.scan(path)

FX, OUT, modes = sys.argv[1], sys.argv[2], sys.argv[3].split(',')
fails = []
census = json.load(open(os.path.join(FX, 'census.json')))
for label in census:
    reg = census[label]['reg']
    inp = os.path.join(FX, label, f'{reg}.mca')
    oi, ti = nbtscan.timestamps(inp)
    # 每個版本一支行程平行解:純 Python 解一份 region 要好幾分鐘,序列跑會把整關拖到一小時。
    jobs = {'in': inp}
    for m in modes:
        p = os.path.join(OUT, m, label, f'{reg}.mca')
        if not os.path.exists(p):
            print(f"FAIL [{label}] 缺 {m} 輸出"); fails.append(1); continue
        ox, tx = nbtscan.timestamps(p)
        rew = sum(1 for i in range(1024) if oi[i] and ti[i] != tx[i])
        present = sum(1 for i in range(1024) if oi[i])
        okrew = rew >= 0.9 * present
        print(f"{'OK  ' if okrew else 'FAIL'} [{label}/{m}] 真的被改寫的 chunk {rew}/{present}(<90% = 沒載入,空洞通過)")
        if not okrew: fails.append(1)
        jobs[m] = p
    with ProcessPoolExecutor(max_workers=min(5, len(jobs))) as ex:
        done = dict(ex.map(_scan, jobs.values()))
    scans = {k: done[v] for k, v in jobs.items()}
    ks = {k: len(v) for k, v in scans.items()}
    print(f"     [{label}] 容器數 " + " ".join(f"{k}={v}" for k, v in ks.items()))
    names = list(scans)
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            a, b = names[i], names[j]
            X, Y = scans[a], scans[b]
            common = [k for k in X if k in Y]
            miss = [k for k in X if k not in Y]; extra = [k for k in Y if k not in X]
            dif = [k for k in common if X[k][1] != Y[k][1]]   # [1] 是結構雜湊(canon 後才算)
            good = not dif and not miss and not extra
            # V(原版對照組)只是參考:原版是「解成 ItemStack 再重編碼」,會丟掉 Slot 越界、
            # 未知 id、count 超範圍之類的 entry。那是原版的正規化,不是 agent 的差異——
            # 出現差異反而證明 agent 保住了原版會丟掉的東西。所以帶 V 的比較只報告、不判紅。
            ctrl = 'V' in (a, b)
            tag = ('OK  ' if good else ('INFO' if ctrl else 'FAIL'))
            print(f"{tag} [{label}] {a} vs {b}:共同 {len(common)} 結構不同 {len(dif)} 只在前者 {len(miss)} 只在後者 {len(extra)}"
                  + ("   ← 原版對照組,差異僅供參考" if ctrl and not good else ""))
            for k in dif[:3]: print(f"       結構不同 @ {k} {X[k][0]}")
            for k in (miss[:3] + extra[:3]): print(f"       只有一邊有 @ {k}")
            if not good and not ctrl: fails.append(1)
print("STRUCTCMP", "PASS" if not fails else f"FAIL({len(fails)} 條)")
sys.exit(0 if not fails else 1)
