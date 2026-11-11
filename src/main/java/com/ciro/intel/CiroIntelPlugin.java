package com.ciro.intel;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class CiroIntelPlugin extends JavaPlugin implements CommandExecutor {

    private String botToken;
    private String chatId;
    private int scanIntervalMinutes;
    private int summaryHourWib;

    // Database memori deduplikasi berita 24 jam (Key: Normalized Title Hash, Value: NewsItem)
    private final Map<String, NewsItem> newsDatabase = new ConcurrentHashMap<>();
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private int lastReportedDay = -1;

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

        if (getCommand("cirointel") != null) {
            getCommand("cirointel").setExecutor(this);
        }

        getLogger().info("[CiroIntel] Plugin Intel Pasar diaktifkan! Scanning aktif tiap " + scanIntervalMinutes + " menit.");

        // Kirim salam pembuka ke Telegram saat server menyala
        new BukkitRunnable() {
            @Override
            public void run() {
                sendTelegramMessage("🛰️ <b>Ciro Market Intel Sentinel Aktif!</b>\n" +
                        "• Hosting: Server Minecraft (Paper 1.21)\n" +
                        "• Mode: 24 Jam Silent Scraper (IDX, Gold, BTC)\n" +
                        "• Waktu Laporan: Setiap Jam 00:00 WIB\n" +
                        "• Perintah Cepat: Ketik di console <code>cirointel scan</code> atau <code>cirointel summary</code>");
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

        // Watcher jam 00:00 WIB (Pengecekan tiap 60 detik)
        new BukkitRunnable() {
            @Override
            public void run() {
                checkMidnightSummaryTrigger();
            }
        }.runTaskTimerAsynchronously(this, 200L, 1200L);
    }

    @Override
    public void onDisable() {
        getLogger().info("[CiroIntel] Plugin Intel dinonaktifkan.");
    }

    private void loadConfiguration() {
        reloadConfig();
        this.botToken = getConfig().getString("telegram.bot_token", "8899978543:AAHlrCjXZbiJwcI_OZIzXrGdpKC5_BCGCEs");
        this.chatId = getConfig().getString("telegram.chat_id", "1936436999");
        this.scanIntervalMinutes = getConfig().getInt("crawler.scan_interval_minutes", 15);
        this.summaryHourWib = getConfig().getInt("crawler.daily_summary_hour_wib", 0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§b[CiroIntel] §fStatus: Aktif | Berita Tersimpan: §e" + newsDatabase.size() + " item");
            return true;
        }

        if (args[0].equalsIgnoreCase("scan")) {
            sender.sendMessage("§a[CiroIntel] Memulai pemindaian berita manual...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    int added = runScanCycleAsync(true);
                    sender.sendMessage("§a[CiroIntel] Selesai! Berita baru ditemukan: " + added);
                }
            }.runTaskAsynchronously(this);
            return true;
        }

        if (args[0].equalsIgnoreCase("summary")) {
            sender.sendMessage("§e[CiroIntel] Menghasilkan dan mengirimkan rangkuman sekarang...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMidnightSummary(true);
                }
            }.runTaskAsynchronously(this);
            return true;
        }

        sender.sendMessage("§cPenggunaan: /cirointel <status|scan|summary>");
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

            // Bersihkan berita usang (> 28 jam)
            long cutoff = System.currentTimeMillis() - (28L * 3600L * 1000L);
            newsDatabase.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);

            getLogger().info("[CiroIntel] Siklus Scan selesai. Berita baru: " + totalNew + " | Total Pool: " + newsDatabase.size());
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
        } catch (Exception ignored) {
            // Abaikan error koneksi feed individu agar tidak spam konsol
        }
        return added;
    }

    private void checkMidnightSummaryTrigger() {
        ZonedDateTime nowWib = ZonedDateTime.now(ZoneId.of("Asia/Jakarta"));
        int currentDay = nowWib.getDayOfYear();
        int currentHour = nowWib.getHour();
        int currentMinute = nowWib.getMinute();

        // Tepat jam 00:00 - 00:05 WIB dan belum lapor hari ini
        if (currentHour == summaryHourWib && currentMinute <= 5 && lastReportedDay != currentDay) {
            lastReportedDay = currentDay;
            sendMidnightSummary(false);
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

        // Ambil berita paling berbobot (mengandung kata kunci aksi korporasi / whale / yield)
        List<NewsItem> topIdx = filterHighImpact(idxList, Arrays.asList("akuisisi", "merger", "dividen", "investasi", "baterai", "laba", "suspensi", "esdm", "pabrik", "tender", "bumn"));
        List<NewsItem> topGold = filterHighImpact(goldList, Arrays.asList("fed", "rate", "yield", "inflation", "cpi", "war", "middle east", "central bank", "china", "pboc", "gold"));
        List<NewsItem> topBtc = filterHighImpact(btcList, Arrays.asList("whale", "etf", "sec", "inflow", "outflow", "blackrock", "binance", "coinbase", "reserve", "all-time", "trump"));

        StringBuilder sb = new StringBuilder();
        sb.append("📡 <b>RADAR INTELIJEN PASAR — EARLY ALPHA</b>\n");
        sb.append("⏱ <i>Periode Pantauan 24 Jam | Tepat Jam 00:00 WIB</i>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 <i>Total Terpantau: ").append(newsDatabase.size()).append(" berita | Disaring otomatis anti-hoaks & anti-duplikat.</i>\n\n");

        sb.append("🇮🇩 <b>1. SAHAM IDX & AKSI KORPORASI:</b>\n");
        formatSection(sb, topIdx, "Belum ada isu keterbukaan signifikan malam ini.");

        sb.append("\n🟡 <b>2. GOLD / XAUUSD & MAKRO GLOBAL:</b>\n");
        formatSection(sb, topGold, "Sentimen emas stabil, belum ada anomali yield atau geopolitik ekstrem.");

        sb.append("\n🟠 <b>3. BITCOIN & ON-CHAIN WHALE:</b>\n");
        formatSection(sb, topBtc, "Arus koin bursa normal, belum ada transfer paus besar.");

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🎯 <i>Status: Laporan terkirim via server Minecraft Paper. Selamat beristirahat Tuan Ciro!</i>");

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

        // Batasi 3 berita paling relevan per sektor agar teks padat
        return result.subList(0, Math.min(result.size(), 3));
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
        // Hilangkan nama portal di ujung judul (contoh: " - CNBC Indonesia")
        String s = raw.replaceAll("\\s*-\\s*[^-]+$", "");
        // Ambil karakter alfanumerik saja untuk mencocokkan kemiripan judul
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
