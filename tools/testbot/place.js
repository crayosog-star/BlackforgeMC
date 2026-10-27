const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('message', m => console.log('>', m.toString()));
(async () => {
  await once(A, 'spawn'); await sleep(3000);
  A.setQuickBarSlot(4); await sleep(400); const w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(12, 0, 0); await sleep(7000);
  rcon('tp AlloyTesterA 3000.5 140 3000.5 0 30'); await sleep(3000);
  console.log(rcon('fill 2995 99 2995 3005 99 3005 minecraft:stone'), rcon('fill 2995 100 2995 3005 104 3005 minecraft:air'));
  rcon('tp AlloyTesterA 3000.5 100 3000.5 0 30'); await sleep(2500);
  console.log('gamemode', A.game.gameMode, 'pos', A.entity.position.toString());
  rcon('minecraft:give AlloyTesterA cobblestone 4'); console.log(rcon('alloy scrap give AlloyTesterA 2500')); await sleep(800);
  const w2 = once(A, 'windowOpen'); A.chat('/scrap'); await w2; await sleep(400); await A.clickWindow(5, 0, 0); await sleep(700); await A.clickWindow(9, 0, 0); await sleep(1000); if (A.currentWindow) A.closeWindow(A.currentWindow);
  for (const name of ['cobblestone', 'spawner']) {
    const it = A.inventory.items().find(i => i.name === name);
    if (!it) { console.log('no', name); continue; }
    await A.equip(it, 'hand');
    const x = name === 'spawner' ? 3002 : 2998;
    const floor = A.blockAt(new Vec3(x, 99, 3002));
    await A.lookAt(floor.position.offset(0.5, 1, 0.5));
    try { await A.placeBlock(floor, new Vec3(0, 1, 0)); console.log('placed', name); } catch (e) { console.log('place', name, 'err', e.message.slice(0, 80)); }
    await sleep(800);
    console.log(name, 'block now:', rcon(`execute if block ${x} 100 3002 minecraft:${name}`), rcon(`data get block ${x} 100 3002 SpawnData`).slice(0, 120));
  }
  rcon('minecraft:give AlloyTesterA diamond_pickaxe[enchantments={silk_touch:1}]'); await sleep(800);
  const pick = A.inventory.items().find(i => i.name === 'diamond_pickaxe'); await A.equip(pick, 'hand');
  try { await A.dig(A.blockAt(new Vec3(3002, 100, 3002)), true); } catch (e) { console.log('dig err', e.message); }
  await sleep(1200); rcon('tp AlloyTesterA 3002.5 100 3001.5'); await sleep(1500);
  console.log('spawner items after silk touch:', A.inventory.items().filter(i => i.name === 'spawner').length, '| block now:', rcon('execute if block 3002 100 3002 minecraft:air'));
  process.exit(0);
})();
