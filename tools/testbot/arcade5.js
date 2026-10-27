const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd arcade "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const SKIP = /Chunk size|partial packet|joined|left the|Welcome|daily|mail|online|commands|BLACKFORGEMC|Pick a game|Skyblock -|Survival -|Factions -|Arcade -|respectful|type \/hub|^\s*$|Finding a match|Loading arena/;
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (!SKIP.test(t)) console.log(n.replace('AlloyTester', ''), '>', t.slice(0, 140)); }); b.on('error', () => {}); return b; };
async function toArcade(b) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(16, 0, 0); await sleep(7000); }
const gm = n => rcon(`data get entity AlloyTester${n} playerGameType`).split(': ')[1];
(async () => {
  // short timers for the test (map settings; restored at the end)
  for (const m of ['workshop', 'village_market']) { rcon(`arcade set hideseek ${m} hide-seconds 10`); rcon(`arcade set hideseek ${m} time-limit 60`); }
  for (const m of ['classic_plots', 'sky_plots']) { rcon(`arcade set buildbattle ${m} build-seconds 15`); rcon(`arcade set buildbattle ${m} vote-seconds 5`); }
  const B = {};
  for (const n of 'ABCDEFG') { B[n] = mk('AlloyTester' + n); await once(B[n], 'spawn'); rcon(`lp user AlloyTester${n} permission set grim.exempt true`); await sleep(3600); }
  for (const n of 'ABCDEFG') await toArcade(B[n]);
  
  B.D.chat('/arcade join buildbattle'); B.E.chat('/arcade join buildbattle');
  
  await sleep(26000);
  const seeker = 'ABC'.split('').find(n => B[n].inventory.items().length === 0 || B[n].inventory.items().some(i => i.name === 'iron_sword'));
  const hiders = 'ABC'.split('').filter(n => n !== seeker);
  console.log('H&S seeker', seeker, 'hiders', hiders, '| hider gamemodes (3 = solid/spectator):', hiders.map(gm));
  // FFA: both walk out of spawn (tp 12 blocks away) then F hits G to death
  rcon('execute as AlloyTesterF at @s run tp @s ~12 ~5 ~12'); rcon('execute as AlloyTesterG at @s run tp @s ~13 ~5 ~12'); await sleep(4500);
  rcon('execute as AlloyTesterG at @s run damage @s 100 minecraft:player_attack by AlloyTesterF'); await sleep(3500);
  console.log('G after death:', gm('G'), '(3 = waiting to respawn)'); await sleep(2500); console.log('G respawned:', gm('G'));
  // build battle: D places a block on its plot
  await sleep(2000);
  // H&S: seeker hits a hider's disguise block after release
  await sleep(4000);
  const h = hiders.find(n => gm(n) === '3');
  if (h) {
    const pos = rcon(`data get entity AlloyTester${h} Pos`).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    if (pos) { const x = Math.floor(+pos[1]), y = Math.floor(+pos[2]), z = Math.floor(+pos[3]);
      rcon(`execute in minecraft:arcade_matches run tp AlloyTester${seeker} ${x + 0.5} ${y} ${z + 2.5} 180 30`); await sleep(2000);
      const blk = B[seeker].blockAt(new (require('vec3').Vec3)(x, y, z)); console.log('seeker sees at hider spot:', blk && blk.name);
      try { B[seeker]._client.write('block_dig', { status: 0, location: { x, y, z }, face: 1, sequence: 1 }); } catch (e) { console.log('dig err', e.message); }
      await sleep(2500); }
  } else console.log('no solid hider to find');
  // build battle voting: everyone votes "Legendary" (slot 6)
  await sleep(8000);
  for (const n of 'DE') { B[n].setQuickBarSlot(6); await sleep(200); B[n].activateItem(); }
  await sleep(14000);
  console.log(rcon('arcade status'));
  for (const m of ['workshop', 'village_market']) { rcon(`arcade set hideseek ${m} hide-seconds 30`); rcon(`arcade set hideseek ${m} time-limit 300`); }
  for (const m of ['classic_plots', 'sky_plots']) { rcon(`arcade set buildbattle ${m} build-seconds 300`); rcon(`arcade set buildbattle ${m} vote-seconds 12`); }
  rcon('arcade stop all'); await sleep(3000);
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
