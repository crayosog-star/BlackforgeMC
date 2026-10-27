const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  rcon('tp AlloyTesterA 30.5 165 530.5'); rcon('tp AlloyTesterB 32.0 165 530.5'); await sleep(3000);
  rcon('gamemode survival AlloyTesterA'); rcon('gamemode survival AlloyTesterB');
  console.log('modes', rcon('data get entity AlloyTesterA playerGameType'), '|', rcon('data get entity AlloyTesterB playerGameType'));
  for (let i = 0; i < 3; i++) {
    const ta = B.nearestEntity(e => e.username === 'AlloyTesterA');
    await B.lookAt(ta.position.offset(0, 1.5, 0), true);
    await sleep(1200);   // full attack charge
    B.attack(ta);
    await sleep(500);
    console.log('hit', i + 1, 'dist', ta.position.distanceTo(B.entity.position).toFixed(2), rcon('data get entity AlloyTesterA Health'));
  }
  process.exit(0);
})();
