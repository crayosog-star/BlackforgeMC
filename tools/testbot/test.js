// Headless end-to-end test of the AlloyMC network (proxy must be in offline mode).
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const op = (server, who) => { try { console.log('rcon', server, execSync(`python ../netctl.py cmd ${server} op ${who}`).toString().trim()); } catch (e) { console.log('rcon fail', e.message); } };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (who, ...a) => console.log(`[${new Date().toISOString().slice(11, 19)}] ${who}:`, ...a);
const plain = c => { try { if (c == null) return ''; if (typeof c === 'string') return c; const s = c.toString(); return s === '[object Object]' ? JSON.stringify(c).replace(/\"(type|value)\":/g,'').slice(0,160) : s; } catch { return String(c); } };

function mk(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: name, version: process.env.BOTVER || '26.1', auth: 'offline' });
  bot.on('message', m => { const t = m.toString(); if (t.trim()) log(name, 'chat>', t); });
  bot.on('title', (t, type) => log(name, `title(${type})>`, plain(t)));
  bot.on('kicked', r => log(name, 'KICKED', JSON.stringify(r)));
  bot.on('error', e => log(name, 'ERROR', e.message));
  bot.on('respawn', () => log(name, 'respawn/switch -> dim', bot.game.dimension));
  return bot;
}
const once = (bot, ev) => new Promise(r => bot.once(ev, r));
const pos = b => b.entity ? b.entity.position.toArray().map(v => v.toFixed(1)).join(',') : '?';
const sidebar = b => { const s = Object.values(b.scoreboards).find(x => x.position === 1) || b.scoreboard?.sidebar;
  return s ? s.items.map(i => plain(i.displayName)).join(' | ') : '(none)'; };
async function waitFor(fn, ms, step = 250) { const end = Date.now() + ms; while (Date.now() < end) { if (fn()) return true; await sleep(step); } return false; }

async function openAndClick(bot, slotItem, clickSlot) {
  bot.setQuickBarSlot(slotItem);
  await sleep(300);
  const p = once(bot, 'windowOpen');
  bot.activateItem();
  const win = await Promise.race([p, sleep(4000).then(() => null)]);
  if (!win) { log(bot.username, 'no window opened'); return; }
  log(bot.username, 'window', plain(win.title), '->', win.slots.filter(s => s && s.customName && !s.name.includes('pane')).map(s => `${s.slot}:${s.name}:${plain(s.customName)}`).join(' ; '));
  await sleep(300);
  if (clickSlot != null) await bot.clickWindow(clickSlot, 0, 0); else bot.closeWindow(win);
}

(async () => {
  const A = mk('AlloyTesterA');
  await once(A, 'spawn');
  await sleep(4000);
  const B = mk('AlloyTesterB');
  await once(B, 'spawn');
  await sleep(2500);
  op('hub', 'AlloyTesterA'); op('hub', 'AlloyTesterB');
  await sleep(500);
  log('A', 'hub pos', pos(A), 'gamemode', A.game.gameMode);
  log('A', 'inventory', A.inventory.items().map(i => `${i.slot}:${i.name}:${plain(i.customName)}`).join(' ; '));
  log('A', 'sidebar', sidebar(A));
  log('A', 'tab header', plain(A.tablist?.header));
  const holos = Object.values(A.entities).filter(e => e.name === 'text_display').length;
  log('A', 'text displays visible:', holos);

  // compass menu
  await openAndClick(A, 4, null);

  // walk into the Skyblock gate (north). Op'd via rcon before the test.
  A.chat('/tp AlloyTesterA 0.5 101 -27.5 180 0');
  await sleep(1500);
  log('A', 'at gate approach', pos(A));
  A.setControlState('forward', true);
  const switched = await waitFor(() => A.game.dimension && A._client && A.entity && Math.abs(A.entity.position.z + 27.5) > 30, 8000);
  A.setControlState('forward', false);
  await sleep(6000);
  log('A', 'after portal: pos', pos(A), 'switched?', switched);
  log('A', 'sidebar now', sidebar(A));
  A.chat('/hub');
  await sleep(5000);
  log('A', 'after /hub pos', pos(A));

  // both to the arcade via the compass menu
  await openAndClick(A, 4, 16);
  await openAndClick(B, 4, 16);
  await sleep(6000);
  op('arcade', 'AlloyTesterA'); op('arcade', 'AlloyTesterB');
  log('A', 'arcade pos', pos(A), 'inv', A.inventory.items().map(i => i.name).join(','));
  log('A', 'arcade sidebar', sidebar(A));

  // ---------------- Spleef
  await openAndClick(A, 0, 11);
  await openAndClick(B, 0, 11);
  const started = await waitFor(() => A.entity.position.x > 900, 30000);
  log('A', 'spleef started?', started, 'pos', pos(A), 'B pos', pos(B));
  log('B', 'inv', B.inventory.items().map(i => i.name).join(','), 'mode', B.game.gameMode);
  await sleep(4500);   // grace
  for (let i = 0; i < 2; i++) {
    const below = B.blockAt(B.entity.position.offset(0, -1, 0));
    log('B', 'digging', below && below.name, 'at', below && below.position.toString());
    try { await B.dig(below, true); } catch (e) { log('B', 'dig failed', e.message); }
    await sleep(2500);
    log('B', 'pos after dig', pos(B));
  }
  log('A', 'snowballs A has', A.inventory.items().filter(i => i.name === 'snowball').length);
  await waitFor(() => A.entity.position.x < 900, 15000);
  log('A', 'back in lobby?', pos(A));

  // ---------------- TNT Run
  await sleep(2000);
  await openAndClick(A, 0, 13);
  await openAndClick(B, 0, 13);
  const tntStart = await waitFor(() => A.entity.position.x > 1900, 30000);
  log('A', 'tnt run started?', tntStart, pos(A));
  await sleep(8000);
  log('A', 'tnt pos after standing still', pos(A), 'B', pos(B));
  await waitFor(() => A.entity.position.x < 900, 20000);
  log('A', 'back in lobby after tnt?', pos(A));

  // ---------------- Infected
  await sleep(2000);
  await openAndClick(A, 0, 15);
  await openAndClick(B, 0, 15);
  const infStart = await waitFor(() => A.entity.position.x > 2900, 30000);
  log('A', 'infected started?', infStart, pos(A), 'B', pos(B));
  await sleep(12000);
  const zombie = [A, B].find(b => b.inventory.slots[5] && b.inventory.slots[5].name === 'zombie_head');
  log('-', 'zombie is', zombie ? zombie.username : 'nobody');
  if (zombie) {
    const human = zombie === A ? B : A;
    zombie.chat(`/tp ${zombie.username} ${human.username}`);
    await sleep(1500);
    const target = zombie.nearestEntity(e => e.type === 'player' && e.username === human.username);
    if (target) { zombie.attack(target); log(zombie.username, 'attacked', human.username); }
  }
  await waitFor(() => A.entity.position.x < 900, 15000);
  log('A', 'back in lobby after infected?', pos(A));
  A.quit(); B.quit();
  await sleep(1000);
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
