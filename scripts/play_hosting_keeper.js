/**
 * 🛡️ CIRO MULTI-TARGET MINECRAFT KEEPER (GITHUB ACTIONS SENTINEL v5.0)
 * 
 * Menjaga multi-server Minecraft tetap online 24/7 sekaligus secara paralel:
 * 1. cirobro.play.hosting:25565
 * 2. cirox.bedrock.minekeep.gg:25565
 * 
 * Fitur: Dual-Instance Paralel, Auto-Reconnect, Anti-AFK Jump Loop, & Infinite Estafet.
 */
const mineflayer = require('mineflayer');

const TARGETS = [
    {
        name: 'Play.Hosting',
        host: process.env.MC_HOST_1 || 'cirobro.play.hosting',
        port: parseInt(process.env.MC_PORT_1 || '25565', 10),
        username: 'Ciro'
    },
    {
        name: 'MineKeep.gg',
        host: process.env.MC_HOST_2 || 'cirox.bedrock.minekeep.gg',
        port: parseInt(process.env.MC_PORT_2 || '25565', 10),
        username: 'CiroKeeper'
    }
];

const DURATION_MS = (parseInt(process.env.KEEPALIVE_MINUTES || '23', 10)) * 60 * 1000;
const END_TIME = Date.now() + DURATION_MS;

console.log('======================================================');
console.log('  🛡️ CIRO MULTI-TARGET SENTINEL KEEPER (v5.0)         ');
console.log('======================================================');
console.log(`[Total Target]  : ${TARGETS.length} Server`);
console.log(`[Durasi Siklus] : ${DURATION_MS / 60000} menit`);
TARGETS.forEach((t, i) => {
    console.log(`  ${i + 1}. [${t.name}] -> ${t.host}:${t.port} (User: ${t.username})`);
});
console.log('------------------------------------------------------');

function launchKeeperBot(target) {
    function startInstance() {
        if (Date.now() >= END_TIME) return;

        console.log(`[${target.name}] Menghubungkan ke ${target.host}:${target.port}...`);
        let bot;
        try {
            bot = mineflayer.createBot({
                host: target.host,
                port: target.port,
                username: target.username,
                auth: 'offline',
                version: '1.20.4',
                checkTimeoutInterval: 60000
            });
        } catch (err) {
            console.error(`[${target.name}] Init Error:`, err.message);
            setTimeout(startInstance, 5000);
            return;
        }

        let jumpTimer = null;

        bot.on('login', () => {
            console.log(`🎉 [${target.name}] BERHASIL LOGIN! (${target.username})`);
        });

        bot.on('spawn', () => {
            console.log(`🌍 [${target.name}] RESMI SPAWN DI DUNIA GAME!`);
            if (jumpTimer) clearInterval(jumpTimer);
            jumpTimer = setInterval(() => {
                try {
                    bot.setControlState('jump', true);
                    setTimeout(() => bot.setControlState('jump', false), 350);
                    const remaining = Math.round((END_TIME - Date.now()) / 1000);
                    if (remaining <= 0) {
                        bot.quit();
                    }
                } catch (e) {}
            }, 15000);
        });

        bot.on('kicked', (reason) => {
            console.log(`⚠️ [${target.name}] KICKED:`, typeof reason === 'object' ? JSON.stringify(reason) : reason);
        });

        bot.on('error', (err) => {
            console.error(`❌ [${target.name}] ERROR:`, err.message);
        });

        bot.on('end', () => {
            if (jumpTimer) clearInterval(jumpTimer);
            if (Date.now() < END_TIME) {
                console.log(`🔄 [${target.name}] Reconnecting dalam 4 detik...`);
                setTimeout(startInstance, 4000);
            } else {
                console.log(`🛑 [${target.name}] Selesai siklus.`);
            }
        });
    }

    startInstance();
}

// Jalankan kedua bot secara paralel
TARGETS.forEach(target => {
    launchKeeperBot(target);
});

// Timer monitor status
const monitorTimer = setInterval(() => {
    const remaining = Math.round((END_TIME - Date.now()) / 1000);
    if (remaining % 60 === 0 || remaining <= 20) {
        console.log(`⏱️ [Sentinels Active] Semua bot standby anti-AFK. Sisa waktu: ${remaining}s`);
    }
    if (remaining <= 0) {
        clearInterval(monitorTimer);
        console.log('✅ Siklus 23 menit selesai. Keluar untuk regenerasi runner estafet baru.');
        process.exit(0);
    }
}, 1000);

setTimeout(() => {
    console.log('⏱️ Hard timeout reached.');
    process.exit(0);
}, DURATION_MS + 8000);
