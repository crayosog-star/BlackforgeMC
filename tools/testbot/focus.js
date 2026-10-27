const mineflayer = require('mineflayer');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterC', version: '1.20.4', auth: 'offline' });
bot.on('message', m => { const t = m.toString(); if (t.trim()) console.log('chat>', t); });
bot.on('windowOpen', w => console.log('WINDOW', JSON.stringify(w.title).slice(0, 200), w.slots.filter(s => s && s.slot < w.inventoryStart).map(s => s.name).join(',')));
bot._client.on('scoreboard_objective', p => console.log('objective pkt', JSON.stringify(p).slice(0, 200)));
bot._client.on('scoreboard_score', p => console.log('score pkt', JSON.stringify(p).slice(0, 220)));
bot._client.on('scoreboard_display_objective', p => console.log('display pkt', JSON.stringify(p)));
(async () => {
  await once(bot, 'spawn');
  await sleep(4000);
  console.log('entities:', [...new Set(Object.values(bot.entities).map(e => e.name))].join(','));
  // to skyblock via menu
  bot.setQuickBarSlot(4); await sleep(300);
  const w = once(bot, 'windowOpen'); bot.activateItem(); await w; await sleep(300);
  await bot.clickWindow(10, 0, 0);
  await sleep(12000);
  console.log('skyblock pos', bot.entity.position.toString());
  process.exit(0);
})();
