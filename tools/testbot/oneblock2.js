const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = c => { try { return execSync(`python ../netctl.py cmd skyblock "${c}"`).toString().replace(/ยง./g, '').trim(); } catch (e) { return 'ERR'; } };
const ob = c => rcon('execute in minecraft:oneblock_world run ' + c);
const A = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyTesterA', version: '1.20.4', auth: 'offline' });
A.on('message', m => { const t = m.toString(); if (t.trim() && !/Welcome|joined|BLACKFORGEMC -|Pick a game|  ป|respectful|Type \/|online:|mail|daily|Key!/.test(t)) console.log('A>', t.slice(0, 160)); });
const title = w => JSON.stringify(w.title).match(/"value":"([^"]{2,})"/g)?.map(s => s.slice(9, -1)).filter(s => !/^(string|byte|compound|list)$/.test(s)).join('') || '?';
async function win(fn) { const w = once(A, 'windowOpen'); await fn(); const r = await Promise.race([w, sleep(4000).then(() => null)]); await sleep(500); return r; }
(async () => {
  await once(A, 'spawn'); await sleep(4000);
  rcon('lp user AlloyTesterA permission set grim.exempt true');
  A.setQuickBarSlot(4); await sleep(300); let w = once(A, 'windowOpen'); A.activateItem(); await w; await sleep(300); await A.clickWindow(10, 0, 0); await sleep(8000);
  A.chat('/ob'); await sleep(9000);
  rcon('minecraft:gamemode survival AlloyTesterA'); rcon('minecraft:clear AlloyTesterA');
  const p = A.entity.position.floored(); const c = { x: p.x, y: p.y - 1, z: p.z };
  console.log('on island, magic block at', c);
  console.log('--- break the OneBlock (fragment chance forced to 100% for this test)');
  for (let i = 0; i < 2; i++) { const b = A.blockAt(new Vec3(c.x, c.y, c.z)); if (b && b.name !== 'air') { try { await A.dig(b, true); } catch (e) {} } await sleep(1500); }
  await sleep(1500);
  console.log('progress tag above the block:', ob(`execute if entity @e[type=text_display,x=${c.x},y=${c.y + 3},z=${c.z},distance=..1.5]`));
  console.log('fragments on the ground/inventory:', ob(`execute if entity @e[type=item,x=${c.x},y=${c.y},z=${c.z},distance=..4]`), A.inventory.items().map(i => i.name + 'x' + i.count).join(','));
  console.log('--- OneBlock chest');
  ob(`setblock ${c.x} ${c.y} ${c.z} minecraft:chest`); await sleep(500);
  w = await win(() => A.activateBlock(A.blockAt(new Vec3(c.x, c.y, c.z))));
  console.log('chest contents:', w ? w.slots.slice(0, 27).filter(Boolean).map(s => s.name).join(',') : 'did not open'); if (A.currentWindow) A.closeWindow(A.currentWindow);
  console.log('recipe registered:', rcon('minecraft:recipe give AlloyTesterA alloycore:empty_spawner'));
  console.log('--- empty spawner');
  ob(`fill ${c.x - 4} ${c.y} ${c.z - 4} ${c.x + 4} ${c.y} ${c.z + 4} minecraft:stone keep`);
  ob(`setblock ${c.x} ${c.y} ${c.z} minecraft:dirt`);
  rcon('minecraft:clear AlloyTesterA'); rcon('minecraft:gamemode creative AlloyTesterA'); rcon('robots give AlloyTesterA emptyspawner 1'); rcon('robots give AlloyTesterA essence 900'); rcon('eco set AlloyTesterA 900000'); await sleep(1000);
  const sp = A.inventory.items().find(i => i.name === 'spawner'); console.log('inv before place:', A.inventory.items().map(i => i.name + 'x' + i.count).join(',')); if (sp) await A.equip(sp, 'hand');
  ob(`minecraft:tp AlloyTesterA ${c.x + 3.5} ${c.y + 1} ${c.z + 0.5} 90 30`); await sleep(1500);
  console.log('target block:', A.blockAt(new Vec3(c.x + 2, c.y, c.z))?.name, 'pos', A.entity.position.floored().toString(), 'held', A.heldItem && A.heldItem.name);
  try { await A.placeBlock(A.blockAt(new Vec3(c.x + 2, c.y, c.z)), new Vec3(0, 1, 0)); } catch (e) { console.log('place err', e.message); } await sleep(1500);
  const S = { x: c.x + 2, y: c.y + 1, z: c.z };
  console.log('placed:', ob(`execute if block ${S.x} ${S.y} ${S.z} minecraft:spawner`));
  A.setQuickBarSlot(8); await sleep(300);
  w = await win(() => A.activateBlock(A.blockAt(new Vec3(S.x, S.y, S.z))));
  console.log('menu:', w ? title(w) : 'none', '| choices:', w ? w.slots.slice(0, 45).filter(Boolean).map(s => s.name.replace('_spawn_egg', '')).join(',') : '');
  if (w) { const idx = w.slots.findIndex((s, i) => i < 45 && s && s.name === 'pig_spawn_egg'); await A.clickWindow(idx >= 0 ? idx : 0, 0, 0); await sleep(1500); }
  console.log('spawner type:', ob(`data get block ${S.x} ${S.y} ${S.z} SpawnData.entity.id`).split(': ')[1]);
  console.log('--- upgrades capped by tier');
  if (!A.currentWindow) await win(() => A.activateBlock(A.blockAt(new Vec3(S.x, S.y, S.z))));
  if (A.currentWindow) { await A.clickWindow(19, 0, 0); await sleep(1000); await A.clickWindow(19, 0, 0); await sleep(1000); A.closeWindow(A.currentWindow); }
  console.log('--- kill-based tier up');
  ob(`fill ${S.x - 4} ${c.y} ${S.z - 4} ${S.x + 4} ${c.y} ${S.z + 4} minecraft:grass_block replace minecraft:stone`);
  ob(`data merge block ${S.x} ${S.y} ${S.z} {PublicBukkitValues:{'alloycore:spawner_kills':63}}`);
  for (let i = 0; i < 20 && !/passed/.test(ob(`execute if entity @e[type=pig,x=${S.x},y=${S.y},z=${S.z},distance=..8]`)); i++) await sleep(4000);
  console.log('pigs from spawner:', ob(`execute if entity @e[type=pig,x=${S.x},y=${S.y},z=${S.z},distance=..8]`));
  ob(`minecraft:kill @e[type=pig,x=${S.x},y=${S.y},z=${S.z},distance=..8]`); await sleep(2000);
  console.log('spawner data:', ob(`data get block ${S.x} ${S.y} ${S.z} PublicBukkitValues`));
  w = await win(() => A.activateBlock(A.blockAt(new Vec3(S.x, S.y, S.z))));
  if (w) { await A.clickWindow(19, 0, 0); await sleep(1200); console.log('menu after tier up:', title(A.currentWindow || w)); }
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
