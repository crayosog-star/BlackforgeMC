const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rc = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const SKIP = /Chunk size|partial packet|^\s*$/;
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' }); b.on('error', () => {});
  b.on('message', m => { const t = m.toString(); if (!SKIP.test(t)) console.log(n.slice(-1), '>', t.slice(0, 140)); }); return b; };
async function go(b, slot) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(slot, 0, 0); await sleep(8000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(3600);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(3000);
  for (const n of 'AB') rc('factions', `lp user AlloyTester${n} permission set grim.exempt true`);
  await go(A, 14); console.log('A at factions spawn:', A.entity.position.floored().toString());
  await go(B, 16); console.log('B in arcade lobby:', B.entity.position.floored().toString());
  console.log('--- arcade: B steps on the spleef plate');
  rc('arcade', 'tp AlloyTesterB 2.5 101 -12.5'); await sleep(1500);
  rc('arcade', 'tp AlloyTesterB -5.5 101 -17.5'); await sleep(2000);   // the spleef plate (north-west of the ring)
  console.log('--- factions: A creates a faction and tries to claim at spawn');
  A.chat('/f create TestLegion'); await sleep(2000); rc('factions', 'fa power set AlloyTesterA 10 test'); await sleep(1500); A.chat('/f claim'); await sleep(2000);
  A.chat('/season'); await sleep(1500);
  console.log('--- factions: A walks into the north gate');
  rc('factions', 'tp AlloyTesterA 0.5 191 -40.5 180 0'); await sleep(1000); A.setControlState('forward', true); await sleep(1200); A.setControlState('forward', false); await sleep(8000);
  console.log('A after gate:', A.entity.position.floored().toString());
  A.chat('/f claim'); await sleep(2500);
  A.chat('/spawn'); await sleep(7500);
  console.log('A after /spawn:', A.entity.position.floored().toString());
  A.chat('/f disband'); await sleep(1500); A.chat('/f disband confirm'); await sleep(1500);
  console.log(rc('factions', 'alloy season standings'));
  process.exit(0);
})();
