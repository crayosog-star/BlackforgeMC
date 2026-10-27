const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd arcade "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const KEEP = /Map selected|Winner|wins the round|ROUND|Rematch|rematch|BED DESTROYED|FINAL|ELIMINATED|\+ \$|Kit|refilled|fell|knocked|Item Shop|need/;
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (KEEP.test(t)) console.log(n.slice(-1), '>', t.slice(0, 150)); }); b.on('kicked', r => console.log(n, 'kicked', JSON.stringify(r).slice(0, 100))); return b; };
async function toArcade(b) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(16, 0, 0); await sleep(7000); }
const title = w => JSON.stringify(w.title).match(/"value":"([^"]{2,})"/g)?.map(s => s.slice(9, -1)).filter(s => !/^(string|byte|compound|list)$/.test(s)).join('') || '?';
const voidKill = n => rcon(`execute as AlloyTester${n} at @s run tp @s ~ ~-45 ~`);
(async () => {
  const B = {};
  for (const n of ['A', 'B', 'C', 'D', 'E', 'F']) { B[n] = mk('AlloyTester' + n); await once(B[n], 'spawn'); rcon(`lp user AlloyTester${n} permission set grim.exempt true`); await sleep(3600); }
  for (const n of Object.keys(B)) await toArcade(B[n]);
  console.log('=== start: duels sumo (A,B), bedwars solo (C,D), skywars solo (E,F)');
  B.A.chat('/arcade join duels sumo_1v1'); B.B.chat('/arcade join duels sumo_1v1');
  B.C.chat('/arcade join bedwars solo'); B.D.chat('/arcade join bedwars solo');
  B.E.chat('/arcade join skywars solo'); B.F.chat('/arcade join skywars solo');
  await sleep(30000);
  console.log(rcon('arcade status'));
  // --- sumo: B falls twice -> A wins 2-0
  voidKill('B'); await sleep(5000); voidKill('B'); await sleep(4000);
  // --- skywars: E opens a chest on its island
  const chest = B.E.findBlock({ matching: b => b.name === 'chest', maxDistance: 8 });
  if (chest) { const w = once(B.E, 'windowOpen'); await B.E.activateBlock(chest); const win = await Promise.race([w, sleep(3000).then(() => null)]);
    console.log('skywars chest:', win ? win.slots.slice(0, 27).filter(Boolean).map(s => s.name + 'x' + s.count).join(', ') : 'did not open'); if (B.E.currentWindow) B.E.closeWindow(B.E.currentWindow); }
  else console.log('skywars: no chest near E at', B.E.entity.position.floored());
  // --- bedwars: C buys wool, then breaks D's bed and void-kills D
  rcon('give AlloyTesterC iron_ingot 20');
  await sleep(1000);
  const npc = Object.values(B.C.entities).filter(e => e.name === 'villager').sort((a, b) => a.position.distanceTo(B.C.entity.position) - b.position.distanceTo(B.C.entity.position))[0];
  if (npc) { const w = once(B.C, 'windowOpen'); B.C.activateEntity(npc); const win = await Promise.race([w, sleep(3000).then(() => null)]); console.log('C clicked villager ->', win ? title(win) : 'no window');
    if (win) { await B.C.clickWindow(10, 0, 0); await sleep(800); if (B.C.currentWindow) B.C.closeWindow(B.C.currentWindow); } }
  console.log('C inventory:', B.C.inventory.items().map(i => i.name + 'x' + i.count).join(', '));
  const myBed = B.C.findBlock({ matching: b => b.name.endsWith('_bed'), maxDistance: 8 });
  const beds = B.C.findBlocks({ matching: b => b.name.endsWith('_bed'), maxDistance: 90, count: 40 }).map(p => B.C.blockAt(p)).filter(b => !myBed || b.name !== myBed.name);
  console.log('C own bed', myBed && myBed.name, '| enemy beds seen:', [...new Set(beds.map(b => b.name))].join(','));
  if (beds.length) { const t = beds[0].position; rcon(`execute in minecraft:arcade_matches run tp AlloyTesterC ${t.x + 0.5} ${t.y + 1} ${t.z + 2.5}`); await sleep(2500);
    try { await B.C.dig(B.C.blockAt(t), true); } catch (e) { console.log('dig failed', e.message); } await sleep(1500); }
  voidKill('D'); await sleep(9000);
  // --- skywars end
  voidKill('F'); await sleep(10000);
  console.log('=== rematch A+B');
  B.A.chat('/arcade rematch'); await sleep(800); B.B.chat('/arcade rematch'); await sleep(12000);
  console.log(rcon('arcade status'));
  console.log('A stats:', rcon('arcade stats AlloyTesterA'));
  console.log('C stats:', rcon('arcade stats AlloyTesterC'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
