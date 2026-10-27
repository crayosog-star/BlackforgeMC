const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'AlloyProbe', version: process.env.BOTVER || '26.1', auth: 'offline' });
let n = 0;
bot.on('spawn', () => {
  console.log('spawn', bot.entity.position, 'attrs', JSON.stringify(bot.entity.attributes).slice(0, 400));
  console.log('physics', JSON.stringify(bot.physics).slice(0, 300));
});
bot.on('physicsTick', () => { if (n++ < 6) console.log('tick', bot.entity.position.toString(), bot.entity.velocity.toString(), bot.entity.onGround); });
bot._client.on('abilities', p => console.log('abilities', JSON.stringify(p)));
bot._client.on('update_attributes', p => console.log('attrs pkt', JSON.stringify(p).slice(0, 300)));
bot.on('kicked', r => { console.log('KICKED', JSON.stringify(r).slice(0, 200)); process.exit(0); });
setTimeout(() => process.exit(0), 6000);
