const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
async function toS(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toS(A); await toS(B);
  B.on('message', m => { const t = m.toString(); if (/head|slain|AlloyTesterA/.test(t)) console.log('B>', t); });
  rcon('survival', 'minecraft:tp AlloyTesterA 30.5 166 530.5'); rcon('survival', 'minecraft:tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('survival', 'minecraft:give AlloyTesterB netherite_sword'); await sleep(600);
  await B.equip(B.inventory.items().find(i => i.name === 'netherite_sword'), 'hand');
  rcon('survival', 'effect give AlloyTesterA minecraft:instant_health 1 10 true'); await sleep(1200); rcon('survival', 'effect clear AlloyTesterA'); rcon('survival', 'damage AlloyTesterA 14'); await sleep(1500);
  for (let i = 0; i < 3 && A.health > 0; i++) {
    const t = B.nearestEntity(e => e.username === 'AlloyTesterA'); if (!t) break;
    await B.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(1100); B.attack(t); await sleep(700);
  }
  await sleep(1500);
  const items = Object.values(B.entities).filter(e => e.name === 'item');
  console.log('items on the ground near B:', items.length);
  console.log('server: player_head item present ->', rcon('survival', 'execute positioned 31 166 530 if entity @e[type=minecraft:item,distance=..12]'), '|', rcon('survival', 'execute positioned 31 166 530 run data get entity @e[type=minecraft:item,distance=..12,limit=1] Item'));
  process.exit(0);
})();
