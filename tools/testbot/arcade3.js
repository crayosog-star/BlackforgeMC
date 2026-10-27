// Wave tests for the phase-4 arcade modes. Usage: node arcade3.js <wave>
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd arcade "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const SKIP = /Chunk size|partial packet|joined|left the|Welcome|daily|mail|online|commands|ALLOYMC|BLACKFORGEMC|Pick a game|Skyblock -|Survival -|Factions -|Arcade -|respectful|type \/hub|^\s*$|Finding a match|Loading arena|spectating|Kit:/;
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (!SKIP.test(t)) console.log(n.replace('AlloyTester', ''), '>', t.slice(0, 140)); });
  b.on('kicked', r => console.log(n, 'kicked', JSON.stringify(r).slice(0, 100))); b.on('error', () => {}); return b; };
async function toArcade(b) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(16, 0, 0); await sleep(7000); }
const voidKill = n => rcon(`execute as AlloyTester${n} at @s run tp @s ~ ~-60 ~`);
const W = {
  1: { bots: 'ABCDEFGHI', run: async B => {
    B.A.chat('/arcade join parkour solo'); B.B.chat('/arcade join dropper solo');
    for (const n of 'CDE') B[n].chat('/arcade join tnttag');
    for (const n of 'FGHI') B[n].chat('/arcade join murder');
    await sleep(28000); console.log(rcon('arcade status'));
    console.log('--- parkour fall recovery + dropper splat');
    const pa = B.A.entity.position.clone(); voidKill('A'); await sleep(3000); console.log('A back at start?', B.A.entity.position.distanceTo(pa) < 4, B.A.entity.position.floored().toString());
    rcon('execute as AlloyTesterB at @s run tp @s ~ ~-20 ~-4'); await sleep(4000);
    console.log('--- murder: murderer stabs someone');
    const mm = 'FGHI'.split('').find(n => B[n].inventory.items().some(i => i.name === 'iron_sword'));
    console.log('murderer:', mm, '| detective:', 'FGHI'.split('').find(n => B[n].inventory.items().some(i => i.name === 'bow')));
    if (mm) { const victim = 'FGHI'.split('').find(n => n !== mm); rcon(`tp AlloyTester${mm} AlloyTester${victim}`); await sleep(1500);
      const sw = B[mm].inventory.items().find(i => i.name === 'iron_sword'); await B[mm].equip(sw, 'hand'); const t = B[mm].nearestEntity(e => e.type === 'player'); if (t) B[mm].attack(t); await sleep(2500); }
    console.log('--- waiting for the TNT Tag fuse (30s)'); await sleep(32000);
    console.log(rcon('arcade status'));
    rcon('arcade stop all'); await sleep(4000);
  } },
  2: { bots: 'ABCDEFGHI', run: async B => {
    B.A.chat('/arcade join bridge 1v1'); B.B.chat('/arcade join bridge 1v1');
    B.C.chat('/arcade join blockparty'); B.D.chat('/arcade join blockparty');
    for (const n of 'EFG') B[n].chat('/arcade join hideseek');
    B.H.chat('/arcade join speedbuilders'); B.I.chat('/arcade join speedbuilders');
    await sleep(28000); console.log(rcon('arcade status'));
    console.log('--- bridge: A jumps into the enemy goal');
    const enemyGoal = B.A.findBlock({ matching: b => b.name === 'black_concrete', maxDistance: 80, useExtraInfo: b => b.position.distanceTo(B.A.entity.position) > 20 });
    const goals = B.A.findBlocks({ matching: b => b.name === 'black_concrete', maxDistance: 90, count: 30 }).filter(p => p.distanceTo(B.A.entity.position) > 20);
    if (goals.length) { const g = goals[0]; rcon(`execute in minecraft:arcade_matches run tp AlloyTesterA ${g.x + 0.5} ${g.y + 2} ${g.z + 0.5}`); await sleep(3000); } else console.log('no enemy goal seen');
    console.log('--- hide and seek: hiders stand still to go solid; block party rounds');
    await sleep(40000);
    console.log(rcon('arcade status'));
    await sleep(25000);
    console.log(rcon('arcade status'));
    rcon('arcade stop all'); await sleep(4000);
  } },
  3: { bots: 'ABCDEFG', run: async B => {
    B.A.chat('/arcade join kitpvp'); B.B.chat('/arcade join kitpvp');
    B.D.chat('/arcade join buildbattle'); B.E.chat('/arcade join buildbattle');
    B.F.chat('/arcade join uhc'); B.G.chat('/arcade join uhc');
    await sleep(25000);
    console.log('--- late join: C joins the running FFA arena'); B.C.chat('/arcade join kitpvp'); await sleep(5000);
    console.log(rcon('arcade status'));
    console.log('--- FFA: A kills B (void) -> respawn; UHC: F into void -> G wins');
    rcon('execute as AlloyTesterB at @s run damage @s 100 minecraft:player_attack by AlloyTesterA'); await sleep(4000);
    voidKill('F'); await sleep(8000);
    console.log('--- build battle: D places a block on its plot, E tries to leave its plot');
    rcon('give AlloyTesterD minecraft:stone 1');
    console.log(rcon('arcade status'));
    rcon('arcade stop all'); await sleep(4000);
  } }
};
(async () => {
  const wave = W[process.argv[2]];
  const B = {};
  for (const n of wave.bots) { B[n] = mk('AlloyTester' + n); await once(B[n], 'spawn'); rcon(`lp user AlloyTester${n} permission set grim.exempt true`); await sleep(3600); }
  for (const n of wave.bots) await toArcade(B[n]);
  await wave.run(B);
  console.log(rcon('arcade status'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
