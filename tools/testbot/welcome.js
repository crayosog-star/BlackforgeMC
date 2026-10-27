// New-player welcome, head drops and Hall of Fame boards.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' }); b.on('error', e => console.log(n, 'ERR', e.message)); return b; };
async function toS(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  // an "old" player already on survival
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(3000); await toS(B);
  B.on('message', m => { const t = m.toString(); if (/Welcome|Say hi/.test(t)) console.log('B (on survival) sees:', t); });
  // brand-new player
  const N = mk('AlloyTesterNew' + (Date.now() % 1000));
  N.on('title', (t, k) => { if (k === 'title') console.log('new player title:', JSON.stringify(t).match(/"value":"([A-Z!]+)"/)?.[1]); });
  N.on('message', m => { const t = m.toString(); if (t.trim()) console.log('new player chat:', t); });
  await once(N, 'spawn'); await sleep(6000);
  // head drop: B kills N's... use A instead: N goes to the Scrapyard with B
  await toS(N);
  rcon('survival', `minecraft:tp ${N.username} 30.5 166 530.5`); rcon('survival', 'minecraft:tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('survival', `damage ${N.username} 19`); await sleep(500);
  const t = B.nearestEntity(e => e.username === N.username);
  await B.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(900); B.attack(t); await sleep(2000);
  const heads = Object.values(B.entities).filter(e => e.name === 'item' && e.position.distanceTo(B.entity.position) < 8);
  console.log('items dropped near B after the kill:', heads.length, '| head on ground:', rcon('survival', 'execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:player_head"}}]'));
  rcon('survival', 'alloy buildsurvival'.replace('buildsurvival', 'nonexistent'));
  await sleep(1000);
  for (const [x, z, name] of [[21, -12, 'money'], [25, -17, 'mined'], [30, -19, 'kills'], [35, -17, 'playtime'], [39, -12, 'deaths']])
    console.log('board', name, '->', /Text Display/.test(rcon('survival', `execute positioned ${x} 154 ${z} if entity @e[type=minecraft:text_display,distance=..2]`)) ? 'present' : rcon('survival', `execute positioned ${x} 154 ${z} if entity @e[type=minecraft:text_display,distance=..2]`));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
