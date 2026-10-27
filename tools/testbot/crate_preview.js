const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const rcon = c => execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim();
const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
b.on('message', m => { const t = m.toString(); if (t.trim()) console.log('chat>', t); });
b.on('windowOpen', w => console.log('WINDOW', JSON.stringify(w.title).replace(/"(type|value)":/g, '').slice(0, 120), 'slots', w.slots.filter(s => s && s.slot < w.inventoryStart && !s.name.includes('pane')).map(s => s.name).join(',')));
b.once('spawn', async () => {
  await sleep(3000);
  // hub -> survival
  b.setQuickBarSlot(4); await sleep(400); b.activateItem(); await sleep(1500);
  if (b.currentWindow) await b.clickWindow(12, 0, 0);
  await sleep(7000);
  console.log(rcon('tp AlloyTesterA -2.5 152 -26.5 180 20'));
  await sleep(1500);
  const keys = b.inventory.items().filter(i => i.name === 'tripwire_hook');
  console.log('keys', keys.map(k => k.count));
  const gold = b.blockAt(new Vec3(-3, 152, -30));
  await b.lookAt(gold.position.offset(0.5, 0.5, 0.5));
  b._client.write('block_dig', { status: 0, location: gold.position, face: 3, sequence: 1 });
  await sleep(1500);
  console.log('preview open?', !!b.currentWindow);
  if (b.currentWindow) b.closeWindow(b.currentWindow);
  process.exit(0);
  // open the iron crate
  const vault = b.blockAt(new Vec3(-9, 152, -28));
  await b.equip(keys[0], 'hand'); await b.lookAt(vault.position.offset(0.5, 0.5, 0.5));
  await b.activateBlock(vault);
  await sleep(10000);
  console.log('items now', b.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  process.exit(0);
});
