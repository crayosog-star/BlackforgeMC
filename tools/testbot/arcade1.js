// New arcade core: queues, random maps per game, two simultaneous instances, elimination, win, rewards, reset.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd arcade "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const KEEP = /ALLOY ARCADE|Map selected|Loading|starting|Winner|winner|VICTORY|\+ \$|fell|left|Not enough|countdown|Finding|Round|wins round/;
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (KEEP.test(t)) console.log(n.slice(-1), '>', t.slice(0, 140)); }); b.on('error', e => console.log(n, e.message)); b.on('kicked', r => console.log(n, 'kicked', JSON.stringify(r).slice(0, 120))); return b; };
async function toArcade(b) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(16, 0, 0); await sleep(7000); }
(async () => {
  const names = ['A', 'B', 'C', 'D', 'E', 'F'];
  const bots = {};
  for (const n of names) { bots[n] = mk('AlloyTester' + n); await once(bots[n], 'spawn'); await sleep(3600); }
  for (const n of names) await toArcade(bots[n]);
  console.log('--- queue + cancel: A joins spleef alone, then leaves');
  bots.A.chat('/arcade join spleef'); await sleep(2500); bots.A.chat('/arcade leave'); await sleep(1000);
  console.log('--- match 1: A+B spleef, E+F tntrun');
  bots.A.chat('/arcade join spleef'); bots.B.chat('/arcade join spleef');
  bots.E.chat('/arcade join tntrun'); bots.F.chat('/arcade join tntrun');
  await sleep(22000);
  console.log('--- match 2: C+D spleef (second instance)');
  bots.C.chat('/arcade join spleef'); bots.D.chat('/arcade join spleef');
  await sleep(22000);
  console.log(rcon('arcade status'));
  for (const n of names) console.log(n, 'at', bots[n].entity.position.floored().toString(), bots[n].game.dimension);
  console.log('--- eliminate A (void) and D (void); E falls in tnt run');
  rcon('execute as AlloyTesterA at @s run tp @s ~ ~-40 ~'); rcon('execute as AlloyTesterD at @s run tp @s ~ ~-40 ~'); rcon('execute as AlloyTesterE at @s run tp @s ~ ~-60 ~');
  await sleep(12000);
  console.log(rcon('arcade status'));
  await sleep(6000);
  console.log('after reset:', rcon('arcade status'));
  console.log('B stats:', rcon('arcade stats AlloyTesterB'));
  console.log(rcon('arcade maps spleef'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
