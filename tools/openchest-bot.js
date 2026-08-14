// 真 client 開箱測試:用底層 minecraft-protocol 宣稱 26.1 直連(mineflayer 對 26.2 走不通)。
// 目的=補兩個先前沒測到的缺口:
//   1. 玩家開啟容器 GUI 這條路徑會不會正確觸發 pending 容器物化
//   2. 雙箱(CompoundContainer)兩半的槽位映射對不對(刀一的 f1/f2 合併邏輯)
// 用法:node openchest.js <host> <port> <x> <y> <z> <期望槽位:物品:數量,...>
const mc = require('minecraft-protocol');

const [host, port, X, Y, Z, expectSpec] = process.argv.slice(2);
const expect = (expectSpec || '').split(',').filter(Boolean).map(s => {
  const [slot, name, count] = s.split(':');
  return { slot: +slot, name, count: +count };
});

const client = mc.createClient({
  host, port: +port, username: 'LcProbe', auth: 'offline', version: '26.1',
});

let opened = false;
let started = false;
const fail = m => { console.log('BOT-FAIL ' + m); try { client.end(); } catch {} process.exit(1); };
const done = () => { try { client.end(); } catch {} process.exit(0); };

client.on('error', e => fail('client error: ' + e.message));
client.on('kick_disconnect', p => fail('kicked: ' + JSON.stringify(p).slice(0, 200)));

client.on('login', () => console.log('BOT-LOGIN ok'));

// 進世界後持續重試右鍵(等外部把 bot 傳送到容器旁,超出互動距離時伺服器會忽略)
let tries = 0;
function poke() {
  if (opened) return;
  tries++;
  if (tries > 25) return fail('重試 25 次仍未開啟(距離?權限?)');
  console.log(`BOT-POKE #${tries} @ ${X},${Y},${Z}`);
  client.write('block_place', {
    hand: 0,
    location: { x: +X, y: +Y, z: +Z },
    direction: 1,
    cursorX: 0.5, cursorY: 1.0, cursorZ: 0.5,
    insideBlock: false, worldBorderHit: false, sequence: tries,
  });
  setTimeout(poke, 2000);
}

client.on('position', (p) => {
  console.log(`BOT-POS ${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}`);
  if (p.teleportId !== undefined) {
    client.write('teleport_confirm', { teleportId: p.teleportId });
  }
  if (!started) { started = true; setTimeout(poke, 1500); }
});

client.on('open_window', (p) => {
  opened = true;
  console.log(`BOT-WINDOW id=${p.windowId} type=${p.inventoryType} title=${JSON.stringify(p.windowTitle).slice(0, 60)}`);
});

client.on('window_items', (p) => {
  if (p.windowId === 0) return;           // 玩家自己的物品欄,不是我們要的
  const items = p.items || [];
  console.log(`BOT-ITEMS windowId=${p.windowId} slots=${items.length}`);
  const seen = [];
  items.forEach((it, i) => {
    if (!it || it.itemCount === 0 || it.itemId === undefined || it.itemId === 0) return;
    seen.push({ slot: i, id: it.itemId, count: it.itemCount });
  });
  console.log('BOT-NONEMPTY ' + JSON.stringify(seen));

  const md = require('minecraft-data')('26.1');
  let allOk = true;
  for (const e of expect) {
    const got = seen.find(s => s.slot === e.slot);
    if (!got) { console.log(`BOT-MISS slot=${e.slot} 期望 ${e.name}x${e.count} 但該格是空的`); allOk = false; continue; }
    const item = md.items[got.id];
    const nameOk = item && item.name === e.name;
    const countOk = got.count === e.count;
    console.log(`${nameOk && countOk ? 'BOT-OK' : 'BOT-BAD'} slot=${e.slot} got=${item ? item.name : got.id}x${got.count} want=${e.name}x${e.count}`);
    if (!nameOk || !countOk) allOk = false;
  }
  // 期望之外不應該冒出東西
  for (const s of seen) {
    if (!expect.find(e => e.slot === s.slot)) {
      const item = md.items[s.id];
      console.log(`BOT-EXTRA slot=${s.slot} ${item ? item.name : s.id}x${s.count}(期望清單沒有這格)`);
      allOk = false;
    }
  }
  console.log(allOk ? 'BOT-VERDICT PASS' : 'BOT-VERDICT FAIL');
  setTimeout(done, 500);
});

setTimeout(() => fail('timeout:沒有收到 open_window/window_items'), 45000);
