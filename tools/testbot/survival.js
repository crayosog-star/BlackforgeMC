// Survival feature test: crates, rtp, claims, homes, PvP rules, KOTH capture.
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().trim(); } catch (e) { return 'ERR ' + e.message; } };
const t0 = Date.now();
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(0).padStart(3)}s]`, ...a);
const pos = b => b.entity.position.toArray().map(v => v.toFixed(1)).join(',');
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (t.trim() && !/joined the game|left the game/.test(t)) log(n, 'chat>', t); });
  b.on('actionBar', m => { const t = m.toString(); if (t.trim()) log(n, 'bar>', t); });
  b.on('windowOpen', w => log(n, 'WINDOW', (w.title || '').toString().slice(0, 140)));
  b.on('kicked', r => log(n, 'KICKED', JSON.stringify(r).slice(0, 200)));
  b.on('error', e => log(n, 'ERROR', e.message));
  b.on('end', r => log(n, 'END', r));
  b.on('login', () => log(n, 'login ok'));
  return b;
}
async function toSurvival(b) {
  b.setQuickBarSlot(4); await sleep(400);
  const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300);
  await b.clickWindow(12, 0, 0);
  await sleep(7000);
}
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  log('A at', pos(A), 'B at', pos(B));
  log('A items', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));

  // ---- crates
  A.chat('/warp crates'); await sleep(2500);
  log('A at crates', pos(A));
  const key = A.inventory.items().find(i => i.name === 'tripwire_hook');
  if (key) {
    await A.equip(key, 'hand');
    const vault = A.blockAt(new Vec3(-9, 152, -28));
    log('vault block:', vault && vault.name);
    await A.lookAt(vault.position.offset(0.5, 0.5, 0.5));
    await A.activateBlock(vault);
    await sleep(9000);
    log('A items after crate', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  }
  // preview with left click
  try { const v = A.blockAt(new Vec3(-3, 152, -30)); A.dig(v, true).catch(() => {}); await sleep(600); A.stopDigging(); } catch (e) { log('preview err', e.message); }
  await sleep(1500);
  if (A.currentWindow) { log('preview items', A.currentWindow.slots.filter(s => s && s.slot < A.currentWindow.inventoryStart).length); A.closeWindow(A.currentWindow); }

  // ---- economy + PvP at spawn
  A.chat('/bal'); await sleep(1500);
  B.chat('/warp crates'); await sleep(2500);
  const hpBefore = A.health;
  const ta = B.nearestEntity(e => e.username === 'AlloyTesterA'); if (ta) B.attack(ta);
  await sleep(1000);
  log('A health at spawn before/after B hit:', hpBefore, A.health);

  // ---- rtp + claims
  A.chat('/rtp'); await sleep(9000);
  log('A after rtp', pos(A));
  A.chat('/claim'); await sleep(1500);
  A.chat('/claim map'); await sleep(1500);
  A.chat('/sethome base'); await sleep(1000);
  B.chat('/tpa AlloyTesterA'); await sleep(1500);
  A.chat('/tpaccept'); await sleep(5000);
  log('B after tpa', pos(B));
  const under = B.blockAt(B.entity.position.offset(0, -1, 0));
  log('B tries to break', under && under.name, 'in A claim');
  try { await Promise.race([B.dig(under, true), sleep(4000)]); } catch (e) { log('dig err', e.message); }
  await sleep(1000);
  log('block now:', B.blockAt(under.position).name);
  A.chat('/trust AlloyTesterB'); await sleep(1000);
  const under2 = B.blockAt(B.entity.position.offset(0, -1, 0));
  try { await Promise.race([B.dig(under2, true), sleep(4000)]); } catch (e) { log('dig err', e.message); }
  await sleep(800);
  log('after trust, block now:', B.blockAt(under2.position).name);
  A.chat('/spawn'); await sleep(3000);
  A.chat('/home base'); await sleep(4000);
  log('A after /home', pos(A));

  // ---- auction house: A sells a stack, B buys it
  A.chat('/spawn'); B.chat('/spawn'); await sleep(3000);
  const sellItem = A.inventory.items().find(i => i.name !== 'tripwire_hook');
  if (sellItem) { await A.equip(sellItem, 'hand'); log('A selling', sellItem.name, 'x' + sellItem.count); }
  A.chat('/ah sell 120'); await sleep(1500);
  B.chat('/bal'); await sleep(1000);
  B.chat('/ah'); await sleep(2000);
  if (B.currentWindow) {
    const first = B.currentWindow.slots[0]; log('B sees listing', first && first.name);
    await B.clickWindow(0, 0, 0); await sleep(1500);
    if (B.currentWindow) { log('confirm window'); await B.clickWindow(10, 0, 0); }
  }
  await sleep(2000);
  log('B items after buy', B.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  A.chat('/bal'); B.chat('/bal'); await sleep(1500);
  log('leaderboard', rcon('survival', 'execute positioned 17 152 33 run data get entity @e[type=minecraft:text_display,sort=nearest,limit=1] text').slice(0, 400));
  log('rules', rcon('survival', 'execute positioned -17 152 33 run data get entity @e[type=minecraft:text_display,sort=nearest,limit=1] text').slice(0, 200));
  log('border', rcon('survival', 'worldborder get'));
  for (let i = 0; i < 3; i++) { A.chat('/rtp'); await sleep(7000); log('rtp #' + (i + 1), pos(A)); log('reset cd', rcon('survival', 'op AlloyTesterA')); }

  // ---- KOTH (A is op'd so it can force-start and stand on the pad)
  log('op', rcon('survival', 'op AlloyTesterA'));
  A.chat('/koth start'); await sleep(1500);
  A.chat('/tp AlloyTesterA 0.5 158 -419.5'); await sleep(2500);
  log('A on hill', pos(A));
  B.chat('/warp koth'); await sleep(3000);
  log('B in arena', pos(B));
  B.chat('/tp AlloyTesterB AlloyTesterA'); // B is not op: should fail
  await sleep(186000);
  log('A items after koth', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  A.chat('/bal'); await sleep(1500);
  log('deop', rcon('survival', 'deop AlloyTesterA'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
