const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd arcade "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' }); b.on('error', () => {}); return b; };
async function toArcade(b) { b.setQuickBarSlot(4); await sleep(300); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(16, 0, 0); await sleep(7000); }
(async () => {
  for (const m of ['classic_plots', 'sky_plots']) { rcon(`arcade set buildbattle ${m} build-seconds 12`); rcon(`arcade set buildbattle ${m} vote-seconds 6`); }
  const D = mk('AlloyTesterD'); await once(D, 'spawn'); await sleep(3600); const E = mk('AlloyTesterE'); await once(E, 'spawn'); await sleep(2000);
  await toArcade(D); await toArcade(E);
  for (const [b, slot] of [[D, 6], [E, 3]]) b.on('message', async m => { const t = m.toString();
    if (/Now rating build/.test(t)) { await sleep(1500); b.setQuickBarSlot(slot); await sleep(300); b.activateItem(); }
    if (/Results|points|Winner/.test(t)) console.log(b.username.slice(-1), '>', t); });
  D.chat('/arcade join buildbattle'); E.chat('/arcade join buildbattle');
  await sleep(60000);
  for (const m of ['classic_plots', 'sky_plots']) { rcon(`arcade set buildbattle ${m} build-seconds 300`); rcon(`arcade set buildbattle ${m} vote-seconds 12`); }
  process.exit(0);
})();
