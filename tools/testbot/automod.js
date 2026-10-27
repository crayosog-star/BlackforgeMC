// AutoMod + Guardian test. A talks, B reports what actually arrives.
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const once = (b, e) => new Promise(r => b.once(e, r));
const rcon = (s, c) => { try { return execSync(`python ../netctl.py cmd ${s} "${c}"`).toString().replace(/§./g, '').trim(); } catch (e) { return 'ERR'; } };
function mk(n) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: n, version: '1.20.4', auth: 'offline' });
  b.on('error', e => console.log(n, 'ERROR', e.message));
  return b;
}
async function toServer(b, slot) { b.setQuickBarSlot(4); await sleep(400); const w = once(b, 'windowOpen'); b.activateItem(); await w; await sleep(300); await b.clickWindow(slot, 0, 0); await sleep(7000); }
(async () => {
  const A = mk('AlloyTesterA'); await once(A, 'spawn'); await sleep(4000);
  const B = mk('AlloyTesterB'); await once(B, 'spawn'); await sleep(2000);
  await toServer(A, 12); await toServer(B, 12);   // both on survival
  const got = [];
  B.on('message', m => { const t = m.toString(); if (t.includes('AlloyTesterA') && !/joined|left|voted|muted/i.test(t)) got.push(t); });
  A.on('message', m => { const t = m.toString(); if (/⚠|Muted|muted|Slow|warning/.test(t)) console.log('   A sees:', t); });
  const say = async (msg, expect) => {
    const before = got.length; A.chat(msg); await sleep(1300);
    const arrived = got.slice(before).join(' | ');
    const ok = expect === 'pass' ? arrived.length > 0 : arrived.length === 0;
    console.log((ok ? 'OK  ' : 'FAIL') + ` [${expect}] "${msg}" -> ${arrived || '(blocked)'}`);
  };
  await say('hello everyone, nice server', 'pass');
  await say('this spicy food is great', 'pass');
  await say('I visited Niger and Nigeria last year', 'pass');
  await say('the cocoon in a raccoon den', 'pass');
  await say('I like kebabs and chicken', 'pass');
  await say('THIS IS SO COOL GUYS', 'pass');
  await say('check youtube.com/watch?v=abc', 'pass');
  await say('join play.otherserver.net its better', 'block');
  await say('buy cheap ranks', 'pass'); await sleep(1500);
  await say('buy cheap ranks', 'pass'); await sleep(1500);
  await say('buy cheap ranks', 'block');
  await sleep(2000);
  await say('you are a n1gg3r', 'block');
  await say('hello again', 'block');   // now muted
  A.chat('/msg AlloyTesterB psst'); await sleep(1200);
  console.log('mute in shared file:', rcon('survival', 'mutes').split('\n').slice(0, 2).join(' / '));
  // network-wide: A goes to the hub and tries to talk
  A.chat('/hub'); await sleep(6000);
  A.chat('hub chat while muted'); await sleep(1500);
  console.log('hub log mentions mute block:', /hub chat while muted/.test(require('fs').readFileSync('../../hub/logs/latest.log', 'utf8')) ? 'message reached hub log (check)' : 'not logged as chat (blocked)');
  console.log('unmute:', rcon('hub', 'unmute AlloyTesterA'));
  await sleep(6500);   // let other servers pick up the shared file
  await toServer(A, 12); await sleep(1000);
  await say('back and unmuted', 'pass');
  // more slur tricks (each one mutes again; check they're caught, then unmute)
  for (const m of ['n i g g a', 'f@gg0t', 'what a f a g', 'spic', 'go back you wetback']) {
    await say(m, 'block'); rcon('survival', 'unmute AlloyTesterA'); await sleep(5600);
  }
  // auto-clicker: 26 swings/second for 4 seconds
  for (let i = 0; i < 104; i++) { A.swingArm('right'); await sleep(38); }
  await sleep(2500);
  console.log('anticheat.log:', require('fs').existsSync('../../shared/anticheat.log') ? require('fs').readFileSync('../../shared/anticheat.log', 'utf8').trim().split('\n').slice(-3).join(' || ') : '(none)');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
