const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
const title = w => JSON.stringify(w.title).match(/"value":"([^"]{3,})"/g)?.map(s => s.slice(9, -1)).join(' ') || '?';
A.on('message', m => { const t = m.toString(); if (t.trim() && !/Type \/|mail|Players online|joined the game/.test(t)) console.log('chat>', t.slice(0, 160)); });
async function openVia(fn) { const w = once(A, 'windowOpen'); fn(); const win = await Promise.race([w, sleep(3500).then(() => null)]); if (win) { console.log('  window:', title(win)); await sleep(300); A.closeWindow(win); } else console.log('  (no window)'); await sleep(500); }
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  A.setQuickBarSlot(4); await sleep(400); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  console.log('pos on skyblock:', A.entity.position.toString());
  A.chat('/daily'); await sleep(1500);
  for (const c of ['/scrap', '/ah', '/tags']) { console.log(c); await openVia(() => A.chat(c)); }
  A.chat('/ranks'); await sleep(1200);
  // market stalls
  rcon('minecraft:tp AlloyTesterA -14.5 151 31.5 0 30'); await sleep(2500);
  for (const [name, x, y, z] of [['bell', -19, 152, 33], ['forge', -14, 151, 33], ['daily chest', -9, 151, 33]]) {
    rcon(`minecraft:tp AlloyTesterA ${x + 0.5} 151 ${z - 2 + 0.5} 0 35`); await sleep(1500);
    console.log('click', name);
    await openVia(async () => { const b = A.blockAt(new Vec3(x, y, z)); await A.lookAt(b.position.offset(0.5, 0.5, 0.5)); A.activateBlock(b).catch(() => {}); });
  }
  // open the iron crate from the welcome key
  rcon('minecraft:tp AlloyTesterA -24.5 151 -5.5 90 20'); await sleep(2500);
  const key = A.inventory.items().find(i => i.name === 'tripwire_hook');
  if (key) { await A.equip(key, 'hand'); const v = A.blockAt(new Vec3(-26, 152, -6)); await A.lookAt(v.position.offset(0.5, 0.5, 0.5)); await A.activateBlock(v); await sleep(9000); if (A.currentWindow) A.closeWindow(A.currentWindow); }
  // island gate
  rcon('minecraft:tp AlloyTesterA 0.5 151 -26.5 180 0'); await sleep(2000);
  const g = once(A, 'windowOpen'); A.setControlState('forward', true); await sleep(1500); A.setControlState('forward', false);
  const gw = await Promise.race([g, sleep(4000).then(() => null)]);
  console.log('island gate ->', gw ? 'window: ' + title(gw) : 'pos ' + A.entity.position.toString());
  console.log('hall boards:', rcon('execute positioned 26 154 0 if entity @e[type=minecraft:text_display,distance=..12]'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
