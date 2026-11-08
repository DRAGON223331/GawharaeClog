package dev.gawhara.clog;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GawharaeClog extends JavaPlugin implements Listener {

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private ExecutorService executor;
    private HttpClient client;
    private String webhookUrl;
    private String username;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        webhookUrl = getConfig().getString("webhook-url", "");
        username = getConfig().getString("username", "Gawhara Clog");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GawharaeClog-Webhook");
            t.setDaemon(true);
            return t;
        });
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("GawharaeClog enabled.");
    }

    @Override
    public void onDisable() {
        if (executor != null) executor.shutdown();
    }

    // Fires for F3+F4, /gamemode, other plugins, etc. At MONITOR the player is still in the OLD mode.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.CREATIVE) return;

        Player p = event.getPlayer();
        GameMode old = p.getGameMode();
        if (old == GameMode.CREATIVE) return;

        Location l = p.getLocation();
        String cause = event.getCause().name();
        String json = buildJson(
                p.getName(), p.getUniqueId().toString(),
                old.name(), cause,
                l.getWorld() != null ? l.getWorld().getName() : "?",
                l.getBlockX(), l.getBlockY(), l.getBlockZ());
        send(json);
    }

    private String buildJson(String name, String uuid, String old, String cause,
                             String world, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(esc(username)).append("\",");
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"Creative Mode Enabled\",");
        sb.append("\"color\":16733525,");
        sb.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(esc(uuid)).append("\"},");
        sb.append("\"fields\":[");
        sb.append(field("Player", name, true)).append(",");
        sb.append(field("Previous mode", old, true)).append(",");
        sb.append(field("Cause", cause, true)).append(",");
        sb.append(field("World", world, true)).append(",");
        sb.append(field("Location", x + ", " + y + ", " + z, true)).append(",");
        sb.append(field("UUID", uuid, false));
        sb.append("],");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\"");
        sb.append("}]}");
        return sb.toString();
    }

    private String field(String n, String v, boolean inline) {
        return "{\"name\":\"" + esc(n) + "\",\"value\":\"" + esc(v) + "\",\"inline\":" + inline + "}";
    }

    private void send(String json) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            getLogger().warning("webhook-url is empty in config.yml");
            return;
        }
        executor.submit(() -> {
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    int code = res.statusCode();
                    if (code >= 200 && code < 300) return;
                    if (code == 429) {
                        Matcher m = RETRY_AFTER.matcher(res.body());
                        double wait = m.find() ? Double.parseDouble(m.group(1)) : 1.5;
                        Thread.sleep((long) (wait * 1000) + 250);
                        continue;
                    }
                    getLogger().warning("Discord webhook returned HTTP " + code + ": " + res.body());
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    getLogger().warning("Failed to send webhook: " + ex.getMessage());
                    return;
                }
            }
        });
    }

    private static String esc(String s) {
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
