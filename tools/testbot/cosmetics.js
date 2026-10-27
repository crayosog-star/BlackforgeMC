// Name tags, kits and glowing supply crates.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const flat = c => { try { return JSON.stringify(c).replace(/"(type|value)":/g, '').slice(0, 220); } catch { return String(c); } };
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => console.log(n, 'ERROR', e.message));
  return b;
}
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
async function openCmd(b, cmd) {
  const w = once(b, 'windowOpen'); b.chat(cmd);
  const win = await Promise.race([w, sleep(4000).then(() => null)]);
  return win;
}
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  A.on('message', m => { const t = m.toString(); if (/Name|colour|Unlocked|blessing|received|Kit|SUPPLY/i.test(t)) console.log('A chat>', t); });
  B.on('message', (m, pos) => { if (/AlloyTesterA/.test(m.toString()) && /hello/.test(m.toString())) console.log('B sees chat json:', flat(m.json || m)); });

  // --- name tag item
  console.log(rcon('alloy tag give AlloyTesterA molten'));
  await sleep(800);
  const tagItem = A.inventory.items().find(i => i.name === 'name_tag');
  console.log('tag item:', tagItem && flat(tagItem.customName));
  await A.equip(tagItem, 'hand'); A.activateItem(); await sleep(3500);
  A.chat('hello from A'); await sleep(1200);
  const team = B.teamMap && B.teamMap['AlloyTesterA'];
  console.log('B sees A in team:', team ? team.team + ' color=' + team.color : 'none');
  // --- /tags menu + buying a basic colour
  rcon('eco give AlloyTesterA 5000');
  let win = await openCmd(A, '/tags');
  console.log('tags menu:', win ? win.slots.slice(0, 13).map(s => s && s.name).join(',') : 'NO WINDOW');
  if (win) { await A.clickWindow(4, 0, 0); await sleep(1500); }   // aqua
  A.chat('/bal'); await sleep(1200);
  const team2 = B.teamMap && B.teamMap['AlloyTesterA'];
  console.log('after buying aqua, B sees team:', team2 ? team2.team + ' color=' + team2.color : 'none');
  if (A.currentWindow) A.closeWindow(A.currentWindow);
  // --- kits
  win = await openCmd(A, '/kits');
  console.log('kits menu:', win ? [10, 11, 12, 13, 14, 15, 16].map(i => win.slots[i] && win.slots[i].name).join(',') : 'NO WINDOW');
  if (win) {
    const z = win.slots[16];
    console.log('zeus lore:', z && z.nbt ? flat(z.nbt).slice(0, 200) : '?');
    await A.clickWindow(16, 0, 0); await sleep(1500);
    if (A.currentWindow) { console.log('zeus preview:', A.currentWindow.slots.filter(s => s && s.slot < 27).map(s => s.name + (s.count > 1 ? 'x' + s.count : '')).join(',')); A.closeWindow(A.currentWindow); }
  }
  A.chat('/clear'); await sleep(300);
  console.log(rcon('clear AlloyTesterA'));
  console.log(rcon('alloy kit give AlloyTesterA ares'));
  await sleep(1500);
  console.log('A after store kit:', A.inventory.items().map(i => i.name + (i.count > 1 ? 'x' + i.count : '')).join(','));
  // --- glowing supply crates
  A.chat('/pvp'); await sleep(3500);
  console.log(rcon('alloy pvp drop'));
  await sleep(3000);
  const kinds = {};
  for (const e of Object.values(A.entities)) kinds[e.name] = (kinds[e.name] || 0) + 1;
  console.log('entities A can see:', JSON.stringify(kinds));
  console.log('block displays (glows):', rcon('execute if entity @e[type=minecraft:block_display]'));
  console.log('glowing ones:', rcon('execute if entity @e[type=minecraft:block_display,nbt={Glowing:1b}]'));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
