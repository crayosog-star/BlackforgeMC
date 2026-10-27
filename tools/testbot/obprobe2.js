const mineflayer = require('mineflayer'); const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms)); const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const ob = c => rcon('execute in minecraft:oneblock_world run ' + c);
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('windowOpen', w => console.log('WINDOW', JSON.stringify(w.title).slice(0, 100)));
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  A.setQuickBarSlot(4); await sleep(400); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  A.chat('/ob'); await sleep(5000);
  ob('minecraft:tp AlloyTesterA 3.5 80 0.5 90 0'); await sleep(2000);
  A.setQuickBarSlot(8); await sleep(300);
  ob('setblock 4 80 2 minecraft:chest'); ob('setblock -1 80 -1 minecraft:spawner'); await sleep(1000); await A.activateBlock(A.blockAt(new Vec3(4, 80, 2))); await sleep(1500); if (A.currentWindow) A.closeWindow(A.currentWindow);
  await A.activateBlock(A.blockAt(new Vec3(-1, 80, -1))); await sleep(1500);
  console.log('spawner block seen as', A.blockAt(new Vec3(-3, 80, -3))?.name);
  await A.activateBlock(A.blockAt(new Vec3(-3, 80, -3))); await sleep(2000);
  const g = Object.values(A.entities).find(e => e !== A.entity && e.name === 'frog');
  console.log('golem', g && g.position.toString(), 'dist', g && g.position.distanceTo(A.entity.position));
  ob('summon pig 3.5 80 2.5 {NoAI:1b}'); await sleep(1500);
  const pig = Object.values(A.entities).find(e => e.name === 'pig');
  if (pig) { await A.activateEntity(pig); await sleep(1000); }
  if (g) { await A.activateEntity(g); await sleep(1500); await A.activateEntityAt(g, new Vec3(0, 0.5, 0)); await sleep(1500); }
  ob('kill @e[type=pig]');
  console.log('pos', A.entity.position.toString());
  process.exit(0);
})();
