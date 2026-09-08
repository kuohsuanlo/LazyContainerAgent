#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""容器在磁碟上的 Items 指紋分類:缺鍵 / 空清單 / 有東西。

兩種資料事故會留下**不同**的痕跡,靠這支分開:
  沒有 Items 這個鍵  ⟹ 直寫側掛(#261)在序列化時被丟掉(容器根本沒被寫出 Items)
  Items 是空清單     ⟹ 容器的記憶體清單在存檔當下就是空的,走的是原版正常編碼

例外:空的界伏盒本來就沒有 Items(vanilla 對 allowEmpty=false 會 discard),
所以「缺鍵」要看**增量**,不能看絕對值。

用法:itemskey.py <region.mca>
"""
import sys, os, io, collections
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'tools'))
sys.path.insert(0, HERE)
import mca_restore as M
import nbtscan

path = sys.argv[1]
r = M.Region(path)
tally = collections.Counter()
samples = collections.defaultdict(list)
for idx in range(1024):
    d = r.decompressed(idx)
    if not d:
        continue
    b = io.BytesIO(d)
    if b.read(1)[0] != 10:
        continue
    b.read(int.from_bytes(b.read(2), 'big'))
    root = nbtscan.rd(b, 10)[1]
    bes = root.get('block_entities')
    if not bes:
        continue
    for _t, be in bes[1][2]:
        m = be[1] if isinstance(be, tuple) else be
        if m.get('id', (8, ''))[1] not in nbtscan.CONT:
            continue
        pos = (m.get('x', (3, 0))[1], m.get('y', (3, 0))[1], m.get('z', (3, 0))[1])
        if 'Items' not in m:
            k = 'no_items_key'
        else:
            lst = m['Items'][1]
            k = 'empty_items_list' if (lst[0] == 'L' and len(lst[2]) == 0) else 'non_empty'
        tally[k] += 1
        if k != 'non_empty' and len(samples[k]) < 6:
            samples[k].append(pos)

print(path)
for k, v in tally.most_common():
    print('  %-18s %d' % (k, v))
for k, v in samples.items():
    print('  sample %s %s' % (k, v))
