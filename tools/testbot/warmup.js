const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const spos = () => rcon('data get entity AlloyTesterA Pos').replace(/.*data: /, '').replace(/d/g, '');
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('message', m => { const t = m.toString(); if (/⏳|✖|cancel|Teleport/.test(t)) console.log('A>', t); });
(async () => {
  await once(A, 'spawn'); await sleep(3000);
  A.setQuickBarSlot(4); await sleep(400); const w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(12, 0, 0); await sleep(7000);
  // start clean at spawn (admin bypass: op + creative)
  rcon('op AlloyTesterA'); rcon('gamemode creative AlloyTesterA'); await sleep(300); rcon('minecraft:tp AlloyTesterA 0.5 151 32.5'); await sleep(500); rcon('gamemode survival AlloyTesterA'); rcon('deop AlloyTesterA'); await sleep(1500);
  console.log('start:', spos());
  // Scrapyard shaft, land, then /spawn with warmup
  rcon('minecraft:tp AlloyTesterA -39.5 153 -4.5'); await sleep(5000);
  console.log('landed in Scrapyard:', spos(), 'health', A.health);
  A.chat('/spawn'); await sleep(4000);
  console.log('4s after /spawn:', spos());
  await sleep(8000);
  console.log('12s after /spawn:', spos());
  // KOTH shaft (walk there from spawn), /rtp then move -> cancelled
  rcon('minecraft:tp AlloyTesterA -39.5 153 5.5'); await sleep(5000);
  console.log('landed in KOTH:', spos(), 'health', A.health);
  A.chat('/rtp'); await sleep(2000);
  A.setControlState('forward', true); await sleep(900); A.setControlState('forward', false);
  await sleep(10000);
  console.log('after moving during /rtp warmup (still KOTH?):', spos());
  // /hub from the arena also waits
  A.chat('/hub'); await sleep(3000);
  console.log('3s after /hub, still on survival?', /AlloyTesterA/.test(rcon('list')) ? 'yes' : 'no');
  process.exit(0);
})();
