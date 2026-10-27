// Spawner upgrades + Mob Essence, robots (fuel, miner on the OneBlock, grinder, upgrade, pick up), workshop
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const ob = c => rcon('execute in minecraft:oneblock_world run ' + c);
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
const txt = w => JSON.stringify(w.title).match(/"value":"([^"]{2,})"/g)?.map(s => s.slice(9, -1)).filter(s => !/^(string|byte|compound|list)$/.test(s)).join('') || '?';
A.on('message', m => { const t = m.toString(); if (/upgraded|Robot|robot|Fuel|Added|need|now a|picked|Essence|spawns|blocks/.test(t) && !/Welcome|ALLOYMC/.test(t)) console.log('  chat>', t.slice(0, 150)); });
async function openWin(fn) { const w = once(A, 'windowOpen'); await fn(); const win = await Promise.race([w, sleep(4000).then(() => null)]); await sleep(500); return win; }
const near = (p, r = 1.6) => Object.values(A.entities).find(e => e !== A.entity && e.type !== 'player' && e.name !== 'item' && e.position.distanceTo(p) < r);
const items = win => win.slots.slice(18, 54).filter(Boolean).map(s => s.name + 'x' + s.count).join(', ') || '(empty)';
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  A.setQuickBarSlot(4); await sleep(400); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  A.chat('/ob'); await sleep(6000);
  // clean island: platform, no mobs, no old robots
  rcon('minecraft:gamemode creative AlloyTesterA'); rcon('minecraft:clear AlloyTesterA'); rcon('eco set AlloyTesterA 900000');
  ob('kill @e[type=!player,x=0,y=80,z=0,distance=..40]');
  ob('fill -5 79 -5 5 79 5 minecraft:stone'); ob('fill -5 80 -5 5 84 5 minecraft:air'); ob('setblock 0 80 0 minecraft:dirt');
  ob('fill -6 80 -6 6 81 -6 minecraft:glass'); ob('fill -6 80 6 6 81 6 minecraft:glass'); ob('fill -6 80 -6 -6 81 6 minecraft:glass'); ob('fill 6 80 -6 6 81 6 minecraft:glass');
  ob("setblock 3 80 -3 minecraft:spawner{SpawnData:{entity:{id:'minecraft:pig'}}}");
  rcon('robots give AlloyTesterA robot 1'); rcon('robots give AlloyTesterA fuel 2'); rcon('robots give AlloyTesterA essence 3200');
  ob('minecraft:tp AlloyTesterA 3.5 80 0.5 90 0'); await sleep(2500); A.setQuickBarSlot(8); await sleep(300);
  console.log('== SPAWNER');
  let win = await openWin(() => A.activateBlock(A.blockAt(new Vec3(3, 80, -3))));
  console.log('menu:', win ? txt(win) : 'none');
  if (win) { await A.clickWindow(10, 0, 0); await sleep(1200); for (let i = 0; i < 5; i++) { await A.clickWindow(16, 0, 0); await sleep(900); } }
  console.log('MaxSpawnDelay:', ob('data get block 3 80 -3 MaxSpawnDelay').split(': ')[1]);
  if (A.currentWindow) { await A.clickWindow(31, 0, 0); await sleep(1000); console.log('mob page:', A.currentWindow ? txt(A.currentWindow) : 'none'); await A.clickWindow(4, 0, 0); await sleep(1000); }
  console.log('spawner type:', ob('data get block 3 80 -3 SpawnData.entity.id').split(': ')[1]);
  if (A.currentWindow) A.closeWindow(A.currentWindow);
  ob('summon zombie 3.5 81 -1.5 {NoAI:1b}'); // a 'spawner' mob: tag it like the spawner would by spawning from it instead:
  ob('kill @e[type=zombie]');
  await sleep(22000);
  const z = ob('execute if entity @e[type=zombie,x=3,y=80,z=-3,distance=..8]');
  console.log('zombies spawned by the spawner:', z);
  ob('kill @e[type=zombie,x=3,y=80,z=-3,distance=..8]'); await sleep(1500);
  console.log('Mob Essence dropped:', ob("execute if entity @e[type=item,nbt={Item:{id:'minecraft:echo_shard'}}]"));
  ob('kill @e[type=item]'); ob('setblock 3 80 -3 minecraft:stone');
  console.log('== ROBOT');
  const egg = A.inventory.items().find(i => /spawn_egg/.test(i.name));
  await A.equip(egg, 'hand'); await A.lookAt(new Vec3(1.5, 79.9, 0.5)); await sleep(300);
  await A.activateBlock(A.blockAt(new Vec3(1, 79, 0)), new Vec3(0, 1, 0)); await sleep(2000);
  const rp = new Vec3(1.5, 80, 0.5);
  win = await openWin(() => A.activateEntityAt(near(rp), new Vec3(0, 0.5, 0)));
  console.log('robot menu:', win ? txt(win) : 'none');
  if (win) { await A.clickWindow(2, 0, 1); await sleep(1000); A.closeWindow(win); }
  await sleep(20000);
  A.chat('/istop'); await sleep(1500);
  win = await openWin(() => A.activateEntityAt(near(rp), new Vec3(0, 0.5, 0)));
  if (win) { console.log('miner storage:', items(win)); await A.clickWindow(14, 0, 0); await sleep(1000); A.closeWindow(A.currentWindow || win); }
  ob('summon zombie 2.5 80 2.5'); ob('summon zombie 0.5 80 2.5'); ob('summon cow 2.5 80 -1.5');
  await sleep(30000);
  console.log('golems before pickup:', ob('execute if entity @e[type=copper_golem]'));
  console.log('zombies left:', ob('execute if entity @e[type=zombie,x=1,y=80,z=0,distance=..6]') || '0', '| cow left (single animals are spared):', ob('execute if entity @e[type=cow]'));
  console.log(rcon('robots charge AlloyTesterA 3'));
  win = await openWin(() => A.activateEntityAt(near(rp), new Vec3(0, 0.5, 0)));
  if (win) { console.log('grinder storage:', items(win)); await A.clickWindow(6, 0, 0); await sleep(1500); }
  console.log('after upgrade:', A.currentWindow ? txt(A.currentWindow) : 'none');
  if (A.currentWindow) { await A.clickWindow(0, 0, 0); await sleep(800); await A.clickWindow(8, 0, 0); await sleep(1500); }
  console.log('robots left on island:', ob('execute if entity @e[type=copper_golem]') || '0', '| robot item back:', A.inventory.items().filter(i => /spawn_egg/.test(i.name)).length);
  console.log('== WORKSHOP');
  win = await openWin(() => A.chat('/robots'));
  console.log('workshop:', win ? txt(win) : 'none');
  if (win) { await A.clickWindow(21, 0, 0); await sleep(1000); await A.clickWindow(13, 0, 0); await sleep(1000); }
  console.log('inventory:', A.inventory.items().map(i => i.name + 'x' + i.count).join(', '));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
