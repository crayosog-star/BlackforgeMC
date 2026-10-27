const mineflayer = require('mineflayer');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
b.on('windowOpen', w => console.log('WINDOW', w.type, w.slots.length));
b.on('spawn', async () => {
  console.log('spawn', b.entity.position, 'held', b.quickBarSlot, 'items', b.inventory.items().map(i => i.slot + ':' + i.name));
  await sleep(3000);
  console.log('after 3s items', b.inventory.items().map(i => i.slot + ':' + i.name), 'pos', b.entity.position);
  b.setQuickBarSlot(4); await sleep(500);
  b.activateItem(); console.log('activated');
  await sleep(3000); process.exit(0);
});
