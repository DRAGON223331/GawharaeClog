package dev.gawhara.clog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** إرسال الرسايل للديسكورد (ويب هوك أو بوت). */
final class Discord {

    record Field(String name, String value, boolean inline) {}

    record Embed(String title, int color, String description, List<Field> fields, String thumbnailUuid) {
        int size() {
            int n = title == null ? 0 : title.length();
            n += description == null ? 0 : description.length();
            if (fields != null) {
                for (Field f : fields) n += f.name().length() + f.value().length();
            }
            return n + 60;
        }
    }

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final Logger log;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GawharaeClog-Sender");
        t.setDaemon(true);
        return t;
    });

    final AtomicInteger ok = new AtomicInteger();
    final AtomicInteger fail = new AtomicInteger();
    volatile String lastError = "لا يوجد";

    volatile boolean bot;
    volatile String webhookUrl = "";
    volatile String botToken = "";
    volatile String channelId = "";
    volatile String username = "Gawhara Clog";
    volatile String avatarUrl = "";
    volatile String serverName = "Gawharasmp";

    Discord(Logger log) {
        this.log = log;
    }

    boolean configured() {
        if (bot) {
            return !botToken.isBlank() && !channelId.isBlank()
                    && !botToken.startsWith("PUT_") && !channelId.startsWith("PUT_");
        }
        return !webhookUrl.isBlank() && !webhookUrl.startsWith("PUT_");
    }

    /** بيقسّم الإيمبدات على أكتر من رسالة لو كبيرة (حدود ديسكورد: 10 إيمبد و 6000 حرف). */
    void send(String content, List<Embed> embeds) {
        if (!configured()) {
            lastError = "وجهة ديسكورد مش متظبطة في config.yml";
            return;
        }
        List<Embed> group = new ArrayList<>();
        int size = 0;
        boolean first = true;
        for (Embed e : embeds) {
            if (!group.isEmpty() && (group.size() >= 10 || size + e.size() > 5500)) {
                post(first ? content : "", group);
                first = false;
                group = new ArrayList<>();
                size = 0;
            }
            group.add(e);
            size += e.size();
        }
        if (!group.isEmpty()) {
            post(first ? content : "", group);
        }
    }

    void send(String content, Embed embed) {
        send(content, List.of(embed));
    }

    private void post(String content, List<Embed> embeds) {
        final String json = json(content, embeds);
        final boolean useBot = bot;
        final String url = useBot ? "https://discord.com/api/v10/channels/" + channelId + "/messages" : webhookUrl;
        final String token = botToken;
        executor.submit(() -> {
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(json));
                    if (useBot) {
                        rb.header("Authorization", "Bot " + token);
                        rb.header("User-Agent", "GawharaeClog (gawharasmp, 1.2)");
                    }
                    HttpResponse<String> res = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                    int code = res.statusCode();
                    if (code >= 200 && code < 300) {
                        ok.incrementAndGet();
                        return;
                    }
                    if (code == 429) {
                        Matcher m = RETRY_AFTER.matcher(res.body());
                        double wait = m.find() ? Double.parseDouble(m.group(1)) : 1.5;
                        Thread.sleep((long) (wait * 1000) + 250);
                        continue;
                    }
                    fail.incrementAndGet();
                    lastError = "HTTP " + code + " " + cut(res.body(), 150);
                    log.warning("ديسكورد رجّع " + lastError);
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    fail.incrementAndGet();
                    lastError = String.valueOf(ex.getMessage());
                    log.warning("فشل الإرسال لديسكورد: " + lastError);
                    return;
                }
            }
            fail.incrementAndGet();
            lastError = "ديسكورد عمل rate limit كتير";
        });
    }

    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ json

    private String json(String content, List<Embed> embeds) {
        StringBuilder sb = new StringBuilder("{");
        if (!bot) {
            sb.append("\"username\":\"").append(esc(username)).append("\",");
            if (!avatarUrl.isBlank()) sb.append("\"avatar_url\":\"").append(esc(avatarUrl)).append("\",");
        }
        if (content != null && !content.isBlank()) {
            sb.append("\"content\":\"").append(esc(cut(content, 1900))).append("\",");
            sb.append("\"allowed_mentions\":{\"parse\":[\"roles\",\"users\",\"everyone\"]},");
        }
        sb.append("\"embeds\":[");
        String now = Instant.now().toString();
        for (int i = 0; i < embeds.size(); i++) {
            Embed e = embeds.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"").append(esc(cut(e.title(), 250))).append("\",");
            sb.append("\"color\":").append(e.color()).append(",");
            if (e.description() != null && !e.description().isBlank()) {
                sb.append("\"description\":\"").append(esc(cut(e.description(), 4000))).append("\",");
            }
            if (e.thumbnailUuid() != null) {
                sb.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(esc(e.thumbnailUuid())).append("\"},");
            }
            sb.append("\"fields\":[");
            List<Field> fields = e.fields() == null ? List.of() : e.fields();
            int count = 0;
            for (Field f : fields) {
                if (count >= 25) break;
                if (count > 0) sb.append(",");
                String v = (f.value() == null || f.value().isBlank()) ? "-" : cut(f.value(), 1000);
                sb.append("{\"name\":\"").append(esc(cut(f.name(), 250))).append("\",\"value\":\"")
                        .append(esc(v)).append("\",\"inline\":").append(f.inline()).append("}");
                count++;
            }
            sb.append("],");
            sb.append("\"footer\":{\"text\":\"").append(esc(serverName)).append("\"},");
            sb.append("\"timestamp\":\"").append(now).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
