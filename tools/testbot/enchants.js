// Custom enchant test: drag-apply books, then exercise each enchant.
const mineflayer = require('mineflayer');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd survival "${c}"`).toString().trim(); } catch (e) { return 'ERR'; } };
const num = s => { const m = /([\d.]+)f?\s*$/.exec(s.replace(/§./g, '')); return m ? parseFloat(m[1]) : s; };
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => console.log(n, 'ERROR', e.message));
  return b;
}
async function toSurvival(b) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(12, 0, 0); await sleep(7000); }
const slotOf = (b, name) => { const it = b.inventory.items().find(i => i.name === name); return it ? it.slot : -1; };
async function applyBook(b, bookName, target) {
  // pick up the book, click it onto the target item, then put back whatever is left on the cursor
  const want = bookName.toLowerCase();
  const book = b.inventory.items().find(i => i.name === 'enchanted_book' && JSON.stringify(i.customName || '').toLowerCase().includes(want.slice(0, 4)));
  const t = slotOf(b, target);
  if (!book || t < 0) { console.log('missing', bookName, target); return; }
  await b.clickWindow(book.slot, 0, 0); await sleep(300);
  await b.clickWindow(t, 0, 0); await sleep(500);
  if (b.inventory.cursor) { await b.clickWindow(book.slot, 0, 0); await sleep(200); }
}
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toSurvival(A); await toSurvival(B);
  A.on('message', m => { const t = m.toString(); if (/Applied|only works|already has|Thunderstrike|bleeding|received a/.test(t)) console.log('A>', t); });
  console.log(rcon('minecraft:clear AlloyTesterA')); await sleep(1200);
  const gear = [['diamond_sword', 'lifesteal', 3], ['diamond_axe', 'bleed', 3], ['diamond_chestplate', 'overload', 3], ['diamond_boots', 'springs', 2], ['diamond_pickaxe', 'autosmelt', 1], ['diamond_pickaxe', 'excavator', 1]];
  for (const [item, ce, lvl] of gear) {
    if (!A.inventory.items().find(i => i.name === item)) { console.log('give', rcon(`minecraft:give AlloyTesterA ${item}`)); await sleep(900); }
    rcon(`alloy ce give AlloyTesterA ${ce} ${lvl}`); await sleep(600);
    await applyBook(A, ce, item);
  }
  // thunderstrike on the axe too (wrong item first to check validation)
  rcon('alloy ce give AlloyTesterA thunderstrike 3'); await sleep(800);
  await applyBook(A, 'thunderstrike', 'diamond_axe');
  console.log('books left:', A.inventory.items().filter(i => i.name === 'enchanted_book').length);

  // overload + springs: wear them
  const chest = A.inventory.items().find(i => i.name === 'diamond_chestplate'); await A.equip(chest, 'torso');
  const boots = A.inventory.items().find(i => i.name === 'diamond_boots'); await A.equip(boots, 'feet');
  await sleep(2500);
  console.log('max health with Overload III:', num(rcon('attribute AlloyTesterA minecraft:max_health get')));
  console.log('jump boost:', /jump_boost/.test(rcon('data get entity AlloyTesterA active_effects')) ? 'yes' : 'no');

  // combat in the Scrapyard
  rcon('tp AlloyTesterA 30.5 166 530.5'); rcon('tp AlloyTesterB 32.0 166 530.5'); await sleep(3000);
  rcon('effect give AlloyTesterB minecraft:resistance 60 1 true');
  rcon('damage AlloyTesterA 8'); await sleep(600);
  const sword = A.inventory.items().find(i => i.name === 'diamond_sword'); await A.equip(sword, 'hand');
  let hpA = num(rcon('data get entity AlloyTesterA Health'));
  for (let i = 0; i < 3; i++) {
    const t = A.nearestEntity(e => e.username === 'AlloyTesterB'); await A.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(1100); A.attack(t); await sleep(400);
  }
  console.log('Lifesteal: A health', hpA, '->', num(rcon('data get entity AlloyTesterA Health')), '(B', num(rcon('data get entity AlloyTesterB Health')) + ')');
  rcon('effect give AlloyTesterB minecraft:instant_health 1 5 true'); await sleep(500);
  const axe = A.inventory.items().find(i => i.name === 'diamond_axe'); await A.equip(axe, 'hand');
  for (let i = 0; i < 8; i++) {
    const t = A.nearestEntity(e => e.username === 'AlloyTesterB'); if (!t) break;
    rcon('tp AlloyTesterB 32.0 166 530.5');
    await A.lookAt(t.position.offset(0, 1.5, 0), true); await sleep(1100); A.attack(t); await sleep(300);
  }
  await sleep(4000);
  console.log('after 8 axe hits, B health:', num(rcon('data get entity AlloyTesterB Health')));

  // autosmelt + excavator in the wild
  rcon('tp AlloyTesterA 3000.5 100 3000.5'); await sleep(4000);
  rcon('fill 2995 119 2995 3005 119 3005 minecraft:stone'); rcon('fill 2995 120 2995 3005 126 3002 minecraft:air');
  rcon('fill 2998 120 3003 3002 124 3003 minecraft:iron_ore');
  rcon('tp AlloyTesterA 3000.5 120 3001.5 0 0'); await sleep(3000);
  const pick = A.inventory.items().find(i => i.name === 'diamond_pickaxe'); await A.equip(pick, 'hand');
  const ore = A.blockAt(new (require('vec3').Vec3)(3000, 122, 3003));
  console.log('mining', ore && ore.name, 'at', ore && ore.position.toString());
  await A.lookAt(ore.position.offset(0.5, 0.5, 0), true);
  try { await A.dig(ore, true); } catch (e) { console.log('dig err', e.message); }
  await sleep(1500);
  let left = 0;
  for (let x = 2998; x <= 3002; x++) for (let y = 120; y <= 124; y++) if (/passed/.test(rcon(`execute if block ${x} ${y} 3003 minecraft:iron_ore`))) left++;
  console.log('ore blocks remaining of 25:', left, '(expect 16 = 9 mined)');
  await sleep(1500);
  console.log('A items:', A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
