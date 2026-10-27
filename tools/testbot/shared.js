// Shared economy + shared ranks + rank kits, across Survival and Skyblock.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' }); b.on('error', e => console.log(n, 'ERR', e.message)); return b; };
async function go(b, slot) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(slot, 0, 0); await sleep(8000); }
const SURV = 12, SKY = 10;
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  A.on('message', m => { const t = m.toString(); if (/Balance|balance|Sent|sent you|kit|Kit|now Diamond|claim|Diamond|Ranks|rank:/.test(t) && !/joined/.test(t)) console.log('A>', t.slice(0, 170)); });
  B.on('message', m => { const t = m.toString(); if (/Balance|sent you|Can't/.test(t)) console.log('B>', t.slice(0, 170)); });
  await go(A, SURV); await go(B, SKY);
  // money: give A $1000 on survival, A pays B (who is on skyblock) $250
  console.log(rcon('survival', 'eco give AlloyTesterA 1000'));
  A.chat('/bal'); await sleep(1000);
  A.chat('/pay AlloyTesterB 250'); await sleep(1500);
  B.chat('/bal'); await sleep(1200);
  // scrap given on survival, visible on skyblock
  rcon('survival', 'alloy scrap give AlloyTesterA 77');
  // rank: Diamond voucher redeemed on survival...
  console.log(rcon('survival', 'alloy voucher give AlloyTesterA diamond')); await sleep(800);
  const v = A.inventory.items().find(i => i.name === 'paper'); await A.equip(v, 'hand'); A.activateItem(); await sleep(3000);
  // ...then A goes to skyblock: rank, balance and scrap should all follow
  A.chat('/hub'); await sleep(5000); await go(A, SKY);
  A.chat('/bal'); await sleep(1000);
  console.log('skyblock sees A rank perm:', rcon('skyblock', 'lp user AlloyTesterA permission checkinherits alloy.rank.diamond') || '(lp replies async)');
  A.chat('/ranks'); await sleep(1200);
  // rank kits on skyblock: Diamond (own) + Copper (below) claimable, Netherite locked, Diamond again -> cooldown
  rcon('skyblock', 'minecraft:clear AlloyTesterA'); await sleep(800);
  const kit = async (slot, label) => {
    const w = once(A, 'windowOpen'); A.chat('/kit'); const win = await w; await sleep(400);
    await A.clickWindow(slot, 0, 0); await sleep(1200);
    if (A.currentWindow) A.closeWindow(A.currentWindow);
    console.log('after', label, '-> items:', A.inventory.items().map(i => i.name.replace('_', ' ')).slice(0, 12).join(', '));
  };
  await kit(14, 'Diamond kit');
  await kit(14, 'Diamond kit again');
  rcon('skyblock', 'minecraft:clear AlloyTesterA'); await sleep(600);
  await kit(11, 'Copper kit');
  await kit(15, 'Netherite kit (locked)');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
