// Ranks, vouchers, perks, drop shafts and the escape warmup.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const pos = b => b.entity.position.toArray().map(v => v.toFixed(1)).join(',');
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => console.log(n, 'ERROR', e.message));
  return b;
}
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  A._client.on('abilities', p => { A._abilitiesFlags = p.flags; });
  A.on('message', m => { const t = m.toString(); if (/Rank|rank|COPPER|GOLD|monthly|Monthly|Flight|fly|Home|home|Teleport|cancel|DROP|Drop|⏳|✖|Key/.test(t) && !/joined/.test(t)) console.log('A>', t); });
  B.on('message', m => { const t = m.toString(); if (/hello there/.test(t)) console.log('B sees chat:', t); });
  rcon('minecraft:clear AlloyTesterA'); rcon('lp user AlloyTesterA parent remove copper'); rcon('lp user AlloyTesterA parent remove gold'); await sleep(1500);

  // voucher -> Copper
  console.log(rcon('alloy voucher give AlloyTesterA copper')); await sleep(800);
  const v = A.inventory.items().find(i => i.name === 'paper');
  await A.equip(v, 'hand'); A.activateItem(); await sleep(3500);
  A.chat('hello there'); await sleep(1200);
  const t = B.teamMap && B.teamMap['AlloyTesterA'];
  console.log('B sees A team:', t ? t.team + ' prefix=' + (t.prefix ? t.prefix.toString() : '?') : 'none');
  A.chat('/monthly'); await sleep(1500); A.chat('/monthly'); await sleep(1200);
  console.log('keys after monthly:', A.inventory.items().filter(i => i.name === 'tripwire_hook').map(i => i.count));
  for (let h = 1; h <= 6; h++) { A.chat('/sethome h' + h); await sleep(500); }
  await sleep(800);
  // Gold voucher -> /fly inside own claim
  console.log(rcon('alloy voucher give AlloyTesterA gold')); await sleep(800);
  const v2 = A.inventory.items().find(i => i.name === 'paper'); await A.equip(v2, 'hand'); A.activateItem(); await sleep(3000);
  rcon('tp AlloyTesterA 2500.5 140 2500.5'); await sleep(3000);
  const gy = rcon('execute positioned 2500 140 2500 run fill 2496 120 2496 2504 120 2504 minecraft:stone');
  rcon('tp AlloyTesterA 2500.5 121 2500.5'); await sleep(2000);
  A.chat('/claim'); await sleep(1200);
  A.chat('/fly'); await sleep(1200);
  console.log('allowFlight after /fly in claim:', A.player ? A.entity && A._client && A.physicsEnabled !== undefined && (A.game && A.creative) : '?', '| abilities flags', A._abilitiesFlags);

  // drop shafts
  A.chat('/spawn'); await sleep(3000);
  rcon('tp AlloyTesterA -39.5 152 -4.5'); await sleep(4000);
  console.log('after jumping in the Scrapyard shaft:', pos(A), 'health', A.health);
  await sleep(4000);
  console.log('landed at', pos(A), 'health', A.health);
  // escape warmup: /spawn from inside the Scrapyard
  A.chat('/spawn'); await sleep(4000);
  console.log('4s after /spawn (should still be in the Scrapyard):', pos(A));
  await sleep(8000);
  console.log('12s after /spawn (should be at spawn):', pos(A));
  // KOTH shaft, then try /home and move to cancel
  rcon('tp AlloyTesterA -39.5 152 5.5'); await sleep(7000);
  console.log('after KOTH shaft:', pos(A), 'health', A.health);
  A.chat('/home h1'); await sleep(2000);
  A.setControlState('forward', true); await sleep(800); A.setControlState('forward', false);
  await sleep(10000);
  console.log('after moving during warmup (should still be in KOTH arena):', pos(A));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
