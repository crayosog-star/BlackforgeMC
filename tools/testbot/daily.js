const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
async function toS(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); A.on('message', m => { const t = m.toString(); if (/☀|Welcome|head|Daily|daily/.test(t)) console.log('A>', t); });
  await once(A, 'spawn'); await sleep(5000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toS(A); await toS(B); await sleep(4000);
  A.chat('/daily'); await sleep(1500); A.chat('/daily'); await sleep(1500);
  // head drop: B kills A in the Scrapyard
  rcon('survival', 'minecraft:tp AlloyTesterA 30.5 166 530.5'); rcon('survival', 'minecraft:tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('survival', 'damage AlloyTesterA 19'); await sleep(500);
  const t = B.nearestEntity(e => e.username === 'AlloyTesterA'); await B.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(900); B.attack(t); await sleep(2000);
  console.log('player head on the ground:', rcon('survival', 'execute if entity @e[type=minecraft:item,nbt={Item:{id:\\"minecraft:player_head\\"}}]'));
  console.log('hall boards present:', rcon('survival', 'execute positioned 30 154 -16 if entity @e[type=minecraft:text_display,distance=..12]'));
  process.exit(0);
})();
