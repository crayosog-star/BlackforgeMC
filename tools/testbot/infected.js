const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const mk = n => { const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('message', m => { const t = m.toString(); if (t.trim() && !t.includes('queue')) console.log(n, 'chat>', t); }); return b; };
async function pick(bot, hot, slot) { bot.setQuickBarSlot(hot); await sleep(300); const w = once(bot, 'windowOpen'); bot.activateItem(); await w; await sleep(300); await bot.clickWindow(slot, 0, 0); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await pick(A, 4, 16); await pick(B, 4, 16); await sleep(7000);
  await pick(A, 0, 15); await pick(B, 0, 15);
  while (A.entity.position.x < 2900) await sleep(500);
  console.log('round started; A', A.entity.position.toString(), 'B', B.entity.position.toString());
  await sleep(11500);
  const z = [A, B].find(b => (b.inventory.slots[5] || {}).name === 'zombie_head');
  console.log('zombie:', z && z.username);
  const h = z === A ? B : A;
  z.chat(`/tp ${z.username} ${h.username}`); await sleep(1500);
  const t = z.nearestEntity(e => e.type === 'player');
  z.attack(t); console.log('swing at', t && t.username);
  await sleep(9000);
  console.log('after: A', A.entity.position.toString(), 'B', B.entity.position.toString());
  process.exit(0);
})();
