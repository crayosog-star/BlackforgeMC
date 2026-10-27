const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const hp = n => (rcon(`data get entity ${n} Health`).match(/([\d.]+)f?$/) || [])[1];
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  A.on('actionBar', m => console.log('A bar>', m.toString()));
  B.on('actionBar', m => console.log('B bar>', m.toString()));
  rcon('tp AlloyTesterA 30.5 166 530.5'); rcon('tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('attribute AlloyTesterB minecraft:max_health base set 200'); rcon('attribute AlloyTesterB minecraft:knockback_resistance base set 1'); await sleep(300);
  rcon('effect give AlloyTesterB minecraft:instant_health 1 20 true'); await sleep(800);
  const axe = A.inventory.items().find(i => i.name === 'diamond_axe'); console.log('A pos', A.entity.position.toString(), 'B pos', B.entity.position.toString(), 'axe', !!axe, 'A hp', A.health, 'mode', A.game.gameMode); await A.equip(axe, 'hand');
  for (let i = 0; i < 10; i++) {
    const t = A.nearestEntity(e => e.username === 'AlloyTesterB');
    await A.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(1000); A.attack(t);
    await sleep(150); const h0 = hp('AlloyTesterB'); await sleep(2100); console.log('hit', i + 1, 'B health right after', h0, '-> 2s later', hp('AlloyTesterB'));
  }
  rcon('attribute AlloyTesterB minecraft:max_health base set 20'); rcon('attribute AlloyTesterB minecraft:knockback_resistance base set 0');
  process.exit(0);
})();
