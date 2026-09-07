#!/usr/bin/env python3
"""G7 互動對抗:產生「操作腳本 + 逐格預期」。從普查產生,不重掃素材。
  - bot 玩家互動:open(最密的 chunk 全開)、pickput/move/take(單箱、非空、6 鄰無漏斗)、dig、卸載循環、reopen
  - 外掛面 API 操作(LcOps ops.txt)
  - 伺服器端指令正控制與金絲雀(LcOps cmds.txt,回饋文字可驗)
  - expected.json:結構化斷言 {kind, assert:{...}},判定逐格驗(不是只看「有沒有差」)
用法:genops.py <fixtures_dir> <out_ops_dir>"""
import sys, json, random, os, collections
random.seed(2626)
FX, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
C = json.load(open(os.path.join(FX, 'census.json')))
AGENT_IDS = {'minecraft:chest', 'minecraft:trapped_chest', 'minecraft:barrel'} | {
    f'minecraft:{c}shulker_box' for c in ['', 'white_', 'orange_', 'magenta_', 'light_blue_', 'yellow_', 'lime_',
                                          'pink_', 'gray_', 'light_gray_', 'cyan_', 'purple_', 'blue_', 'brown_',
                                          'green_', 'red_', 'black_']}
CHESTS = {'minecraft:chest', 'minecraft:trapped_chest'}
ops, expected, plan, apiops, cmds = [], {}, {}, [], []
def xyz(k): return tuple(map(int, k.split(',')))
def key(x, y, z): return f'{x},{y},{z}'

for label in sorted(C):
    cen = C[label]; dim = cen['dim']
    allbe = {**cen['containers'], **cen['movers']}
    cont = {k: v for k, v in cen['containers'].items() if v['id'] in AGENT_IDS}
    hoppers = {k for k, v in allbe.items() if v['id'] == 'minecraft:hopper'}
    n_open = max(120, min(320, len(cont) // 90))
    p = dict(open=n_open, pickput=max(20, n_open // 10), move=6, take=6, dig=4)
    def near_hopper6(k):
        x, y, z = xyz(k)
        return any(key(x+dx, y+dy, z+dz) in hoppers for dx, dy, dz in ((0,1,0),(0,-1,0),(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)))
    def is_double(k, v):
        if v['id'] not in CHESTS: return False
        x, y, z = xyz(k)
        return any(key(x+dx, y, z+dz) in cont and cont[key(x+dx, y, z+dz)]['id'] == v['id'] for dx, dz in ((1,0),(-1,0),(0,1),(0,-1)))
    nonempty_single = [k for k, v in cont.items() if v['slots'] and not is_double(k, v) and not near_hopper6(k)]
    nonempty_any = [k for k, v in cont.items() if v['slots'] and not near_hopper6(k)]
    random.shuffle(nonempty_single); random.shuffle(nonempty_any)
    by = collections.defaultdict(list)
    for k, v in cont.items(): by[tuple(v['chunk'])].append(k)
    dense = sorted(by.items(), key=lambda kv: -len(kv[1]))[:4]
    pool = [k for _, ks in dense for k in ks]; random.shuffle(pool)
    opens = pool[:p['open']]
    taken = set(opens)
    def grab(n, pool=None):
        pool = nonempty_single if pool is None else pool
        got = []
        while pool and len(got) < n:
            k = pool.pop()
            if k in taken: continue
            taken.add(k); got.append(k)
        return got
    e = expected.setdefault(label, {})
    for k in opens:
        x, y, z = xyz(k); ops.append({'op': 'open', 'dim': dim, 'x': x, 'y': y, 'z': z, 'key': k, 'label': label})
    for k in grab(p['pickput']):
        x, y, z = xyz(k)
        ops.append({'op': 'pickput', 'dim': dim, 'x': x, 'y': y, 'z': z, 'key': k, 'label': label, 'slots': cont[k]['slots']})
        e[k] = {'kind': 'SAME'}
    for k in grab(p['move']):
        x, y, z = xyz(k); s = cont[k]['slots']
        empties = [i for i in range(27) if i not in s]
        if not empties: continue
        ops.append({'op': 'move', 'dim': dim, 'x': x, 'y': y, 'z': z, 'key': k, 'label': label, 'slots': s})
        e[k] = {'kind': 'DIFF', 'assert': {'moved_from': s[0], 'moved_to': empties[0]}}
    for k in grab(p['take']):
        x, y, z = xyz(k); s = cont[k]['slots']
        ops.append({'op': 'take', 'dim': dim, 'x': x, 'y': y, 'z': z, 'key': k, 'label': label, 'slots': s})
        e[k] = {'kind': 'DIFF', 'assert': {'emptied': [s[0]]}}
    for k in grab(p['dig']):
        x, y, z = xyz(k); ops.append({'op': 'dig', 'dim': dim, 'x': x, 'y': y, 'z': z, 'key': k, 'label': label})
        e[k] = {'kind': 'MISSING'}
    for kind, kd in (('stateupdate-noop', {'kind': 'SAME'}),
                     ('stateupdate', {'kind': 'DIFF', 'assert': {'slot': 3, 'item': 'minecraft:stonex5'}}),
                     ('setcontents-noop', {'kind': 'SAME'}),
                     ('setcontents', {'kind': 'DIFF', 'assert': {'slot': 0, 'item': 'minecraft:diamondx1'}}),
                     ('clear', {'kind': 'DIFF', 'assert': {'all_empty': True}})):
        for k in grab(1, nonempty_any if kind.startswith('stateupdate') else None):
            x, y, z = xyz(k); apiops.append(f'{kind}\t{dim}\t{x}\t{y}\t{z}'); e[k] = dict(kd, via='api', op=kind)
    def cmd(tag, text, k, kd, feedback):
        cmds.append(f'{tag}\t{text}'); e[k] = dict(kd, via='cmd', tag=tag, feedback=feedback)
    for k in grab(2, nonempty_any):
        x, y, z = xyz(k); cmd(f'datamod:{k}', f'execute in minecraft:{dim} run data modify block {x} {y} {z} Items append value {{Slot:26b,id:"minecraft:bedrock",count:7}}',
                              k, {'kind': 'DIFF', 'assert': {'slot': 26, 'item': 'minecraft:bedrockx7'}}, 'Modified block data')
    for k in grab(2, nonempty_any):
        x, y, z = xyz(k); cmd(f'setblock:{k}', f'execute in minecraft:{dim} run setblock {x} {y} {z} minecraft:barrel{{Items:[]}}',
                              k, {'kind': 'DIFF', 'assert': {'all_empty': True}}, 'Changed the block')
    for k in grab(2, nonempty_any):
        x, y, z = xyz(k); cmd(f'itemsx:{k}', f'execute in minecraft:{dim} run data modify block {x} {y} {z} Items set value "x"',
                              k, {'kind': 'DIFF', 'assert': {'all_empty': True}}, 'Modified block data')
    for k in grab(2):
        x, y, z = xyz(k); cmd(f'itemreplace:{k}', f'execute in minecraft:{dim} run item replace block {x} {y} {z} container.5 with minecraft:stone 3',
                              k, {'kind': 'DIFF', 'assert': {'slot': 5, 'item': 'minecraft:stonex3'}}, 'Replaced a slot')
    for k in grab(2):
        x, y, z = xyz(k); cmd(f'clone:{k}', f'execute in minecraft:{dim} run clone {x} {y} {z} {x} {y} {z} {x} 250 {z}', k, {'kind': 'SAME'}, 'Successfully cloned')
        e[key(x, 250, z)] = {'kind': 'EXTRA', 'assert': {'equals_source': k}}
    for k in grab(1, nonempty_any):
        x, y, z = xyz(k); cmd(f'loot:{k}', f'execute in minecraft:{dim} run data merge block {x} {y} {z} {{LootTable:"minecraft:chests/simple_dungeon"}}',
                              k, {'kind': 'SAME', 'assert': {'others_contains': 'LootTable'}}, 'Modified block data')
    # C1:塞一筆 Slot 越界的 entry。`/data modify` 會讓容器物化,之後就是原版語意(越界被丟),
    # 所以只能斷言「解碼後逐格相同」;「原樣保留原版會丟掉的東西」這個性質由 G4 的
    # 原版對照組(in vs V 有差、in vs A/B/C 零差)在真實資料上證明。
    for k in grab(2):
        x, y, z = xyz(k); cmd(f'slot40:{k}', f'execute in minecraft:{dim} run data modify block {x} {y} {z} Items append value {{Slot:40b,id:"minecraft:stone",count:1}}',
                              k, {'kind': 'SAME'}, 'Modified block data')
    # 金絲雀:存檔視窗旗標若外洩到別的執行緒/路徑,/data get 就會拿不到 Items
    for k in grab(3, nonempty_any):
        x, y, z = xyz(k); cmds.append(f'canary:{k}\texecute in minecraft:{dim} run data get block {x} {y} {z} Items')
    plan[label] = {'open': len(opens), 'nonempty_open': sum(1 for k in opens if cont[k]['slots']),
                   'containers': len(cont), 'chunksWithContainers': cen['chunksWithContainers'],
                   'entries': cen['nonEmptyEntries'], 'dim': dim, 'censusContainers': len(cen['containers'])}

ops.append({'op': 'tp', 'dim': 'overworld', 'x': 0.5, 'y': 80, 'z': 0.5})
ops.append({'op': 'wait', 'ms': 110000})
reopen = [o for o in ops if o['op'] == 'open'][::4]
for o in reopen: ops.append(dict(o, phase='reopen'))
json.dump(ops, open(f'{OUT}/ops.json', 'w')); json.dump(expected, open(f'{OUT}/expected.json', 'w'), indent=1)
json.dump(plan, open(f'{OUT}/plan.json', 'w'), indent=1)
open(f'{OUT}/lcops.txt', 'w').write('\n'.join(apiops) + '\n')
open(f'{OUT}/cmds.txt', 'w').write('\n'.join(cmds) + '\n')
for label in expected: open(f'{OUT}/expected-keys-{label}.txt', 'w').write('\n'.join(expected[label]) + '\n')
print('ops', len(ops), dict(collections.Counter(o['op'] for o in ops)),
      'expected', {k: len(v) for k, v in expected.items()}, 'api', len(apiops), 'cmds', len(cmds), 'reopen', len(reopen))
