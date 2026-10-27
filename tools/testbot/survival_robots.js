// Survival robots: crate-only workshop, crate rewards, claim-only placement, Miner stays inside the owner's claim
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
const chat = [];
A.on('message', m => { const t = m.toString(); chat.push(t); if (/[Rr]obot|claim|Claim|Fuel|crate/.test(t) && !/Welcome/.test(t)) console.log('  chat>', t.slice(0, 160)); });
const txt = w => JSON.stringify(w.title).match(/"value":"([^"]{2,})"/g)?.map(s => s.slice(9, -1)).filter(s => !/^(string|byte|compound|list)$/.test(s)).join('') || '?';
async function openWin(fn) { const w = once(A, 'windowOpen'); await fn(); const win = await Promise.race([w, sleep(4000).then(() => null)]); await sleep(500); return win; }
const golemAt = p => Object.values(A.entities).find(e => e !== A.entity && e.type !== 'player' && e.name !== 'item' && e.position.distanceTo(p) < 1.6);
const eggs = () => A.inventory.items().filter(i => /spawn_egg/.test(i.name));
let pass = 0, fail = 0;
const check = (name, ok, extra = '') => { ok ? pass++ : fail++; console.log(ok ? 'PASS' : 'FAIL', name, extra); };
async function place(standX, x, z) {
  rcon(`minecraft:tp AlloyTesterA ${standX} 100 ${z + 0.5} -90 30`); await sleep(1500);
  await A.equip(eggs()[0], 'hand'); await sleep(300);
  await A.activateBlock(A.blockAt(new Vec3(x, 99, z)), new Vec3(0, 1, 0)); await sleep(1500);
}
(async () => {
  await once(A, 'spawn'); await sleep(3000);
  A.setQuickBarSlot(4); await sleep(400); A.activateItem(); await sleep(1500);
  if (A.currentWindow) await A.clickWindow(12, 0, 0);
  await sleep(7000);
  rcon('minecraft:gamemode creative AlloyTesterA'); rcon('minecraft:clear AlloyTesterA'); rcon('eco set AlloyTesterA 900000');

  console.log('== WORKSHOP');
  let win = await openWin(() => A.chat('/robots'));
  check('workshop opens', !!win, win ? txt(win) : '');
  if (win) { await A.clickWindow(13, 0, 0); await sleep(1000); A.closeWindow(A.currentWindow || win); }
  check('workshop refuses to sell a robot', eggs().length === 0 && chat.some(t => /can only be won/.test(t)));

  console.log('== CRATE PREVIEW');
  rcon('minecraft:gamemode survival AlloyTesterA'); rcon('minecraft:tp AlloyTesterA -2.5 152 -26.5 180 20'); await sleep(1500);
  const molten = A.blockAt(new Vec3(-3, 152, -30));
  await A.lookAt(molten.position.offset(0.5, 0.5, 0.5));
  win = await openWin(async () => A._client.write('block_dig', { status: 0, location: molten.position, face: 3, sequence: 1 }));
  const shown = win ? win.slots.slice(0, win.inventoryStart).filter(Boolean) : [];
  console.log('  preview:', win ? txt(win) : 'none', shown.length, 'rewards');
  check('Molten preview lists the robots', shown.some(s => JSON.stringify(s.nbt || '').includes('Robot')), shown.map(s => s.name).join(','));
  if (A.currentWindow) A.closeWindow(A.currentWindow);

  rcon('minecraft:gamemode creative AlloyTesterA');
  console.log('== CRATE REWARD COMMANDS');
  const cfg = require('fs').readFileSync('../../survival/plugins/AlloyCore/crates.yml', 'utf8');
  const cmds = [...cfg.matchAll(/"(robots give \{player\} [^"]+)"/g)].map(m => m[1].replace('{player}', 'AlloyTesterA'));
  for (const c of cmds) rcon(c);
  await sleep(1000);
  const got = A.inventory.items().map(i => (i.nbt ? JSON.stringify(i.nbt).match(/(Miner|Farmer|Grinder|Fisherman) Robot [IV]+|Fuel Cell [IV]+/)?.[0] : null) || i.name);
  console.log('  got:', got.join(', '));
  check('every crate robot command works', eggs().length === cmds.filter(c => / robot /.test(c)).length, `${eggs().length} robots from ${cmds.length} commands`);

  console.log('== PLACEMENT');
  rcon('minecraft:tp AlloyTesterA 2003.5 100 2005.5'); await sleep(2500);
  rcon('fill 1995 99 1995 2022 99 2022 minecraft:stone'); rcon('fill 1995 100 1995 2022 106 2022 minecraft:air');
  rcon('kill @e[type=!player,x=2008,y=100,z=2008,distance=..30]');
  rcon('setblock 2006 100 2005 minecraft:gold_block'); rcon('setblock 2016 100 2008 minecraft:gold_block');
  const before = eggs().reduce((n, i) => n + i.count, 0);
  chat.length = 0;
  await place(2003.5, 2005, 2005);
  check('cannot place outside a claim', eggs().reduce((n, i) => n + i.count, 0) === before && chat.some(t => /only go on your own/.test(t)));
  A.chat('/claim'); await sleep(1500);
  // put a Miner I first in the hotbar slot we place from
  rcon('minecraft:clear AlloyTesterA minecraft:copper_golem_spawn_egg'); rcon('robots give AlloyTesterA robot 1 miner'); rcon('robots give AlloyTesterA robot 1 miner'); rcon('robots give AlloyTesterA fuel 1'); rcon('robots give AlloyTesterA fuel 1');
  await sleep(800);
  await place(2003.5, 2005, 2005);     // inside the claim, facing a gold block in the claim
  await place(2013.5, 2015, 2008);     // on the claim edge, facing a gold block in the unclaimed chunk next door
  const r1 = new Vec3(2005.5, 100, 2005.5), r2 = new Vec3(2015.5, 100, 2008.5);
  check('robots placed in own claim', !!golemAt(r1) && !!golemAt(r2));
  for (const [r, stand] of [[r1, '2003.5 100 2005.5'], [r2, '2013.5 100 2008.5']]) {
    rcon(`minecraft:tp AlloyTesterA ${stand}`); await sleep(1200);
    const g = golemAt(r);
    if (!g) continue;
    win = await openWin(() => A.activateEntityAt(g, new Vec3(0, 0.5, 0)));
    if (win) { await A.clickWindow(2, 0, 0); await sleep(800); A.closeWindow(A.currentWindow || win); }
  }
  console.log('  waiting for the miners...'); await sleep(25000);
  const b1 = rcon('execute if block 2006 100 2005 minecraft:gold_block'), b2 = rcon('execute if block 2016 100 2008 minecraft:gold_block');
  check('Miner mines inside its claim', !/passed/i.test(b1), b1);
  check('Miner does NOT mine into the next chunk', /passed/i.test(b2), b2);

  // tidy up: pick both robots back up, unclaim, clear the test area
  for (const [r, stand] of [[r1, '2003.5 100 2005.5'], [r2, '2013.5 100 2008.5']]) {
    rcon(`minecraft:tp AlloyTesterA ${stand}`); await sleep(1200);
    const g = golemAt(r); if (!g) continue;
    win = await openWin(() => A.activateEntityAt(g, new Vec3(0, 0.5, 0)));
    if (win) { await A.clickWindow(8, 0, 0); await sleep(1000); if (A.currentWindow) A.closeWindow(A.currentWindow); }
  }
  check('robots picked back up', !golemAt(r1) && !golemAt(r2));
  A.chat('/unclaim'); await sleep(1000);
  rcon('fill 1995 99 1995 2022 106 2022 minecraft:air'); rcon('minecraft:clear AlloyTesterA');
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
