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
  B.on('message', m => { const t = m.toString(); if (/AlloyTesterA/.test(t)) console.log('B>', t); });
  B.on('actionBar', m => console.log('B bar>', m.toString()));
  rcon('survival', 'minecraft:tp AlloyTesterA 30.5 166 530.5'); rcon('survival', 'minecraft:tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  console.log('modes:', rcon('survival', 'data get entity AlloyTesterA playerGameType'), rcon('survival', 'data get entity AlloyTesterA Invulnerable')); rcon('survival', 'gamemode survival AlloyTesterA'); rcon('survival', 'gamemode survival AlloyTesterB'); await sleep(500);
  rcon('survival', 'kill @e[type=minecraft:item]');
  console.log('damage:', rcon('survival', 'damage AlloyTesterA 500 minecraft:player_attack by AlloyTesterB'));
  await sleep(1500);
  console.log('player head dropped:', rcon('survival', "execute if entity @e[type=minecraft:item,nbt={Item:{id:'minecraft:player_head'}}]"));
  console.log('head item:', rcon('survival', "data get entity @e[type=minecraft:item,nbt={Item:{id:'minecraft:player_head'}},limit=1] Item.components").slice(0, 260));
  process.exit(0);
})();
