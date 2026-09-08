#!/usr/bin/env python3
"""G7 互動對抗的單一總判定:所有證據在這裡對帳,任何一條不成立就 FINAL VERDICT FAIL(exit 1)。
用法:judge.py <fixtures_dir> <out_dir> <ops_dir> <run_start_epoch>

證據來源:
  report-mid-<label>.json  輸入 → 中段快照(bot + API + 指令之後、tick step 之前):零容忍;正控制逐格斷言;未被碰的必須結構原封
  report-out-<label>.json  中段 → 最終(tick step 之後):只允許漏斗可解釋的差異 + 全 region 物品守恆
  trace.log(伺服器端事件)、bot-result.json(bot 自己宣稱)、counters.txt(agent 計數)、boot.txt、lcops-result.txt、verify 結果
反空洞的關鍵:bot 宣稱的每一個操作都要有伺服器端事件對得上;裁判讀到的容器數要等於普查數。"""
import json, sys, os, re, collections
FX, out, opsdir, run_start = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
fails, warns = [], []
def F(m): fails.append(m); print("  FAIL " + m)
def W(m): warns.append(m); print("  WARN " + m)
def OK(m): print("  OK   " + m)
def need(path):
    if not os.path.exists(path): F(f"缺產物 {os.path.basename(path)}"); return None
    if os.path.getmtime(path) < run_start: F(f"產物 {os.path.basename(path)} 是本輪開始前的殘留"); return None
    return path

plan = json.load(open(f'{opsdir}/plan.json'))
expected = json.load(open(f'{opsdir}/expected.json'))
census = json.load(open(os.path.join(FX, 'census.json')))
LABELS = sorted(census)
AGENT_IDS = {'minecraft:chest', 'minecraft:trapped_chest', 'minecraft:barrel'} | {
    f'minecraft:{c}shulker_box' for c in ['', 'white_', 'orange_', 'magenta_', 'light_blue_', 'yellow_', 'lime_',
                                          'pink_', 'gray_', 'light_gray_', 'cyan_', 'purple_', 'blue_', 'brown_',
                                          'green_', 'red_', 'black_']}
# 機械碰過的位置(漏斗搬運 / 發射器動作的伺服器事件,由 LcOps 記錄)。
# 這關中間有 110 秒必須解凍(否則區塊不會卸載重載),那段時間世界自己的農場在動——
# 只有「有事件證據」的位置才豁免零容忍,其餘一律零容忍。這比「六鄰有漏斗」的幾何猜測準確得多。
def _mach(st, l):
    p = f'{out}/mach-{st}-{l}.txt'
    return set(x.strip() for x in open(p) if x.strip()) if os.path.exists(p) else set()
MACH_MID = {l: _mach('mid', l) for l in LABELS}
MACH_OUT = {l: _mach('out', l) for l in LABELS}
def _kinds(st, l):
    p = f'{out}/mach-{st}-{l}.kinds'
    d = {}
    if os.path.exists(p):
        for x in open(p):
            if '\t' in x: k, v = x.rstrip('\n').split('\t'); d[k] = v
    return d
KIND_MID = {l: _kinds('mid', l) for l in LABELS}
KIND_OUT = {l: _kinds('out', l) for l in LABELS}
# 每個容器在快照當下有沒有被物化(LcOps 讀活體 BE 的 lazycontainer$pending)
def _pending(st):
    p = f'{out}/pending-{st}.tsv'
    d = {}
    if os.path.exists(p):
        for x in open(p):
            parts = x.rstrip('\n').split('\t')
            if len(parts) == 3: d[(parts[0], parts[1])] = (parts[2] == 'true')
    return d
PENDING_MID = _pending('mid'); PENDING_OUT = _pending('out')
mid_epoch = int(open(f'{out}/mid-epoch.txt').read().strip()) if os.path.exists(f'{out}/mid-epoch.txt') else 0

print("== 0. 產物 ==")
for f in (['boot.txt', 'counters.txt', 'bot-result.json', 'bot.log', 'trace.log', 'lcops-result.txt', 'server.log']
          + [f"{st}/{l}/{census[l]['reg']}.mca" for st in ('mid', 'out') for l in LABELS]
          + [f'report-{st}-{l}.json' for st in ('mid', 'out') for l in LABELS]):
    need(f'{out}/{f}')
if fails:
    print("FINAL VERDICT FAIL(產物不齊)"); sys.exit(1)

print("== 1. agent / 直寫 ==")
boot = open(f'{out}/boot.txt').read()
for s in ('CompoundTag.write/copy carry raw Items', 'getBlockEntityNbtForSaving wrapped', 'transform_failed=0', 'circularity=0'):
    (OK if s in boot else F)(f"boot: {s}")
ctr = open(f'{out}/counters.txt').read().strip().split('\n')[-1]
def c(name):
    m = re.search(r'(?:^|\s)' + name + r'=(-?\d+)', ctr); return int(m.group(1)) if m else None
names = ('stash', 'rawSave', 'rawPassthrough', 'rawEmit', 'badRaw', 'ensure', 'attrPlayer', 'attrHopper',
         'attrPlugin', 'summaryMismatch', 'shadowMismatch', 'eagerLoad', 'summaryFull', 'summarySkip')
v = {n: c(n) for n in names}
print("  counters: " + " ".join(f"{k}={v[k]}" for k in names))
(OK if 'active=true' in ctr else F)("active=true")
(OK if v['rawPassthrough'] and v['rawSave'] and v['rawPassthrough'] >= 0.9 * v['rawSave'] else F)(
    f"rawPassthrough ≥ 0.9×rawSave({v['rawPassthrough']}/{v['rawSave']})")
# 直寫只涵蓋 agent 守的三型(箱/木桶/界伏盒);漏斗、發射器那些不在內,門檻要用同一個口徑
tot_agent = sum(sum(1 for x in census[l]['containers'].values() if x['id'] in AGENT_IDS) for l in LABELS)
(OK if v['rawPassthrough'] and v['rawPassthrough'] >= 0.5 * tot_agent else F)(
    f"rawPassthrough {v['rawPassthrough']} ≥ 0.5×普查(箱/木桶/界伏盒){tot_agent}")
(OK if v['rawEmit'] is not None and v['rawEmit'] >= 0.99 * v['rawPassthrough'] else F)(
    f"rawEmit {v['rawEmit']} ≥ 0.99×rawPassthrough {v['rawPassthrough']}(側車真的被寫進串流)")
for n in ('badRaw', 'summaryMismatch', 'shadowMismatch', 'eagerLoad'):
    (OK if v[n] == 0 else F)(f"{n}==0({v[n]})")
(OK if v['attrHopper'] and v['attrHopper'] > 0 else F)(f"tick step 有推進漏斗(attrHopper={v['attrHopper']})")

print("== 2. 健康 ==")
log = open(f'{out}/server.log', errors='replace').read()
for pat, name in (('VerifyError', 'VerifyError'), ('NoSuchMethodError', 'NoSuchMethodError'),
                  (r'BAD RAW', 'BAD RAW'), (r'BAD PASSTHROUGH', 'BAD PASSTHROUGH'),
                  (r'lazycontainer.*Exception|Exception.*lazycontainer', 'lazycontainer 例外')):
    n = len(re.findall(pat, log)); (OK if n == 0 else F)(f"{name}=0({n})")
n_exc = len([l for l in log.split('\n') if 'Exception' in l and 'stash=' not in l])
(OK if n_exc == 0 else W)(f"任何 Exception 行={n_exc}")
bad_files = int(open(f'{out}/badraw-files.txt').read().strip() or 0)
(OK if bad_files == 0 else F)(f"lc-badraw 檔={bad_files}")
for l in LABELS:
    for st in ('mid', 'out'):
        p = f'{out}/verify-{st}-{l}.txt'
        vtxt = open(p).read() if os.path.exists(p) else ''
        (OK if '沒有發現問題' in vtxt else F)(f"mca verify --deep {st} {l}")

print("== 3. 操作痕跡(伺服器端事件才算數)==")
ev = collections.defaultdict(list)
for line in open(f'{out}/trace.log', errors='replace'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 6: ev[p[1]].append(p)
opens = [p for p in ev['OPEN'] if p[2] == 'LcProbe' and 'cancelled' not in p[5]]
open_keys = collections.defaultdict(set)
for p in opens: open_keys[p[3]].add(p[4])
closes = {(p[3], p[4]): p[5] for p in ev['CLOSE'] if p[2] == 'LcProbe'}
# 雙箱:LcOps 對 DoubleChest 會在同一毫秒記兩行 CLOSE(double-left / double-right),用這個配對,不用猜鄰居
close_pairs = {}
_byts = collections.defaultdict(list)
for p in ev['CLOSE']:
    if p[2] == 'LcProbe' and 'double-' in p[5]: _byts[(p[0], p[3])].append(p[4])
for (_, dim), ks in _byts.items():
    if len(ks) == 2: close_pairs[(dim, ks[0])] = ks[1]; close_pairs[(dim, ks[1])] = ks[0]
clicks = collections.Counter((p[3], p[4]) for p in ev['CLICK'] if p[2] == 'LcProbe' and 'cancelled' not in p[5])
breaks = {(p[3], p[4]) for p in ev['BREAK'] if p[2] == 'LcProbe' and 'cancelled' not in p[5]}
lcops = ev['LCOPS']; cmdres = {p[2]: p[5] for p in ev['CMDRESULT']}
chunk_ev = collections.Counter((p[2], p[3]) for p in ev['CHUNK'])
print(f"  OPEN={len(opens)} 不重複={sum(len(x) for x in open_keys.values())} CLICK={sum(clicks.values())} "
      f"BREAK={len(breaks)} LCOPS={len(lcops)} CMDRESULT={len(cmdres)} CHUNK={sum(chunk_ev.values())}")
cont_of = {l: census[l]['containers'] for l in LABELS}
def label_of(dim, k):
    for l in LABELS:
        if census[l]['dim'] == dim and k in cont_of[l]: return l
    return None
per_label = collections.Counter(); per_label_ne = collections.Counter(); stray = 0
for dim, ks in open_keys.items():
    for k in ks:
        l = label_of(dim, k)
        if l is None: stray += 1; continue
        per_label[l] += 1
        if cont_of[l][k]['slots']: per_label_ne[l] += 1
(OK if stray == 0 else F)(f"OPEN 座標全部是素材 region 的容器(不是的={stray})")
for l in LABELS:
    (OK if per_label[l] >= 0.8 * plan[l]['open'] else F)(f"[{l}] 不重複開箱 {per_label[l]} ≥ 0.8×計畫 {plan[l]['open']}")
    (OK if per_label_ne[l] >= min(100, 0.8 * plan[l]['nonempty_open']) else F)(
        f"[{l}] 非空箱開箱 {per_label_ne[l]}(計畫非空 {plan[l]['nonempty_open']})")
ops = json.load(open(f'{opsdir}/ops.json'))
changed_keys = {o['key'] for o in ops if o['op'] in ('pickput', 'move', 'take')}
mismatch_seen = checked_seen = 0
for (dim, k), extra in closes.items():
    l = label_of(dim, k)
    if l is None: continue
    m = re.search(r'nonEmpty=(\d+) size=(\d+)', extra)
    if not m: continue
    seen, size = int(m.group(1)), int(m.group(2)); checked_seen += 1
    exp = len(cont_of[l][k]['slots'])
    other = close_pairs.get((dim, k)) if size == 54 else None
    if size == 54:
        if other is None or other not in cont_of[l]: continue     # 配不到另一半的雙箱不比(不猜)
        exp += len(cont_of[l][other]['slots'])
    if k in expected.get(l, {}) or k in changed_keys or k in MACH_MID[l]: continue
    if other and (other in expected.get(l, {}) or other in changed_keys or other in MACH_MID[l]): continue
    if seen != exp: mismatch_seen += 1
(OK if checked_seen > 100 and mismatch_seen <= 0.02 * checked_seen else F)(
    f"玩家關箱時看到的非空格數 = 輸入(檢查 {checked_seen},不符 {mismatch_seen})")
br = json.load(open(f'{out}/bot-result.json'))
(OK if not br['stats'].get('kicked') else F)("bot 沒被踢")
(OK if 'BOT-DONE' in open(f'{out}/bot.log').read() else F)("bot 跑到 BOT-DONE")
dim_by_key = {o['key']: o['dim'] for o in ops if o.get('key')}
claimed_open = [r for r in br['results'] if r['op'] in ('open', 'pickput', 'move', 'take') and r.get('ok')]
miss = [r['key'] for r in claimed_open if r['key'] not in open_keys.get(dim_by_key.get(r['key']), set())]
(OK if not miss else F)(f"bot 宣稱開箱 {len(claimed_open)} 全部有伺服器 OPEN(缺 {len(miss)})")
claimed_click = [r for r in br['results'] if r['op'] in ('pickput', 'move', 'take') and r.get('ok')]
nc = [r['key'] for r in claimed_click if clicks.get((dim_by_key.get(r['key']), r['key']), 0) < 2]
(OK if claimed_click and not nc else F)(f"bot 宣稱點擊 {len(claimed_click)} 全部有 ≥2 次伺服器 CLICK(缺 {len(nc)})")
claimed_dig = [r for r in br['results'] if r['op'] == 'dig' and r.get('ok')]
nd = [r['key'] for r in claimed_dig if (dim_by_key.get(r['key']), r['key']) not in breaks]
(OK if claimed_dig and not nd else F)(f"bot 宣稱挖箱 {len(claimed_dig)} 全部有伺服器 BREAK(缺 {len(nd)})")
ne_unique = sum(per_label_ne.values())
(OK if v['attrPlayer'] is not None and v['attrPlayer'] >= 0.8 * ne_unique else F)(
    f"attrPlayer={v['attrPlayer']} ≥ 0.8×非空不重複開箱 {ne_unique}(玩家開箱確實觸發物化)")
unloads = sum(n for (k, _), n in chunk_ev.items() if k == 'UNLOAD')
loads = sum(n for (k, _), n in chunk_ev.items() if k == 'LOAD')
(OK if unloads >= 50 and loads >= 50 else F)(f"素材 region 有 chunk 卸載/重載(unload={unloads} load={loads})")
reopen_keys = {o['key'] for o in ops if o.get('phase') == 'reopen'}
reopened = [k for k in reopen_keys if sum(1 for p in opens if p[4] == k) >= 2]
(OK if len(reopened) >= 0.8 * len(reopen_keys) else F)(f"重開階段:{len(reopened)}/{len(reopen_keys)} 有第二次 OPEN")
lr = [l for l in open(f'{out}/lcops-result.txt').read().strip().split('\n') if l]
napi = len([l for l in open(f'{opsdir}/lcops.txt').read().strip().split('\n') if l])
(OK if lr and all(l.startswith('OK') for l in lr) and len(lr) == napi else F)(
    f"API 操作全部 OK({sum(1 for l in lr if l.startswith('OK'))}/{napi})")
cmd_lines = [l.split('\t', 1) for l in open(f'{opsdir}/cmds.txt').read().strip().split('\n') if l]
WANT = {'datamod': 'Modified block data', 'setblock': 'Changed the block', 'itemsx': 'Modified block data',
        'itemreplace': 'Replaced a slot', 'clone': 'Successfully cloned', 'loot': 'Modified block data',
        'slot40': 'Modified block data'}
cmd_fail = []; canary_ok = canary_n = 0
for tag, text in cmd_lines:
    fb = cmdres.get(tag)
    if fb is None: cmd_fail.append(tag + ' 無 CMDRESULT'); continue
    if tag.startswith('canary'):
        canary_n += 1
        if 'Slot' in fb or 'has the following' in fb: canary_ok += 1
        else: cmd_fail.append(tag + ' 沒拿到 Items:' + fb[:80])
        continue
    want = WANT.get(tag.split(':')[0], '')
    if 'dispatched' not in fb or (want and want not in fb): cmd_fail.append(tag + ' 回饋不對:' + fb[:100])
# 有些核心(區域執行緒系)不讓外掛執行會改方塊的指令:dispatchCommand 回 true、回饋是空的、
# 方塊完全沒變。這種情況要誠實記成「此核心不支援」,不能當成 agent 的問題——
# 但**必須先證明它們一條都沒執行**。部分執行比全部失敗更危險,那仍然判紅。
cmd_targets = {k: v for l in LABELS for k, v in expected.get(l, {}).items() if v.get('via') == 'cmd'}
def _cmd_took_effect(k):
    for l in LABELS:
        if k in expected.get(l, {}):
            try: r = json.load(open(f'{out}/report-mid-{l}.json'))
            except Exception: return False
            return any(d['key'] == k for d in r['diffs'])
    return False
effected = [k for k in cmd_targets if _cmd_took_effect(k)]
all_empty_feedback = cmdres and all('feedback=' in v and v.split('feedback=')[-1].strip() == '' for v in cmdres.values())
CMD_UNSUPPORTED = bool(cmd_targets) and not effected and all_empty_feedback
if CMD_UNSUPPORTED:
    W(f"這個核心不讓外掛執行會改方塊的指令:{len(cmd_lines)} 條全部沒有回饋、目標一個都沒變 "
      f"⟹ 指令正控制與金絲雀在此核心上不適用(bot 操作與外掛 API 正控制照常判定)")
else:
    (OK if not cmd_fail else F)(f"伺服器端指令 {len(cmd_lines)} 條全部回饋成功(失敗 {len(cmd_fail)})")
    for x in cmd_fail[:8]: print("       " + x)
    (OK if canary_n and canary_ok == canary_n else F)(f"金絲雀(/data get 拿得到 Items){canary_ok}/{canary_n}")

print("== 4. 裁判 輸入→中段(零容忍 + 正控制逐格斷言)==")
def slotmap(lst): return {i: x for i, x in enumerate(lst or []) if x}
notcaught = unexpected_n = 0
clean_missing, explained_missing = [], []
for l in LABELS:
    r = json.load(open(f'{out}/report-mid-{l}.json')); e = expected.get(l, {})
    n_be = len(census[l]['containers']) + len(census[l]['movers'])   # 裁判認的是「所有帶 Items 的方塊實體」
    (OK if r['in'] == n_be else F)(f"[{l}] 裁判讀到的輸入容器數 {r['in']} == 普查 {n_be}(反空洞)")
    (OK if r['chunksIn'] == r['chunksOut'] and r['chunksIn'] > 900 else F)(f"[{l}] chunk {r['chunksIn']}/{r['chunksOut']}")
    (OK if r['nonEmptySlotsIn'] >= 0.99 * plan[l]['entries'] else F)(
        f"[{l}] 解碼非空格 {r['nonEmptySlotsIn']} ≥ 0.99×普查 entry {plan[l]['entries']}")
    (OK if r['md5In'] != r['md5Out'] else F)(f"[{l}] 輸出檔 ≠ 輸入檔(md5)")
    sd = r['untouchedStructDiffSample']; dim = census[l]['dim']
    slotdiff_keys = {d['key'] for d in r['diffs'] if d.get('slots')}
    # 結構變了但**逐格解碼完全相同** ⟹ 只是寫法不同(容器曾被物化、由原版重編碼;或卸載重載一輪)。
    # 這是 benignEncoding 的同一類,LcCompare 已經用遊戲自己的判定逐格比過了,不是資料變動。
    encoding_only = [k for k in sd if k not in slotdiff_keys]
    real_sd = [k for k in sd if k in slotdiff_keys]
    print(f"  INFO [{l}] 未被碰卻結構變了 {len(sd)}:其中逐格解碼完全相同(純寫法差異){len(encoding_only)}")
    (OK if r['untouchedN'] and not real_sd else F)(
        f"[{l}] 未被碰的容器沒有逐格內容變動(直寫保真):結構變了且逐格也不同 = {len(real_sd)}")
    for k in real_sd[:5]: print("       逐格也不同:", k, "pending=", PENDING_MID.get((dim, k)))
    got = {d['key']: d for d in r['diffs']}
    raw_unexpected = [k for k in got if k not in e]
    machined = [k for k in raw_unexpected if k in MACH_MID[l]]
    non_agent = [k for k in raw_unexpected if k not in MACH_MID[l] and got[k].get('id') not in AGENT_IDS]
    unexpected = [k for k in raw_unexpected if k not in MACH_MID[l] and got[k].get('id') in AGENT_IDS]
    unexpected_n += len(unexpected)
    print(f"  INFO [{l}] 差異 {len(raw_unexpected)}:有機械事件證據 {len(machined)}、非 agent 守的型別(漏斗等,由物品守恆管){len(non_agent)}")
    (OK if not unexpected else F)(f"[{l}] 箱/木桶/界伏盒 非預期差異(無機械證據){len(unexpected)}")
    for k in unexpected[:6]:
        d = got[k]
        print(f"       非預期 {k} {d['kind']} {d.get('id')} slots={d.get('slots')} othersSame={d.get('othersSame')}")
    bad = []
    for k, ex in e.items():
        if CMD_UNSUPPORTED and ex.get('via') == 'cmd': continue          # 指令沒執行,它的預期不成立是必然
        if CMD_UNSUPPORTED and ex.get('assert', {}).get('equals_source'): continue   # clone 產生的 EXTRA 同理
        kind = ex['kind']; x = r['expected'].get(k); d = got.get(k)
        if kind == 'SAME':
            if d is not None and d['kind'] != 'DIFF': bad.append((k, kind, 'should be SAME but ' + d['kind'])); continue
            if d is not None and d.get('slots'): bad.append((k, kind, f"slots changed {d['slots']}")); continue
            a = ex.get('assert', {})
            if a.get('others_contains') and (x is None or a['others_contains'] not in x.get('outOthers', '')):
                bad.append((k, kind, 'others missing ' + a['others_contains']))
            if a.get('out_tag_contains') and (x is None or a['out_tag_contains'] not in x.get('outItemsTag', '')):
                bad.append((k, kind, 'out tag lacks ' + a['out_tag_contains']))
        elif kind == 'MISSING':
            if d is None or d['kind'] != 'MISSING':
                if k in MACH_MID[l]: explained_missing.append(k)   # 挖掉了,但農場的發射器又放了一顆(有事件證據)
                else: bad.append((k, kind, 'not missing'))
            else: clean_missing.append(k)
        elif kind == 'EXTRA':
            if d is None or d['kind'] != 'EXTRA': bad.append((k, kind, 'not extra')); continue
            src = ex['assert']['equals_source']; xs = r['expected'].get(src)
            if xs is None or slotmap(d.get('out')) != slotmap(xs.get('in')): bad.append((k, kind, 'clone content != source'))
        elif kind == 'DIFF':
            if d is None or d['kind'] != 'DIFF': bad.append((k, kind, 'no diff')); continue
            a = ex.get('assert', {}); si, so = slotmap(x['in']), slotmap(x['out'])
            if 'slot' in a and so.get(a['slot']) != a['item']: bad.append((k, kind, f"slot {a['slot']} = {so.get(a['slot'])} want {a['item']}"))
            if a.get('all_empty') and so: bad.append((k, kind, f"not empty: {list(so.items())[:3]}"))
            if 'emptied' in a and any(s in so for s in a['emptied']): bad.append((k, kind, f"slot {a['emptied']} still present"))
            if 'moved_from' in a and (a['moved_from'] in so or so.get(a['moved_to']) != si.get(a['moved_from'])):
                bad.append((k, kind, f"move {a['moved_from']}→{a['moved_to']} 沒反映"))
            touched = set([a.get('slot'), a.get('moved_from'), a.get('moved_to')] + a.get('emptied', [])) - {None}
            if not a.get('all_empty'):
                others = {i: y for i, y in si.items() if i not in touched}
                if any(so.get(i) != y for i, y in others.items()): bad.append((k, kind, '沒被指定的格也變了'))
    notcaught += len(bad)
    (OK if not bad else F)(f"[{l}] 正控制 {len(e)} 條逐格斷言全部成立(不成立 {len(bad)})")
    for k, kind, why in bad[:8]: print(f"       未成立 {k} [{kind}] {why}")

(OK if clean_missing else F)(f"挖箱:乾淨驗到消失 {len(clean_missing)} 個;被農場機械重新放置(有事件證據){len(explained_missing)} 個")

print("== 5. 裁判 中段→最終(漏斗段)==")
for l in LABELS:
    r = json.load(open(f'{out}/report-out-{l}.json'))
    kinds = KIND_OUT[l]
    def hopper_ok(d):
        if d['kind'] != 'DIFF': return False
        if d.get('id') not in AGENT_IDS: return True                       # 非 agent 型別的內容變動歸物品守恆管
        ok_keys = set(d.get('otherKeys') or []) <= {'LootTable', 'LootTableSeed'}   # 戰利品箱被漏斗碰到 ⟹ 原版開封、標籤拿掉
        others_ok = d.get('othersSame', True) or ok_keys or ('D' in kinds.get(d['key'], ''))   # 發射器放了新界伏盒 ⟹ 身分 UUID 變
        return bool(d.get('nearHopper')) and others_ok or (ok_keys and not d.get('slots'))
    bad = [d for d in r['diffs'] if not hopper_ok(d)]
    (OK if not bad else F)(f"[{l}] 漏斗段差異 {r['diff']} 筆:箱/木桶/界伏盒的變動全部有機械事件證據且其他欄位不變(不合格 {len(bad)})")
    for d in bad[:5]: print("       ", d['key'], d['kind'], d.get('id'), 'nearHopper', d.get('nearHopper'))
    crafts = [p for p in ev['CRAFT'] if p[3] == census[l]['dim'] and int(p[0]) >= mid_epoch]
    produced = sum(int(re.search(r'amount=(\d+)', p[5]).group(1)) for p in crafts)
    consumed = sum(int(re.search(r'consumed=(\d+)', p[5]).group(1)) for p in crafts)
    expect_delta = produced - consumed
    actual_delta = r['itemsTotalOut'] - r['itemsTotalIn']
    # 種類差不等於「生出來或消失」:機器把界伏盒倒空、外掛動了物品上的標籤,都會讓
    # 同一個物品的**身分**改變(components 不同),但東西還是那些東西。真正要抓的是
    # 「某個物品 id 的淨數量變了」——那才是無中生有或憑空消失。
    per_id = collections.Counter()
    for key, (a_, b_) in r['multisetDelta'].items():
        per_id[key.split('|')[0]] += b_ - a_
    id_net = {k: v for k, v in per_id.items() if v != 0}
    (OK if actual_delta == expect_delta else F)(
        f"[{l}] 物品總數守恆:{r['itemsTotalIn']}→{r['itemsTotalOut']}(差 {actual_delta}),"
        f"合成器 {len(crafts)} 次產出 {produced} 消耗 {consumed} ⟹ 預期差 {expect_delta}")
    (OK if not id_net else F)(
        f"[{l}] 沒有任何物品 id 憑空增減(身分變動 {r['multisetDeltaKinds']} 種,淨變化不為 0 的 id:{len(id_net)})")
    for k, v in list(id_net.items())[:5]: print("       ", k, "淨變化", v)
    (OK if r['missing'] == 0 and r['extra'] == 0 else F)(f"[{l}] 漏斗段 MISSING={r['missing']} EXTRA={r['extra']}")

print("== 6. 開箱後寫回 ==")
sa = open(f'{out}/saved-after-open.txt').read() if os.path.exists(f'{out}/saved-after-open.txt') else ''
(OK if 'VERDICT-SAVEDAFTER PASS' in sa else F)("開過的箱子 ≥70% 其 chunk 在開箱之後被重新寫回")
if sa: print("    " + sa.strip().split('\n')[0][:160])

print(f"\n非預期差異合計 {unexpected_n},正控制未成立合計 {notcaught},警告 {len(warns)}")
print("FINAL VERDICT", "PASS" if not fails else f"FAIL({len(fails)} 條)")
sys.exit(0 if not fails else 1)
