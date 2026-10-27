// Votes + Scrap Shop + spawners.
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const vote = (u, s) => { try { return execSync(`python sendvote.py ${u} ${s}`).toString().trim(); } catch (e) { return 'ERR ' + e.message.slice(0, 200); } };
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => console.log(n, 'ERROR', e.message));
  b.on('message', m => { const t = m.toString(); if (/vote|Vote|Scrap|⚙|Thanks|Bought|won|received|Spawner|VOTE/i.test(t) && !/joined|left/.test(t)) console.log(n, '>', t); });
  return b;
}
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  await toSurvival(A);
  console.log(rcon('minecraft:clear AlloyTesterA')); console.log(rcon('minecraft:clear AlloyTesterB')); await sleep(800);
  // real Votifier v2 vote while online, and one for an offline player
  console.log('vote A:', vote('AlloyTesterA', 'PlanetMinecraft')); await sleep(2500);
  console.log('vote B (offline):', vote('AlloyTesterB', 'MinecraftServers.org')); await sleep(1500);
  A.chat('/vote'); await sleep(1500);
  console.log('A items after vote:', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  // B joins later and should get the queued reward
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(3000); await toSurvival(B); await sleep(4000);
  console.log('B items (queued vote):', B.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  // open the vote crate
  rcon('tp AlloyTesterA 0.5 152 -31.5 180 20'); await sleep(2500);
  const key = A.inventory.items().find(i => i.name === 'tripwire_hook');
  await A.equip(key, 'hand');
  const vault = A.blockAt(new Vec3(0, 152, -34));
  await A.lookAt(vault.position.offset(0.5, 0.5, 0.5)); await A.activateBlock(vault); await sleep(9000);
  if (A.currentWindow) A.closeWindow(A.currentWindow);
  // scrap shop: buy a zombie spawner
  console.log(rcon('alloy scrap give AlloyTesterA 3000'));
  A.chat('/scrap bal'); await sleep(1000);
  const w = once(A, 'windowOpen'); A.chat('/scrap'); const win = await w; await sleep(400);
  console.log('shop categories:', [2, 3, 4, 5, 6].map(i => win.slots[i] && win.slots[i].name).join(','));
  await A.clickWindow(5, 0, 0); await sleep(800);   // Spawners tab
  console.log('spawner offers:', A.currentWindow.slots.slice(9, 17).map(s => s && s.name).join(','));
  await A.clickWindow(9, 0, 0); await sleep(1200);  // zombie spawner
  if (A.currentWindow) A.closeWindow(A.currentWindow);
  console.log('A items after purchase:', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  // place it in the wild and check its mob type, then silk-touch it back
  rcon('fill 2995 99 2995 3005 99 3005 minecraft:stone'); rcon('fill 2995 100 2995 3005 104 3005 minecraft:air');
  rcon('tp AlloyTesterA 3000.5 100 3000.5 0 30'); await sleep(3000);
  const sp = A.inventory.items().find(i => i.name === 'spawner');
  await A.equip(sp, 'hand');
  const floor = A.blockAt(new Vec3(3000, 99, 3002));
  await A.lookAt(floor.position.offset(0.5, 1, 0.5));
  try { await A.placeBlock(floor, new Vec3(0, 1, 0)); } catch (e) { console.log('place err', e.message); }
  await sleep(1200);
  console.log('placed spawner data:', rcon('data get block 3000 100 3002 SpawnData').slice(0, 160));
  console.log(rcon('minecraft:give AlloyTesterA diamond_pickaxe[enchantments={silk_touch:1}]')); await sleep(800);
  const pick = A.inventory.items().find(i => i.name === 'diamond_pickaxe'); await A.equip(pick, 'hand');
  try { await A.dig(A.blockAt(new Vec3(3000, 100, 3002)), true); } catch (e) { console.log('dig err', e.message); }
  await sleep(1500);
  rcon('tp AlloyTesterA 3000.5 100 3002.5'); await sleep(1500);
  console.log('A items after silk touch:', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  // scrap from a player kill
  rcon('tp AlloyTesterA 30.5 166 530.5'); rcon('tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('damage AlloyTesterB 19'); await sleep(500);
  const t = A.nearestEntity(e => e.username === 'AlloyTesterB');
  await A.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(900); A.attack(t); await sleep(1500);
  A.chat('/scrap bal'); await sleep(1200);
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
