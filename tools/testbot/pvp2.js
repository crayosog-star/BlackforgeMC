// Focused Scrapyard checks: PvP damage, loot sampling, poured lava clean-up.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR ' + e.message.slice(0, 100); } };
const log = (...a) => console.log(...a);
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => log(n, 'ERROR', e.message));
  b.on('message', m => { const t = m.toString(); if (/lava|slain|PvP|fell|died/.test(t)) log(n, 'chat>', t); });
  return b;
}
async function toSurvival(b) {
  b.setQuickBarSlot(4); await sleep(400);
  const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300);
  await b.clickWindow(12, 0, 0); await sleep(7000);
}
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  rcon('tp AlloyTesterA 30.5 170 530.5'); await sleep(2500);
  rcon('tp AlloyTesterB 32.5 170 530.5'); await sleep(2500);
  log('A', A.entity.position.toString(), 'B', B.entity.position.toString());
  // PvP in the zone
  const hp0 = A.health;
  const ta = B.nearestEntity(e => e.username === 'AlloyTesterA');
  await B.lookAt(ta.position.offset(0, 1.5, 0)); B.attack(ta); await sleep(700);
  log('PvP in Scrapyard: A health', hp0, '->', A.health);
  // PvP at spawn should still be blocked
  rcon('tp AlloyTesterA 0.5 151 32.5'); rcon('tp AlloyTesterB 2.5 151 32.5'); await sleep(2500);
  const hp1 = A.health;
  const ta2 = B.nearestEntity(e => e.username === 'AlloyTesterA');
  await B.lookAt(ta2.position.offset(0, 1.5, 0)); B.attack(ta2); await sleep(700);
  log('PvP at spawn: A health', hp1, '->', A.health);
  // loot table sample
  log('sample:', rcon('alloy pvp sample 40'));
  // lava: pour 4 blocks ahead inside the zone, step away, check it is cleaned up
  rcon('tp AlloyTesterA 30.5 170 530.5 90 45'); await sleep(2500);
  rcon('give AlloyTesterA lava_bucket 1'); await sleep(800);
  const lb = A.inventory.items().find(i => i.name === 'lava_bucket');
  const p = A.entity.position.floored();
  let ground = null;
  for (let dy = 0; dy > -6 && !ground; dy--) { const b = A.blockAt(p.offset(-4, dy, 0)); if (b && b.boundingBox === 'block') ground = b; }
  await A.equip(lb, 'hand');
  await A.lookAt(ground.position.offset(0.5, 1, 0.5)); A.activateItem(); await sleep(800);
  const tgt = ground.position.offset(0, 1, 0);
  log('lava placed at', tgt.toString(), rcon(`execute if block ${tgt.x} ${tgt.y} ${tgt.z} minecraft:lava`));
  rcon('tp AlloyTesterA 60.5 170 560.5'); await sleep(22000);
  log('after 22s:', rcon(`execute if block ${tgt.x} ${tgt.y} ${tgt.z} minecraft:lava`));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
