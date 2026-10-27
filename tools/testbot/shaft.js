const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('title', (t, k) => console.log('title', k, JSON.stringify(t).slice(0, 80)));
(async () => {
  await once(A, 'spawn'); await sleep(3000);
  A.setQuickBarSlot(4); await sleep(400); const w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(12, 0, 0); await sleep(7000);
  console.log(rcon('tp AlloyTesterA -39.5 152 -4.5'));
  for (let i = 0; i < 16; i++) { await sleep(500); console.log((i / 2).toFixed(1) + 's', A.entity.position.toArray().map(v => v.toFixed(1)).join(','), 'vy', A.entity.velocity.y.toFixed(2)); }
  console.log('block under shaft top:', rcon('execute if block -40 150 -5 minecraft:air'), rcon('execute if block -40 148 -5 minecraft:air'));
  process.exit(0);
})();
