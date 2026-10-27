// OneBlock: gate at x=10 starts an island, breaking the magic block counts, /istop lists it.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('message', m => { const t = m.toString(); if (!/joined|left/.test(t) && t.trim()) console.log('A>', t.slice(0, 180)); });
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  A.setQuickBarSlot(4); await sleep(400); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  console.log('at skyblock spawn:', A.entity.position.floored().toString());
  A.chat('/istop'); await sleep(1500);
  console.log('--- walking into the OneBlock gate');
  rcon('minecraft:tp AlloyTesterA 10.5 153.5 -29.5 180 0');
  await sleep(12000);
  const win = A.currentWindow; if (win) { console.log('picker opened, clicking first option'); await A.clickWindow(win.slots.findIndex(s => s), 0, 0); await sleep(10000); }
  console.log('after gate:', A.entity.position.floored().toString(), 'dimension', A.game.dimension);
  rcon('minecraft:gamemode survival AlloyTesterA');
  let broken = 0;
  for (let i = 0; i < 6; i++) {
    const b = A.blockAt(A.entity.position.offset(0, -0.5, 0).floored());
    if (!b || b.name === 'air') { await sleep(1500); continue; }
    try { A.setQuickBarSlot(0); await A.dig(b, true); broken++; } catch (e) { console.log('dig', e.message); }
    await sleep(1500);
  }
  console.log('broke', broken, 'magic blocks');
  A.chat('/istop'); await sleep(2000);
  A.chat('/spawn'); await sleep(5000);
  console.log('--- classic gate x=-10');
  const g = once(A, 'windowOpen'); rcon('minecraft:tp AlloyTesterA -9.5 153.5 -29.5 180 0');
  const gw = await Promise.race([g, sleep(5000).then(() => null)]);
  console.log('classic gate ->', gw ? 'window opened' : 'no window', A.entity.position.floored().toString());
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
