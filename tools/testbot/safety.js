// Tests 2026-10-27 changes: hub lava sealed + rescue + /stuck, safe Drop Zone landings, crate renames,
// silk spawners on Factions. Run with the proxy in offline mode (proxymode.py offline).
const mineflayer = require('mineflayer');
const V = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const results = [];
const check = (name, ok, info = '') => { results.push(`${ok ? 'PASS' : 'FAIL'} ${name} ${info}`); console.log(ok ? 'PASS' : 'FAIL', name, info); };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
const msgs = [];
A.on('message', m => msgs.push(m.toString()));
A.on('actionBar', m => msgs.push('AB:' + m.toString()));
const pos = () => A.entity.position;
const below = (d = 1) => { const b = A.blockAt(pos().offset(0, -d, 0)); return b ? b.name : 'none'; };
async function goServer(slot) {
  A.setQuickBarSlot(4); await sleep(300);
  const w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300);
  console.log('  window open, clicking', slot);
  await A.clickWindow(slot, 0, 0); await Promise.race([once(A, 'spawn'), sleep(15000)]); await sleep(5000);
  console.log('  now at', A.entity.position.floored());
}
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  rcon('hub', 'lp user AlloyTesterA permission set grim.exempt true');
  if (!process.env.SKIPHUB) {
  // ---- hub: standing on the sealed moat
  rcon('hub', 'minecraft:tp AlloyTesterA 5.5 101 0.5'); await sleep(2500);
  check('hub moat is walkable glass', below() === 'orange_stained_glass' && Math.abs(pos().y - 101) < 0.2, `y=${pos().y.toFixed(2)} on ${below()}`);
  check('hub moat lava is under the glass', below(2) === 'lava', below(2));
  // ---- hub: lava rescue (temporary lava pool on the grounds)
  rcon('hub', 'minecraft:tp AlloyTesterA 25.5 101 18.5'); await sleep(2000);
  const safe = pos().clone();
  rcon('hub', 'minecraft:setblock 26 101 18 minecraft:lava'); await sleep(300);
  rcon('hub', 'minecraft:tp AlloyTesterA 26.5 101 18.5'); await sleep(2500);
  check('lava contact puts you back on safe ground', pos().distanceTo(safe) < 4 && A.health >= 19.5, `at ${pos().floored()} hp=${A.health}`);
  check('lava rescue message', msgs.some(m => /molten/i.test(m)));
  rcon('hub', 'minecraft:setblock 26 101 18 minecraft:air');
  // ---- hub: /stuck
  rcon('hub', 'minecraft:tp AlloyTesterA 30.5 101 -20.5'); await sleep(1500);
  A.chat('/stuck'); await sleep(2000);
  check('/stuck returns to spawn', pos().distanceTo({ x: 0.5, y: 103, z: 0.5 }) < 3, `${pos().floored()}`);
  }
  if (!process.env.SKIPSURV) {
  // ---- survival: drop zone landings never on a roof
  await goServer(12);
  rcon('survival', 'lp user AlloyTesterA permission set grim.exempt true');
  let roofs = 0, hurt = 0;
  for (let i = 0; i < 6; i++) {
    const z = i % 2 === 0 ? -4.5 : 5.5;   // PvP shaft, KOTH shaft
    rcon('survival', `minecraft:tp AlloyTesterA -39.5 145 ${z}`);
    await sleep(1500);
    for (let t = 0; t < 40 && !A.entity.onGround; t++) await sleep(250);
    await sleep(1500);
    const solid = [1, 2, 3, 4].every(d => { const b = A.blockAt(pos().offset(0, -d, 0)); return b && b.boundingBox === 'block'; });
    if (!solid) roofs++;
    if (A.health < 19.5) hurt++;
    console.log(`  landing ${i}: ${pos().floored()} on ${below()} solid-below=${solid} hp=${A.health}`);
    rcon('survival', 'minecraft:effect give AlloyTesterA minecraft:instant_health 1 5');
    rcon('survival', 'minecraft:tp AlloyTesterA 0.5 151 32.5'); await sleep(12500);
    console.log('  back at', pos().floored());
  }
  check('drop zone lands on open ground every time', roofs === 0, `${roofs}/6 on hollow ground`);
  check('no fall damage from drops', hurt === 0, `${hurt}/6 hurt`);
  // ---- crates renamed (alias accepted)
  rcon('survival', 'minecraft:clear AlloyTesterA');
  console.log('  give:', rcon('survival', 'alloy crate give AlloyTesterA molten 1'));
  await sleep(1000);
  const key = A.inventory.items().find(i => i.name === 'tripwire_hook');
  const keyName = key ? JSON.stringify(key.customName || key.nbt || '') : 'none';
  check('molten alias gives the renamed key', key && /Molten/.test(keyName), keyName.slice(0, 80));
  }
  // ---- factions: silk spawners
  if (process.env.SKIPSURV) {} 
  A.chat('/hub'); await Promise.race([once(A, 'spawn'), sleep(15000)]); await sleep(4000);
  console.log('  hub at', pos().floored());
  await goServer(14);
  rcon('factions', 'lp user AlloyTesterA permission set grim.exempt true');
  rcon('factions', 'minecraft:gamemode survival AlloyTesterA');
  rcon('factions', 'minecraft:tp AlloyTesterA 3000 200 3000'); await sleep(3000);
  rcon('factions', 'minecraft:fill 2998 199 2998 3002 199 3002 minecraft:stone');
  rcon('factions', 'minecraft:tp AlloyTesterA 3000.5 200 3000.5'); await sleep(2000);
  rcon('factions', "minecraft:setblock 3002 200 3000 minecraft:spawner{SpawnData:{entity:{id:'minecraft:zombie'}}}");
  rcon('factions', 'minecraft:clear AlloyTesterA');
  rcon('factions', "minecraft:give AlloyTesterA minecraft:diamond_pickaxe[minecraft:enchantments={'minecraft:silk_touch':1,'minecraft:efficiency':5}]");
  await sleep(1200);
  await A.equip(A.inventory.items().find(i => i.name === 'diamond_pickaxe'), 'hand');
  const sp = A.blockAt(A.entity.position.offset(1.5, 0, -0.5).floored());
  const target = A.blockAt(V(3002, 200, 3000));
  console.log('  spawner block:', target && target.name);
  await A.lookAt(target.position.offset(0.5, 0.5, 0.5)); await A.dig(target); await sleep(2500);
  const item = A.inventory.items().find(i => i.name === 'spawner');
  check('silk touch picks up the spawner on factions', !!item, item ? JSON.stringify(item.customName || '').slice(0, 60) : 'no spawner item');
  if (item) {
    await A.equip(item, 'hand');
    const ground = A.blockAt(V(3001, 199, 3001));
    await A.placeBlock(ground, V(0, 1, 0)).catch(e => console.log('  place:', e.message)); await sleep(1500);
    const data = rcon('factions', 'data get block 3001 200 3001 SpawnData');
    check('placed spawner keeps its zombie type', /zombie/.test(data), data.slice(0, 120));
    rcon('factions', 'minecraft:setblock 3001 200 3001 minecraft:air');
  }
  rcon('factions', 'minecraft:fill 2998 199 2998 3002 200 3002 minecraft:air');
  console.log('\n' + results.join('\n'));
  process.exit(0);
})().catch(e => { console.error(e); console.log(results.join('\n')); process.exit(1); });
