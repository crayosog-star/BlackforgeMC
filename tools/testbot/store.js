// Store supporter ranks: hub join message + badge, /trails (tier gating, shared save), /store, /tags supporter colour, key all, thanks broadcast.
const mineflayer = require('mineflayer');
const fs = require('fs');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/Â§./g, '').replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
const plain = c => { try { return JSON.stringify(c).match(/"(?:text|value)":"([^"]*)"/g)?.map(s => s.replace(/^"(text|value)":"/, '').slice(0, -1)).join('') || ''; } catch { return ''; } };
const title = w => JSON.stringify(w.title).match(/"(?:text|value)":"([^"]{2,})"/g)?.map(s => s.replace(/^"(text|value)":"/, '').slice(0, -1)).filter(s => !/^(string|byte|compound|list)$/.test(s)).join('') || '?';
const NAME = 'AlloyTesterS';
const results = [];
const check = (label, ok, extra = '') => { results.push(`${ok ? 'PASS' : 'FAIL'}  ${label}${extra ? '  (' + extra + ')' : ''}`); };
async function openCmd(b, cmd) { const w = once(b, 'windowOpen'); b.chat(cmd); return Promise.race([w, sleep(4000).then(() => null)]); }
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }

(async () => {
  rcon('hub', `lp user ${NAME} parent add supporter_blaze`); await sleep(1500);
  const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: NAME, version: '1.20.4', auth: 'offline' });
  A.on('error', e => console.log('ERROR', e.message));
  const chat = [];
  A.on('message', m => chat.push(m.toString()));
  await once(A, 'spawn'); await sleep(5000);

  check('hub join message for Blaze', chat.some(t => /Blaze supporter AlloyTesterS joined the hub/.test(t)), chat.find(t => /joined the hub/.test(t)) || 'none');
  const me = A.players[NAME];
  const tabName = me && me.displayName ? plain(me.displayName.json || me.displayName) : '';
  check('badge in tab list (hub)', tabName.includes('✦'), tabName);

  A.chat('/store'); await sleep(1200);
  check('/store link', chat.some(t => /blackforgemc\.com\/store/.test(t)));

  let win = await openCmd(A, '/trails');
  check('/trails menu opens', !!win, win ? title(win) : 'none');
  if (win) {
    chat.length = 0;
    await A.clickWindow(2, 0, 0); await sleep(1200);           // Notes (Blaze)
    check('Blaze can pick Notes', chat.some(t => /Trail set to Notes/.test(t)));
    chat.length = 0;
    if (A.currentWindow) { await A.clickWindow(4, 0, 0); await sleep(1200); }   // Soul Fire (Inferno)
    check('Blaze blocked from Soul Fire', chat.some(t => /supporter perk/.test(t)));
    if (A.currentWindow) A.closeWindow(A.currentWindow);
  }
  let yml = '';
  try { yml = fs.readFileSync('C:/MinecraftNetwork/shared/supporters/trails.yml', 'utf8'); } catch { console.log('chat so far:', chat.slice(-8)); }
  check('trail saved to shared file', /: notes/.test(yml), yml.trim().split('\n').slice(-1)[0]);

  await toSurvival(A); await sleep(3000);
  const tab2 = A.players[NAME] && A.players[NAME].displayName ? plain(A.players[NAME].displayName.json || A.players[NAME].displayName) : '';
  check('badge in tab list (survival)', tab2.includes('✦'), tab2);
  win = await openCmd(A, '/tags');
  if (win) {
    const slots = win.slots.slice(0, 17);
    const blaze = slots[14], inferno = slots[15];
    check('/tags: Blaze colour unlocked', blaze && blaze.name === 'name_tag', blaze && blaze.name);
    check('/tags: Inferno colour locked', inferno && inferno.name === 'paper', inferno && inferno.name);
    chat.length = 0;
    await A.clickWindow(14, 0, 0); await sleep(3000);
    if (A.currentWindow) A.closeWindow(A.currentWindow);
    const tab3 = A.players[NAME] && A.players[NAME].displayName ? JSON.stringify(A.players[NAME].displayName.json || A.players[NAME].displayName) : '';
    check('Blaze colour equipped', /ffb347|FFB347|ff6a1f|FF6A1F/i.test(tab3) || chat.some(t => /Unlocked|equip/i.test(t)));
  } else check('/tags menu opens', false);

  chat.length = 0;
  const before = A.inventory.items().filter(i => i.name !== 'compass').reduce((n, i) => n + i.count, 0);
  console.log(rcon('survival', 'alloy keyall iron 1 test goal'));
  await sleep(1500);
  const after = A.inventory.items().filter(i => i.name !== 'compass').reduce((n, i) => n + i.count, 0);
  check('key all broadcast', chat.some(t => /KEY ALL/.test(t)), chat.find(t => /KEY ALL/.test(t)));
  check('key all gave a key', after > before, `${before} -> ${after}`);
  rcon('survival', `alloy thanks ${NAME} Blaze Supporter`); await sleep(1000);
  check('thanks broadcast', chat.some(t => /just supported the server with Blaze Supporter/.test(t)));

  // refund: group removed -> badge and colour drop
  rcon('hub', `lp user ${NAME} parent remove supporter_blaze`); await sleep(6000);
  const tab4 = A.players[NAME] && A.players[NAME].displayName ? plain(A.players[NAME].displayName.json || A.players[NAME].displayName) : '';
  check('badge gone after removal', !tab4.includes('✦'), tab4);

  console.log(results.join('\n'));
  A.quit(); await sleep(1000); process.exit(0);
})();
