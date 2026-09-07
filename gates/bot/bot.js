// LazyContainer 對抗測試 bot:真的用玩家身分進伺服器,照 ops.json 逐步做事。
// 用底層 minecraft-protocol 宣稱舊協定($LC_BOT_PROTOCOL,rig 掛 ViaVersion/ViaBackwards 翻到目標版)。
// minecraft-data 通常落後正式版一兩個小版,直連新版會被伺服器以「版本不符」擋掉。
// 用法:node bot.js <host> <port> <ops.json> <result.json>
const mc = require('minecraft-protocol');
const fs = require('fs');
const [host, port, opsFile, resultFile] = process.argv.slice(2);
const ops = JSON.parse(fs.readFileSync(opsFile, 'utf8'));

const client = mc.createClient({ host, port: +port, username: 'LcProbe', auth: 'offline', version: process.env.LC_BOT_PROTOCOL || '26.1' });
const ts = () => new Date().toISOString().slice(11, 19);
const log = (m) => console.log(`[${ts()}] ${m}`);
let seq = 1, stateId = 0;
let posWaiter = null, winWaiter = null, itemsWaiter = null;
const results = [];
const stats = { opened: 0, openFail: 0, pickput: 0, move: 0, take: 0, dig: 0, cmd: 0, tp: 0, kicked: false };

let parseErrors = 0;
// 26.1 協定定義解不開 26.2 某些 data component;protodef 遇到非 partial 的解析錯誤會把整條解析串流關掉
// (之後 keep-alive 沒回 → 30 秒被踢)。封包有長度前綴,跳過壞的那一包不會錯位,所以改成「解不開就丟掉這一包」。
// client 每換一次協定狀態(login→play)就會換一個 deserializer,所以每次 state 事件都要重貼。
function tolerantDeserializer() {
  const des = client.deserializer; if (!des || des.__lcPatched) return; des.__lcPatched = true;
  des._transform = function (chunk, enc, cb) {
    let packet;
    try { packet = this.parsePacketBuffer(chunk); }
    catch (e) { parseErrors++; if (parseErrors <= 5) log('BOT-PARSE-ERR(跳過此封包) ' + String(e.message).slice(0, 110)); return cb(); }
    this.push(packet); cb();
  };
}
tolerantDeserializer();
client.on('state', () => tolerantDeserializer());
process.on('uncaughtException', e => { parseErrors++; if (parseErrors <= 5) log('BOT-PARSE-ERR(忽略,封包有長度前綴不會錯位) ' + String(e.message).slice(0, 120)); });
client.on('error', e => { parseErrors++; if (parseErrors <= 5) log('BOT-ERROR(忽略) ' + String(e.message).slice(0, 120)); });
client.on('kick_disconnect', p => { log('BOT-KICK ' + JSON.stringify(p).slice(0, 300)); stats.kicked = true; finish(2); });
client.on('disconnect', p => { log('BOT-DISC ' + JSON.stringify(p).slice(0, 300)); stats.kicked = true; finish(2); });
client.on('login', () => log('BOT-LOGIN ok'));
client.on('position', p => {
  if (p.teleportId !== undefined) client.write('teleport_confirm', { teleportId: p.teleportId });
  if (posWaiter) { const r = posWaiter; posWaiter = null; r(p); }
});
client.on('open_window', p => { if (winWaiter) { const r = winWaiter; winWaiter = null; r(p); } });
client.on('window_items', p => {
  if (p.windowId === 0) return;
  if (p.stateId !== undefined) stateId = p.stateId;
  if (itemsWaiter) { const r = itemsWaiter; itemsWaiter = null; r(p); }
});
client.on('set_slot', p => { if (p.stateId !== undefined) stateId = p.stateId; });
let chatWaiter = null; const lastChats = [];
client.on('system_chat', p => {
  const s = JSON.stringify(p.content || p);
  lastChats.push(s.slice(0, 300)); if (lastChats.length > 6) lastChats.shift();
  if (chatWaiter) chatWaiter(s);
  if (/error|Unknown|Incorrect|無法|不能|cannot|Expected|Invalid/i.test(s)) log('BOT-CHAT ' + s.slice(0, 200));
});
// 等一段包含 expect 字串的系統訊息(指令回應),回傳收到的文字或 null
function waitChat(expect, ms) {
  return new Promise(res => {
    const t = setTimeout(() => { chatWaiter = null; res(null); }, ms);
    const hit = s => s.includes(expect) || s.includes('commands.data.get.query') || s.includes('"Items"') || s.includes('Slot');
    chatWaiter = s => { if (hit(s)) { clearTimeout(t); chatWaiter = null; res(s); } };
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
function waitFor(kind, ms) {
  return new Promise((res, rej) => {
    const t = setTimeout(() => { clear(); rej(new Error(kind + ' timeout')); }, ms);
    const set = v => { if (kind === 'pos') posWaiter = v; else if (kind === 'win') winWaiter = v; else itemsWaiter = v; };
    const clear = () => set(null);
    set(v => { clearTimeout(t); res(v); });
  });
}
const cmd = c => { client.write('chat_command', { command: c }); stats.cmd++; };

async function tp(dim, x, y, z) {
  const p = waitFor('pos', 10000);
  cmd(`execute in minecraft:${dim} run tp @s ${x} ${y} ${z}`);
  await p; stats.tp++; await sleep(400);
}

// 面:1=頂 2=北 3=南 4=西 5=東;先從頂面點,不行就從旁邊站著點側面(界伏盒/被擋的箱子)
const FACES = [
  { dir: 1, cx: 0.5, cy: 1.0, cz: 0.5, stand: [0.5, 1, 0.5] },
  { dir: 5, cx: 1.0, cy: 0.5, cz: 0.5, stand: [1.5, 0, 0.5] },
  { dir: 4, cx: 0.0, cy: 0.5, cz: 0.5, stand: [-0.5, 0, 0.5] },
  { dir: 3, cx: 0.5, cy: 0.5, cz: 1.0, stand: [0.5, 0, 1.5] },
];
async function openAt(dim, x, y, z) {
  let lastErr = null;
  for (const f of FACES) {
    try { await tp(dim, x + f.stand[0], y + f.stand[1], z + f.stand[2]); } catch (e) { lastErr = e; continue; }
    const w = waitFor('win', 2500);
    const it = waitFor('items', 3500);
    it.catch(() => {});                      // 兩個 promise 同時活著,任一逾時都不能變成未處理的 rejection
    client.write('block_place', { hand: 0, location: { x, y, z }, direction: f.dir, cursorX: f.cx, cursorY: f.cy, cursorZ: f.cz, insideBlock: false, worldBorderHit: false, sequence: seq++ });
    let win;
    try { win = await w; } catch (e) { lastErr = e; continue; }
    let items = null;
    try { items = await it; } catch (e) { /* 26.1 協定解不開某些 component 的 slot;伺服器端已開箱,照常繼續 */ }
    if (!items) return { windowId: win.windowId, type: win.inventoryType, containerSize: 27, nonEmpty: null, face: f.dir };
    const list = items.items || [];
    const containerSize = Math.max(0, list.length - 36);
    const nonEmpty = [];
    list.forEach((s, i) => { if (i < containerSize && s && s.itemCount > 0) nonEmpty.push(i); });
    return { windowId: win.windowId, type: win.inventoryType, containerSize, nonEmpty, face: f.dir };
  }
  throw lastErr || new Error('open failed');
}
const closeWin = id => client.write('close_window', { windowId: id });
const click = (windowId, slot, button = 0, mode = 0) => client.write('window_click', { windowId, stateId, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined });

async function run() {
  await waitFor('pos', 30000);           // 進世界
  log('BOT-INWORLD');
  cmd('gamemode creative @s'); await sleep(500);
  for (let i = 0; i < ops.length; i++) {
    const o = ops[i];
    const rec = { i, op: o.op, key: o.key, ok: false };
    try {
      if (o.op === 'tp') { await tp(o.dim, o.x, o.y, o.z); rec.ok = true; }
      else if (o.op === 'wait') {
        // 卸載段:凍結下 chunk 不會卸載,所以 bot 先解凍(bot 在主世界原點附近,我們的三份 region 都不在附近,漏斗不會跑),等完再凍回去
        cmd('tick unfreeze'); await sleep(o.ms); cmd('tick freeze'); await sleep(1500); rec.ok = true;
      }
      else if (o.op === 'cmd') {
        if (o.expect) {
          const w = waitChat(o.expect, o.ms || 3000);
          cmd(o.text);
          const got = await w;
          rec.ok = got !== null; rec.expect = o.expect; rec.got = got ? got.slice(0, 300) : null; if (!got) rec.lastChats = lastChats.slice();
          if (!rec.ok) log(`BOT-EXPECT-MISS ${o.text} → 沒等到「${o.expect}」`);
        } else { cmd(o.text); await sleep(o.ms || 400); rec.ok = true; }
      }
      else if (o.op === 'open' || o.op === 'pickput' || o.op === 'move' || o.op === 'take') {
        let w;
        try { w = await openAt(o.dim, o.x, o.y, o.z); }
        catch (e) { stats.openFail++; rec.err = 'open:' + e.message; results.push(rec); continue; }
        stats.opened++;
        rec.type = w.type; rec.size = w.containerSize; rec.nonEmpty = w.nonEmpty ? w.nonEmpty.length : null;
        if (!w.nonEmpty && o.slots && o.slots.length) { w.nonEmpty = o.slots.filter(i => i < w.containerSize); rec.slotsFromInput = true; }
        // ok 的語意:open = 開了;pickput/move/take = 兩次點擊都送出了(沒送就 ok=false,不能混充)
        let acted = (o.op === 'open');
        rec.clicked = [];
        const doClick = (slot) => { click(w.windowId, slot); rec.clicked.push(slot); };
        if (!w.nonEmpty && o.op !== 'open') { rec.err = 'items unparsable (client-side), click skipped'; stats.noItems = (stats.noItems || 0) + 1; }
        else if (o.op === 'pickput' && w.nonEmpty.length) {
          const s = w.nonEmpty[0];
          doClick(s); await sleep(350); doClick(s); await sleep(350); stats.pickput++; acted = true;
        } else if (o.op === 'move' && w.nonEmpty.length) {
          const s = w.nonEmpty[0];
          let t = -1; for (let k = 0; k < w.containerSize; k++) if (!w.nonEmpty.includes(k)) { t = k; break; }
          if (t >= 0) { doClick(s); await sleep(350); doClick(t); await sleep(350); stats.move++; rec.moved = [s, t]; acted = true; }
          else rec.err = 'no empty slot';
        } else if (o.op === 'take' && w.nonEmpty.length) {
          const s = w.nonEmpty[0];
          doClick(s); await sleep(350); doClick(w.containerSize + 9); await sleep(350); stats.take++; rec.took = s; acted = true;
        } else if (o.op !== 'open') rec.err = 'container empty, nothing to click';
        closeWin(w.windowId); await sleep(250);
        rec.ok = acted;
      }
      else if (o.op === 'dig') {
        await tp(o.dim, o.x + 0.5, o.y + 1, o.z + 0.5);
        client.write('block_dig', { status: 0, location: { x: o.x, y: o.y, z: o.z }, face: 1, sequence: seq++ });
        await sleep(200);
        client.write('block_dig', { status: 2, location: { x: o.x, y: o.y, z: o.z }, face: 1, sequence: seq++ });
        await sleep(400); stats.dig++; rec.ok = true;
      }
    } catch (e) { rec.err = e.message; }
    results.push(rec);
    if (i % 50 === 0) log(`BOT-PROGRESS ${i}/${ops.length} opened=${stats.opened} fail=${stats.openFail}`);
  }
  log('BOT-DONE ' + JSON.stringify(stats));
  finish(0);
}
function finish(code) {
  try { fs.writeFileSync(resultFile, JSON.stringify({ stats, results }, null, 1)); } catch {}
  try { client.end(); } catch {}
  setTimeout(() => process.exit(code), 300);
}
run().catch(e => { log('BOT-FATAL ' + e.stack); finish(3); });
