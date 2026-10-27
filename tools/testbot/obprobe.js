const mineflayer = require('mineflayer'); const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms)); const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const ob = c => rcon('execute in minecraft:oneblock_world run ' + c);
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('message', m => console.log('A>', m.toString().slice(0, 120)));
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  A.setQuickBarSlot(4); await sleep(400); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  console.log(rcon('lp user AlloyTesterA permission set grim.exempt true'));
  A.chat('/ob'); await sleep(6000);
  console.log('pos', A.entity.position, 'dim', A.game.dimension);
  console.log(ob('fill -4 79 -4 4 79 4 minecraft:stone keep'));
  console.log(ob('setblock 0 80 0 minecraft:dirt'));
  await sleep(1000);
  for (let y = 78; y <= 81; y++) console.log(y, A.blockAt(new Vec3(3, y, 0))?.name, A.blockAt(new Vec3(0, y, 0))?.name);
  console.log(ob('minecraft:tp AlloyTesterA 3.5 80 0.5 90 0'));
  for (let i = 0; i < 6; i++) { await sleep(1000); console.log('pos', A.entity.position.toString(), 'onGround', A.entity.onGround); }
  console.log(rcon('data get entity AlloyTesterA Pos'));
  const g = Object.values(A.entities).filter(e => e.position.distanceTo(A.entity.position) < 6 && e !== A.entity);
  console.log('near ents', g.map(e => e.name + ' ' + e.type + ' ' + e.position.toString()));
  process.exit(0);
})();
