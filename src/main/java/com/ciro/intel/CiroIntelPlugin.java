package com.ciro.intel;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class CiroIntelPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private String botToken;
    private String chatId;
    private int scanIntervalMinutes;
    private int summaryHourWib;

    private final Map<String, NewsItem> newsDatabase = new ConcurrentHashMap<>();
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private int lastReportedDay = -1;

    private long telegramOffset = 0;
    private boolean isPollingRunning = true;

    // Konfigurasi AI Luna (OpenAI Compatible)
    private static final String LUNA_API_KEY = "sk-WcKRPjofesonHH7agg4MWQ";
    private static final String LUNA_URL = "https://lite.koboillm.com/v1/chat/completions";
    private static final String LUNA_MODEL = "openai/gpt-6-luna";

    public static class NewsItem {
        public final String sector; // IDX, GOLD, BTC
        public final String title;
        public final String source;
        public final String link;
        public final long timestamp;

        public NewsItem(String sector, String title, String source, String link) {
            this.sector = sector;
            this.title = title;
            this.source = source;
            this.link = link;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("cirointel") != null) {
            getCommand("cirointel").setExecutor(this);
        }

        getLogger().info("[CiroIntel] Plugin Intel Pasar AI Luna Aktif! Polling Telegram berjalan.");

        new BukkitRunnable() {
            @Override
            public void run() {
                sendTelegramMessage("🛰️ <b>Ciro Market Intel Sentinel (Powered by AI Luna) Aktif!</b>\n" +
                        "• Hosting: Server Minecraft (Paper)\n" +
                        "• Otak Analisis: AI Luna (GPT-6 Engine)\n" +
                        "• Mode: 24 Jam Silent Scraper (IDX, Gold, BTC)\n" +
                        "• Waktu Laporan: Setiap Jam 00:00 WIB\n\n" +
                        "💬 <b>Perintah Telegram (@Dicopi_bot):</b>\n" +
                        "👉 <code>/summary</code> atau <code>/lapor</code> - Minta analisis & prediksi AI Luna sekarang\n" +
                        "👉 <code>/scan</code> - Pindai berita baru detik ini\n" +
                        "👉 <code>/status</code> - Cek total pool berita");
            }
        }.runTaskAsynchronously(this);

        // Timer utama: Scanning tiap X menit
        long periodTicks = scanIntervalMinutes * 60L * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                runScanCycleAsync(false);
            }
        }.runTaskTimerAsynchronously(this, 100L, periodTicks);

        // Watcher jam 00:00 WIB
        new BukkitRunnable() {
            @Override
            public void run() {
                checkMidnightSummaryTrigger();
            }
        }.runTaskTimerAsynchronously(this, 200L, 1200L);

        // Telegram Polling Listener (Tiap 3 detik)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (isPollingRunning) {
                    pollTelegramUpdates();
                }
            }
        }.runTaskTimerAsynchronously(this, 60L, 60L);
    }

    @Override
    public void onDisable() {
        isPollingRunning = false;
        getLogger().info("[CiroIntel] Plugin dinonaktifkan.");
    }

    private void loadConfiguration() {
        reloadConfig();
        this.botToken = getConfig().getString("telegram.bot_token", "8899978543:AAHlrCjXZbiJwcI_OZIzXrGdpKC5_BCGCEs");
        this.chatId = getConfig().getString("telegram.chat_id", "1936436999");
        this.scanIntervalMinutes = getConfig().getInt("crawler.scan_interval_minutes", 15);
        this.summaryHourWib = getConfig().getInt("crawler.daily_summary_hour_wib", 0);
    }

    private void pollTelegramUpdates() {
        try {
            String urlStr = "https://api.telegram.org/bot" + botToken + "/getUpdates?timeout=2&offset=" + telegramOffset;
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            if (conn.getResponseCode() != 200) return;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String resp = sb.toString();
            Pattern pUpdate = Pattern.compile("\\{\"update_id\":(\\d+).*?\"chat\":\\{\"id\":(\\d+).*?\"text\":\"([^\"]+)\"");
            Matcher m = pUpdate.matcher(resp);

            while (m.find()) {
                long upId = Long.parseLong(m.group(1));
                String fromChatId = m.group(2);
                String rawText = m.group(3).replace("\\/", "/");

                if (upId >= telegramOffset) {
                    telegramOffset = upId + 1;
                }

                if (chatId.equals(fromChatId)) {
                    handleTelegramChatCommand(rawText.trim());
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleTelegramChatCommand(String text) {
        String cmd = text.toLowerCase().split("\\s+")[0].split("@")[0];

        if (cmd.equals("/status") || cmd.equals("/cek") || cmd.equals("status")) {
            sendTelegramMessage("📊 <b>Status Radar CiroIntel:</b>\n" +
                    "• Mode: 24 Jam Aktif di Server Minecraft\n" +
                    "• AI Engine: AI Luna (GPT-6 Native)\n" +
                    "• Total Berita Tersimpan: <b>" + newsDatabase.size() + " item</b>\n" +
                    "• Interval Scan: Tiap " + scanIntervalMinutes + " menit\n" +
                    "• Jadwal Laporan: Pukul 00:00 WIB\n\n" +
                    "💡 Ketik <code>/summary</code> untuk minta analisis AI Luna sekarang!");
        } else if (cmd.equals("/scan") || cmd.equals("scan")) {
            sendTelegramMessage("🔍 <b>Memulai pemindaian berita multi-sumber sekarang...</b>");
            new BukkitRunnable() {
                @Override
                public void run() {
                    int added = runScanCycleAsync(true);
                    sendTelegramMessage("✅ <b>Scan Selesai!</b> Ditemukan <b>" + added + "</b> berita baru. Total pool: <b>" + newsDatabase.size() + "</b>.");
                }
            }.runTaskAsynchronously(this);
        } else if (cmd.equals("/summary") || cmd.equals("/lapor") || cmd.equals("/kirim") || cmd.equals("summary")) {
            sendTelegramMessage("🧠 <b>AI Luna sedang menganalisis radar berita & matriks kesehatan ekonomi dunia...</b> Mohon tunggu sebentar!");
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMidnightSummary(true);
                    // Lanjut pesan kedua: Matriks Makro Global
                    sendGlobalMacroMatrix();
                }
            }.runTaskAsynchronously(this);
        } else if (cmd.equals("/matriks") || cmd.equals("/makro") || cmd.equals("matriks")) {
            sendTelegramMessage("🌐 <b>AI Luna sedang membedah Matriks Makro Global & Status Ketakutan Pasar...</b> Mohon tunggu!");
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendGlobalMacroMatrix();
                }
            }.runTaskAsynchronously(this);
        } else if (cmd.equals("/start") || cmd.equals("/help")) {
            sendTelegramMessage("👋 <b>Selamat Datang di Ciro Market Intel Bot!</b>\n\n" +
                    "Perintah yang bisa Tuan kirim:\n" +
                    "👉 <code>/lapor</code> - Laporan Lengkap: Radar Berita 3 Sektor + Matriks Kesehatan Ekonomi Dunia\n" +
                    "👉 <code>/matriks</code> - Khusus Matriks Makro (DXY, Minyak, Gold, Yield, Saham Global & Status Ketakutan VIX)\n" +
                    "👉 <code>/scan</code> - Pindai berita terbaru detik ini\n" +
                    "👉 <code>/status</code> - Cek jumlah berita yang terkumpul");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage().trim().toLowerCase();
        if (msg.equals("!intel") || msg.equals("!status")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§b[CiroIntel] §fStatus: Aktif 24 Jam | Berita: §e" + newsDatabase.size() + " item");
        } else if (msg.equals("!scan")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§a[CiroIntel] Scanning feed live...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    int added = runScanCycleAsync(true);
                    event.getPlayer().sendMessage("§a[CiroIntel] Scan selesai! Berita baru: " + added);
                }
            }.runTaskAsynchronously(this);
        } else if (msg.equals("!summary") || msg.equals("!kirim")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§e[CiroIntel] Merangkum berita dengan AI Luna...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMidnightSummary(true);
                    event.getPlayer().sendMessage("§a[CiroIntel] Laporan Early Alpha berhasil dikirim ke Telegram!");
                }
            }.runTaskAsynchronously(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§b[CiroIntel] §fStatus: Aktif | Berita: §e" + newsDatabase.size());
            return true;
        }
        if (args[0].equalsIgnoreCase("scan")) {
            sender.sendMessage("§a[CiroIntel] Scanning...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    int added = runScanCycleAsync(true);
                    sender.sendMessage("§a[CiroIntel] Baru: " + added);
                }
            }.runTaskAsynchronously(this);
            return true;
        }
        if (args[0].equalsIgnoreCase("summary")) {
            sender.sendMessage("§e[CiroIntel] Merangkum via AI Luna...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMidnightSummary(true);
                }
            }.runTaskAsynchronously(this);
            return true;
        }
        return true;
    }

    private synchronized int runScanCycleAsync(boolean manualTrigger) {
        if (isScanning.getAndSet(true)) {
            return 0;
        }
        int totalNew = 0;
        try {
            // 1. Scan Saham IDX
            totalNew += fetchRssFeed("https://news.google.com/rss/search?q=keterbukaan+informasi+IDX+OR+akuisisi+saham+when:1d&hl=id&gl=ID&ceid=ID:id", "IDX");
            totalNew += fetchRssFeed("https://news.google.com/rss/search?q=saham+IHSG+OR+dividen+OR+merger+when:1d&hl=id&gl=ID&ceid=ID:id", "IDX");
            totalNew += fetchRssFeed("https://www.cnbcindonesia.com/market/rss", "IDX");
            totalNew += fetchRssFeed("https://www.antaranews.com/rss/ekonomi-finansial.xml", "IDX");

            // 2. Scan Gold & Makro
            totalNew += fetchRssFeed("https://news.google.com/rss/search?q=XAUUSD+OR+Gold+yield+OR+Fed+rate+when:1d&hl=en-US&gl=US&ceid=US:en", "GOLD");
            totalNew += fetchRssFeed("https://www.mining.com/commodity/gold/feed/", "GOLD");
            totalNew += fetchRssFeed("https://www.investing.com/rss/commodities.rss", "GOLD");

            // 3. Scan Bitcoin & Crypto
            totalNew += fetchRssFeed("https://news.google.com/rss/search?q=bitcoin+whale+OR+outflow+OR+ETF+inflow+when:1d&hl=en-US&gl=US&ceid=US:en", "BTC");
            totalNew += fetchRssFeed("https://cointelegraph.com/rss", "BTC");
            totalNew += fetchRssFeed("https://www.coindesk.com/arc/outboundfeeds/rss/", "BTC");
            totalNew += fetchRssFeed("https://decrypt.co/feed", "BTC");
            totalNew += fetchRssFeed("https://cryptoslate.com/feed/", "BTC");

            long cutoff = System.currentTimeMillis() - (28L * 3600L * 1000L);
            newsDatabase.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);

            getLogger().info("[CiroIntel] Scan selesai. Berita baru: " + totalNew + " | Total: " + newsDatabase.size());
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "[CiroIntel] Gagal scan: " + e.getMessage());
        } finally {
            isScanning.set(false);
        }
        return totalNew;
    }

    private int fetchRssFeed(String feedUrl, String sector) {
        int added = 0;
        try {
            URL url = URI.create(feedUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            if (conn.getResponseCode() != 200) {
                return 0;
            }

            StringBuilder xmlBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    xmlBuilder.append(line).append("\n");
                }
            }

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xmlBuilder.toString())));

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = getTagValue(item, "title");
                String link = getTagValue(item, "link");
                String source = getTagValue(item, "source");
                if (source == null || source.isEmpty()) {
                    source = extractDomain(feedUrl);
                }

                if (title != null && !title.trim().isEmpty()) {
                    String cleanTitle = cleanHtml(title.trim());
                    String normKey = normalizeTitle(cleanTitle);

                    if (!newsDatabase.containsKey(normKey)) {
                        newsDatabase.put(normKey, new NewsItem(sector, cleanTitle, source, link != null ? link.trim() : ""));
                        added++;
                    }
                }
            }
        } catch (Exception ignored) {}
        return added;
    }

    private void checkMidnightSummaryTrigger() {
        ZonedDateTime nowWib = ZonedDateTime.now(ZoneId.of("Asia/Jakarta"));
        int currentDay = nowWib.getDayOfYear();
        int currentHour = nowWib.getHour();
        int currentMinute = nowWib.getMinute();

        if (currentHour == summaryHourWib && currentMinute <= 5 && lastReportedDay != currentDay) {
            lastReportedDay = currentDay;
            sendMidnightSummary(false);
            sendGlobalMacroMatrix();
        }
    }

    private void sendMidnightSummary(boolean isManual) {
        if (newsDatabase.isEmpty()) {
            runScanCycleAsync(false);
        }

        List<NewsItem> idxList = new ArrayList<>();
        List<NewsItem> goldList = new ArrayList<>();
        List<NewsItem> btcList = new ArrayList<>();

        for (NewsItem item : newsDatabase.values()) {
            if ("IDX".equalsIgnoreCase(item.sector)) idxList.add(item);
            else if ("GOLD".equalsIgnoreCase(item.sector)) goldList.add(item);
            else if ("BTC".equalsIgnoreCase(item.sector)) btcList.add(item);
        }

        List<NewsItem> topIdx = filterHighImpact(idxList, Arrays.asList("akuisisi", "merger", "dividen", "investasi", "baterai", "laba", "suspensi", "esdm", "pabrik", "tender", "bumn"));
        List<NewsItem> topGold = filterHighImpact(goldList, Arrays.asList("fed", "rate", "yield", "inflation", "cpi", "war", "middle east", "central bank", "china", "pboc", "gold"));
        List<NewsItem> topBtc = filterHighImpact(btcList, Arrays.asList("whale", "etf", "sec", "inflow", "outflow", "blackrock", "binance", "coinbase", "reserve", "all-time", "trump"));

        StringBuilder promptSb = new StringBuilder();
        promptSb.append("Daftar berita pasar 24 jam terakhir:\n\n");

        promptSb.append("[SEKTOR SAHAM IDX]:\n");
        for (NewsItem n : topIdx) promptSb.append("- ").append(n.title).append("\n");

        promptSb.append("\n[SEKTOR GOLD / XAUUSD & MAKRO]:\n");
        for (NewsItem n : topGold) promptSb.append("- ").append(n.title).append("\n");

        promptSb.append("\n[SEKTOR BITCOIN & CRYPTO]:\n");
        for (NewsItem n : topBtc) promptSb.append("- ").append(n.title).append("\n");

        promptSb.append("\nTugas Anda sebagai AI Intelijen Pasar Tuan Ciro:\n");
        promptSb.append("Buat laporan analisis cerdas untuk 3 sektor di atas (Saham IDX, Gold, Bitcoin). Jelaskan:\n");
        promptSb.append("1. Inti & situasi pasar saat ini\n");
        promptSb.append("2. Prediksi masa depan / potensi pergerakan pasar\n");
        promptSb.append("3. Peringatan bahaya / resiko yang harus diwaspadai\n");
        promptSb.append("Gunakan format teks HTML Telegram (<b>bold</b>, <i>italic</i>, <code>code</code>). DILARANG menggunakan tanda dolar ($) atau formula LaTeX. Buat padat, tajam, dan profesional.");

        // Panggil AI Luna
        String aiAnalysis = callLunaAI(promptSb.toString());

        if (aiAnalysis != null && !aiAnalysis.trim().isEmpty()) {
            sendTelegramMessage("📡 <b>RADAR INTELIJEN PASAR — ANALISIS AI LUNA</b>\n" +
                    "⏱ <i>Periode Pantauan 24 Jam</i>\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    aiAnalysis +
                    "\n\n━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "🎯 <i>Status: Analisis cerdas dihasilkan oleh AI Luna (GPT-6) via Paper Minecraft.</i>");
        } else {
            sendTelegramFallbackSummary(topIdx, topGold, topBtc);
        }
    }

    private String callLunaAI(String prompt) {
        try {
            URL url = URI.create(LUNA_URL).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + LUNA_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            // Escape JSON prompt
            String safePrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            // Luna adalah model reasoning / CoT (Chain of Thought), butuh max_tokens besar (2500) agar reasoning_tokens tidak menghabiskan kuota sebelum content keluar!
            String jsonPayload = "{\"model\":\"" + LUNA_MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + safePrompt + "\"}],\"max_tokens\":2500}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                getLogger().warning("[Luna AI] Response code: " + code);
                sendTelegramMessage("⚠️ <i>Gagal kontak AI Luna: HTTP " + code + "</i>");
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }

            String resp = sb.toString();
            // Robust extraction untuk isi string "content": "..."
            String key = "\"content\":";
            int keyIdx = resp.indexOf(key);
            if (keyIdx != -1) {
                int quoteStart = resp.indexOf('\"', keyIdx + key.length());
                if (quoteStart != -1) {
                    StringBuilder extracted = new StringBuilder();
                    boolean escaped = false;
                    for (int i = quoteStart + 1; i < resp.length(); i++) {
                        char c = resp.charAt(i);
                        if (escaped) {
                            if (c == 'n') extracted.append("\n");
                            else if (c == '\"') extracted.append('\"');
                            else if (c == '\\') extracted.append('\\');
                            else if (c == 't') extracted.append('\t');
                            else if (c == 'r') { /* abaikan \r */ }
                            else if (c == 'u' && i + 4 < resp.length()) {
                                try {
                                    String hex = resp.substring(i + 1, i + 5);
                                    extracted.append((char) Integer.parseInt(hex, 16));
                                    i += 4;
                                } catch (Exception ignored) {
                                    extracted.append(c);
                                }
                            } else {
                                extracted.append(c);
                            }
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if (c == '\"') {
                            break;
                        } else {
                            extracted.append(c);
                        }
                    }
                    String textRes = extracted.toString().trim();
                    if (!textRes.isEmpty()) {
                        // Konversi markdown header / bold ke HTML Telegram
                        textRes = textRes.replaceAll("\\*\\*([^\\*]+)\\*\\*", "<b>$1</b>");
                        textRes = textRes.replaceAll("###\\s*(.+)", "<b>$1</b>");
                        textRes = textRes.replaceAll("##\\s*(.+)", "<b>$1</b>");
                        textRes = textRes.replaceAll("#\\s*(.+)", "<b>$1</b>");
                        return textRes;
                    } else {
                        getLogger().warning("[Luna AI] Content kosong dalam respons JSON! Raw response: " + resp);
                        sendTelegramMessage("⚠️ <i>AI Luna merespons tapi isinya kosong.</i>");
                    }
                } else {
                    getLogger().warning("[Luna AI] Tidak ditemukan tanda petik setelah 'content:'! Raw: " + resp);
                    sendTelegramMessage("⚠️ <i>Format JSON AI Luna tidak memiliki string content.</i>");
                }
            } else {
                getLogger().warning("[Luna AI] Gagal menemukan key 'content' di respons JSON. Raw: " + resp);
                sendTelegramMessage("⚠️ <i>Gagal membaca respons AI Luna (key 'content' tidak ditemukan).</i>");
            }
        } catch (Exception e) {
            getLogger().warning("[Luna AI] Error calling API: " + e.getMessage());
            sendTelegramMessage("⚠️ <i>Gagal menghubungi AI Luna: " + e.getMessage() + "</i>");
        }
        return null;
    }

    private void sendGlobalMacroMatrix() {
        try {
            // Instrumen Matriks Global + Indeks Kripto
            String[] tvSymbols = new String[]{
                    "TVC:DXY", "TVC:USOIL", "OANDA:XAUUSD", "INDEX:BTCUSD",
                    "CRYPTOCAP:BTC.D", "CRYPTOCAP:USDT.D", "CRYPTOCAP:TOTAL",
                    "SP:SPX", "NASDAQ:NDX", "INDEX:NKY", "INDEX:HSI", "INDEX:DAX",
                    "TVC:US10Y", "TVC:VIX"
            };
            String[] names = new String[]{
                    "DXY (US Dollar Index)", "Minyak Mentah (WTI Crude)", "Emas (Gold / XAUUSD)", "Bitcoin Spot (BTCUSD)",
                    "BTC Dominance (BTC.D %)", "USDT Dominance (USDT.D % - Cash)", "Total Crypto Market Cap",
                    "S&P 500 (Wall St)", "Nasdaq 100 (Tech US)", "Nikkei 225 (Jepang)",
                    "Hang Seng (China/HK)", "DAX (Jerman/Eropa)", "Yield US 10Y", "VIX (Wall St Fear Index)"
            };

            // Ambil data live via TradingView WebSocket
            Map<String, String> tvData = fetchTradingViewQuotes(tvSymbols);

            StringBuilder matrixData = new StringBuilder();
            matrixData.append("[DATA LIVE TRADINGVIEW WEBSOCKET — INDIKATOR KESEHATAN EKONOMI & KRIPTO DUNIA]:\n");
            for (int i = 0; i < tvSymbols.length; i++) {
                String sym = tvSymbols[i];
                String name = names[i];
                String val = tvData.get(sym);
                if (val == null || val.isEmpty()) {
                    // Fallback Yahoo jika TV socket belum terima tick simbol tertentu
                    val = fetchYahooTickerFallback(sym);
                }
                matrixData.append("- ").append(name).append(": ").append(val).append("\n");
            }

            StringBuilder promptSb = new StringBuilder();
            promptSb.append("Berikut adalah data live indikator kesehatan ekonomi & kripto dunia (Zero Delay TradingView WebSocket):\n\n");
            promptSb.append(matrixData.toString()).append("\n");
            promptSb.append("Tugas Anda sebagai AI Intelijen Pasar Tuan Ciro:\n");
            promptSb.append("Buat analisis Matriks Makro, Kripto & Kesehatan Ekonomi Dunia:\n");
            promptSb.append("1. Peta Likuiditas Makro: Hubungkan DXY, Yield US 10Y, Minyak, Emas, dan Bursa Saham AS/Asia/Eropa.\n");
            promptSb.append("2. Peta Pasar Kripto: Analisa BTC, BTC Dominance (BTC.D %), USDT Dominance (USDT.D %), dan Total Crypto Market Cap. Jelaskan apakah uang sedang lari ke Bitcoin, terjadi rotasi Altseason, atau trader panik kabur ke cash USDT.\n");
            promptSb.append("3. Status Ketakutan Pasar: Hubungkan VIX Wall Street dan USDT.D Kripto (Risk-On vs Risk-Off).\n");
            promptSb.append("4. Di bagian paling bawah, tulis KESIMPULAN STATUS DUNIA dalam format tegas:\n");
            promptSb.append("🌐 <b>STATUS KESEHATAN EKONOMI DUNIA:</b> [Pilih salah satu misal: RISK-OFF WASPADA / RISK-ON MODERAT / STAGFLASI GEOPOLITIK / PANIK / KONSOLIDASI]\n");
            promptSb.append("👉 <b>Catatan Kunci:</b> (1-2 kalimat vonis kesehatan ekonomi & kripto global)\n\n");
            promptSb.append("Gunakan format teks HTML Telegram (<b>bold</b>, <i>italic</i>, <code>code</code>). DILARANG menggunakan tanda dolar ($) atau formula LaTeX. Buat tajam, lugas, dan padat.");

            String aiAnalysis = callLunaAI(promptSb.toString());
            if (aiAnalysis != null && !aiAnalysis.trim().isEmpty()) {
                sendTelegramMessage("🌐 <b>MATRIKS MAKRO & BAROMETER KESEHATAN DUNIA</b>\n" +
                        "📡 <i>Sumber: TradingView WebSocket (Real-Time Zero Delay)</i>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        aiAnalysis +
                        "\n\n━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                        "🎯 <i>Status: Analisis Matriks Makro & Kripto dihasilkan oleh AI Luna (GPT-6).</i>");
            } else {
                sendTelegramMessage("🌐 <b>MATRIKS PASAR GLOBAL & KRIPTO (RAW):</b>\n\n" + matrixData.toString());
            }
        } catch (Exception e) {
            getLogger().warning("[MacroMatrix] Gagal: " + e.getMessage());
        }
    }

    private Map<String, String> fetchTradingViewQuotes(String[] symbols) {
        Map<String, String> results = new ConcurrentHashMap<>();
        try {
            String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder sb = new StringBuilder("qs_");
            Random rnd = new Random();
            for (int i = 0; i < 12; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
            String qs = sb.toString();

            CountDownLatch latch = new CountDownLatch(symbols.length);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            WebSocket.Listener wsListener = new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        String msg = buffer.toString();
                        buffer.setLength(0);

                        if (msg.contains("~h~")) {
                            webSocket.sendText(msg, true);
                        }

                        // Parse frames qsd
                        if (msg.contains("\"m\":\"qsd\"")) {
                            for (String sym : symbols) {
                                if (!results.containsKey(sym) && msg.contains("\"" + sym + "\"")) {
                                    // Ekstrak lp dan chp
                                    int symIdx = msg.indexOf("\"" + sym + "\"");
                                    int vIdx = msg.indexOf("\"v\":{", symIdx);
                                    if (vIdx != -1) {
                                        int endV = msg.indexOf("}", vIdx);
                                        if (endV != -1) {
                                            String vSnippet = msg.substring(vIdx, endV + 1);
                                            double lp = extractJsonDouble(vSnippet, "\"lp\":");
                                            double chp = extractJsonDouble(vSnippet, "\"chp\":");
                                            if (lp > 0) {
                                                String formatted = String.format(Locale.US, "%.2f (%+.2f%%)", lp, chp);
                                                results.put(sym, formatted);
                                                latch.countDown();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }
            };

            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .header("Origin", "https://www.tradingview.com")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .buildAsync(URI.create("wss://data.tradingview.com/socket.io/websocket"), wsListener);

            WebSocket ws = wsFuture.get(5, TimeUnit.SECONDS);

            // Kirim frame auth dan quote session
            sendTvFrame(ws, "{\"m\":\"set_auth_token\",\"p\":[\"unauthorized_user_token\"]}");
            sendTvFrame(ws, "{\"m\":\"quote_create_session\",\"p\":[\"" + qs + "\"]}");
            sendTvFrame(ws, "{\"m\":\"quote_set_fields\",\"p\":[\"" + qs + "\",\"lp\",\"ch\",\"chp\",\"short_name\"]}");

            StringBuilder symParams = new StringBuilder();
            symParams.append("{\"m\":\"quote_add_symbols\",\"p\":[\"").append(qs).append("\"");
            for (String sym : symbols) {
                symParams.append(",\"").append(sym).append("\"");
            }
            symParams.append("]}");
            sendTvFrame(ws, symParams.toString());

            // Tunggu paling lama 3.5 detik untuk mengumpulkan semua quote
            latch.await(3500, TimeUnit.MILLISECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done");
        } catch (Exception e) {
            getLogger().warning("[TradingView WS] Gagal ambil quote: " + e.getMessage());
        }
        return results;
    }

    private void sendTvFrame(WebSocket ws, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String frame = "~m~" + bytes.length + "~m~" + json;
        ws.sendText(frame, true);
    }

    private String fetchYahooTickerFallback(String tvSymbol) {
        String ySym = "^GSPC";
        if (tvSymbol.contains("DXY")) ySym = "DX-Y.NYB";
        else if (tvSymbol.contains("USOIL")) ySym = "CL=F";
        else if (tvSymbol.contains("XAUUSD")) ySym = "GC=F";
        else if (tvSymbol.contains("NDX")) ySym = "^IXIC";
        else if (tvSymbol.contains("NKY")) ySym = "^N225";
        else if (tvSymbol.contains("HSI")) ySym = "^HSI";
        else if (tvSymbol.contains("DAX")) ySym = "^GDAXI";
        else if (tvSymbol.contains("US10Y")) ySym = "^TNX";
        else if (tvSymbol.contains("VIX")) ySym = "^VIX";
        return fetchYahooTicker(ySym);
    }

    private String fetchYahooTicker(String symbol) {
        try {
            String urlStr = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8.name()) + "?interval=1d&range=1d";
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            if (conn.getResponseCode() != 200) {
                return "N/A";
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String resp = sb.toString();
            // Ekstrak regularMarketPrice & previousClose sederhana
            double price = extractJsonDouble(resp, "\"regularMarketPrice\":");
            double prev = extractJsonDouble(resp, "\"chartPreviousClose\":");
            if (prev == 0) prev = extractJsonDouble(resp, "\"previousClose\":");

            if (price > 0) {
                if (prev > 0) {
                    double pct = (price - prev) / prev * 100.0;
                    return String.format(Locale.US, "%.2f (%+.2f%%)", price, pct);
                } else {
                    return String.format(Locale.US, "%.2f", price);
                }
            }
        } catch (Exception ignored) {}
        return "N/A";
    }

    private double extractJsonDouble(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return 0.0;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void sendTelegramFallbackSummary(List<NewsItem> topIdx, List<NewsItem> topGold, List<NewsItem> topBtc) {
        StringBuilder sb = new StringBuilder();
        sb.append("📡 <b>RADAR INTELIJEN PASAR — EARLY ALPHA</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🇮🇩 <b>1. SAHAM IDX & AKSI KORPORASI:</b>\n");
        formatSection(sb, topIdx, "Belum ada isu signifikan.");
        sb.append("\n🟡 <b>2. GOLD & MAKRO:</b>\n");
        formatSection(sb, topGold, "Sentimen emas stabil.");
        sb.append("\n🟠 <b>3. BITCOIN & WHALE:</b>\n");
        formatSection(sb, topBtc, "Arus koin normal.");
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sendTelegramMessage(sb.toString());
    }

    private List<NewsItem> filterHighImpact(List<NewsItem> input, List<String> keywords) {
        List<NewsItem> prioritized = new ArrayList<>();
        List<NewsItem> regular = new ArrayList<>();

        for (NewsItem n : input) {
            String lower = n.title.toLowerCase();
            boolean match = false;
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    match = true;
                    break;
                }
            }
            if (match) prioritized.add(n);
            else regular.add(n);
        }

        List<NewsItem> result = new ArrayList<>();
        result.addAll(prioritized);
        result.addAll(regular);

        return result.subList(0, Math.min(result.size(), 4));
    }

    private void formatSection(StringBuilder sb, List<NewsItem> list, String emptyFallback) {
        if (list.isEmpty()) {
            sb.append("• <i>").append(emptyFallback).append("</i>\n");
            return;
        }
        int num = 1;
        for (NewsItem item : list) {
            sb.append(num++).append(". <b>").append(item.title).append("</b>\n");
            sb.append("   👉 <i>Sumber: ").append(item.source).append("</i>\n");
        }
    }

    private void sendTelegramMessage(String text) {
        try {
            URL url = URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8) +
                    "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) +
                    "&parse_mode=HTML" +
                    "&disable_web_page_preview=true";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                getLogger().warning("[CiroIntel] Telegram response code: " + code);
            }
        } catch (Exception e) {
            getLogger().warning("[CiroIntel] Gagal kirim Telegram: " + e.getMessage());
        }
    }

    private String normalizeTitle(String raw) {
        String s = raw.replaceAll("\\s*-\\s*[^-]+$", "");
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String cleanHtml(String text) {
        return text.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("<[^>]*>", "");
    }

    private String getTagValue(Element element, String tagName) {
        NodeList nl = element.getElementsByTagName(tagName);
        if (nl != null && nl.getLength() > 0) {
            return nl.item(0).getTextContent();
        }
        return null;
    }

    private String extractDomain(String urlString) {
        try {
            URI uri = URI.create(urlString);
            String host = uri.getHost();
            if (host != null) {
                return host.replaceFirst("^www\\.", "");
            }
        } catch (Exception ignored) {}
        return "Radar News";
    }
}
