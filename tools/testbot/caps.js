const mineflayer = require('mineflayer');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
async function toS(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterC'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterD'); await once(B, 'spawn'); await sleep(2000);
  await toS(A); await toS(B);
  B.on('message', m => { const t = m.toString(); if (t.includes('AlloyTesterC')) console.log('B got:', t); });
  A.on('message', m => { const t = m.toString(); if (/⚠|Muted/.test(t)) console.log('A sees:', t); });
  A.chat('THIS IS SO COOL GUYS'); await sleep(1500);
  A.chat('what a f a g'); await sleep(1500);
  process.exit(0);
})();
