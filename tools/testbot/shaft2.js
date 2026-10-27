const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('title', (t, k) => { if (k === 'title') console.log('TITLE', JSON.stringify(t).match(/"text":\{[^}]*"value":"([^"]+)"/g)); });
(async () => {
  await once(A, 'spawn'); await sleep(3000);
  A.setQuickBarSlot(4); await sleep(400); const w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(12, 0, 0); await sleep(7000);
  console.log('server pos at join:', rcon('data get entity AlloyTesterA Pos'));
  rcon('spawn AlloyTesterA'); await sleep(2500);
  console.log('server pos after /spawn:', rcon('data get entity AlloyTesterA Pos'), '| bot', A.entity.position.toString());
  rcon('minecraft:tp AlloyTesterA -39.5 153 -4.5');
  for (let i = 0; i < 10; i++) { await sleep(600); console.log('t+' + ((i + 1) * 0.6).toFixed(1), 'server', rcon('data get entity AlloyTesterA Pos').replace(/.*data: /, ''), '| bot', A.entity.position.toArray().map(v => v.toFixed(0)).join(',')); }
  process.exit(0);
})();
