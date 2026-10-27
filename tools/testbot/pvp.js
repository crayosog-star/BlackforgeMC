// Scrapyard test: /pvp entry, forced supply drop, smashing crates, loot pickup, PvP + buckets.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const t0 = Date.now();
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(0).padStart(3)}s]`, ...a);
const pos = b => b.entity.position.toArray().map(v => v.toFixed(1)).join(',');
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (t.trim() && !/joined the game|left the game|mail|Welcome,|Type \/|Players online/.test(t)) log(n, 'chat>', t); });
  b.on('error', e => log(n, 'ERROR', e.message));
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
  A.chat('/pvp'); await sleep(3000); B.chat('/pvp'); await sleep(3000);
  log('A in scrapyard', pos(A), 'B', pos(B));

  // PvP inside the zone
  rcon('tp AlloyTesterB AlloyTesterA'); await sleep(1500);
  const hp0 = A.health;
  const ta = B.nearestEntity(e => e.username === 'AlloyTesterA');
  if (ta) { await B.lookAt(ta.position.offset(0, 1.6, 0)); B.attack(ta); }
  await sleep(800);
  log('A health in scrapyard before/after hit:', hp0, A.health);

  // supply drop
  log('drop:', rcon('alloy pvp drop'));
  await sleep(2500);
  log('op', rcon('op AlloyTesterA'));
  const looted = [];
  for (let round = 0; round < 6; round++) {
    // ask the server where crates are: find the nearest copper chest to A
    const hits = A.findBlocks({ matching: b => /chest|barrel/.test(b.name), maxDistance: 128, count: 20 });
    if (!hits.length) { log('no crate visible from', pos(A)); A.chat('/tp AlloyTesterA ' + [0, 170, 520].join(' ')); await sleep(3000); continue; }
    const c = hits[0];
    A.chat(`/tp AlloyTesterA ${c.x + 1.5} ${c.y + 0.0} ${c.z + 0.5}`); await sleep(2500);
    const blk = A.blockAt(c);
    const before = A.inventory.items().map(i => i.name + 'x' + i.count).join(',');
    await A.lookAt(c.offset(0.5, 0.5, 0.5));
    try { await A.activateBlock(blk); } catch (e) { log('activate err', e.message); }
    await sleep(1200);
    // walk onto the drop spot to pick items up
    A.chat(`/tp AlloyTesterA ${c.x + 0.5} ${c.y} ${c.z + 0.5}`); await sleep(2500);
    const after = A.inventory.items().filter(i => i.slot >= 9).map(i => i.name + 'x' + i.count + (i.customName ? ' "' + i.customName.toString().replace(/\{[^}]*"text":"([^"]*)"[^}]*\}/g, '$1').slice(0, 60) + '"' : '') + (i.enchants && i.enchants.length ? ' [' + i.enchants.map(e => e.name + e.lvl).join(' ') + ']' : '')).join(' | ');
    log(`crate ${round + 1} at ${c} ->`, after);
    looted.push(after);
  }
  // buckets: pour lava in the arena, check it dries up
  rcon('give AlloyTesterA lava_bucket 1'); await sleep(800);
  const lb = A.inventory.items().find(i => i.name === 'lava_bucket');
  if (lb) {
    await A.equip(lb, 'hand');
    const ground = A.blockAt(A.entity.position.offset(3, -1, 0));
    await A.lookAt(ground.position.offset(0.5, 1, 0.5));
    A.activateItem(); await sleep(1000);
    const placed = A.blockAt(ground.position.offset(0, 1, 0));
    log('poured lava ->', placed && placed.name, 'bucket now', A.heldItem && A.heldItem.name);
    await sleep(22000);
    log('20s later ->', A.blockAt(ground.position.offset(0, 1, 0)).name);
  }
  log('deop', rcon('deop AlloyTesterA'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
