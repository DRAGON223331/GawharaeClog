package dev.gawhara.clog;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GawharaeClog extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final List<String> SUBS = List.of("reload", "enable", "disable", "status", "test", "help");

    private record Field(String name, String value, boolean inline) {}
    private record Cmd(String sender, String root, String[] args, String line, long at) {}

    private final ConcurrentLinkedDeque<Cmd> recent = new ConcurrentLinkedDeque<>();
    private final AtomicInteger sentOk = new AtomicInteger();
    private final AtomicInteger sentFail = new AtomicInteger();
    private volatile String lastError = "none";

    private ExecutorService executor;
    private HttpClient client;

    // settings
    private boolean enabled;
    private boolean bot;
    private String webhookUrl, botToken, channelId, username, avatarUrl, serverName, mention, prefix;
    private boolean logEnter, logLeave, logJoin, incLocation, incUuid, incExecutor, notifyEnabled;
    private String enterTitle, leaveTitle, joinTitle, notifyMessage;
    private int enterColor, leaveColor, joinColor;
    private Set<String> causes = new HashSet<>();
    private Set<String> ignored = new HashSet<>();
    private Set<String> gmCommands = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GawharaeClog-Sender");
            t.setDaemon(true);
            return t;
        });
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("gclog");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        if (!configured()) {
            getLogger().warning("Discord target is not configured yet. Edit config.yml then run /gclog reload");
        }
        getLogger().info("GawharaeClog enabled (mode: " + (bot ? "bot" : "webhook") + ", logging: " + enabled + ")");
    }

    @Override
    public void onDisable() {
        if (executor != null) executor.shutdown();
    }

    // ---------------------------------------------------------------- settings

    private void loadSettings() {
        var c = getConfig();
        enabled = c.getBoolean("enabled", true);
        bot = "bot".equalsIgnoreCase(c.getString("mode", "webhook"));
        webhookUrl = c.getString("webhook-url", "").trim();
        botToken = c.getString("bot-token", "").trim();
        channelId = c.getString("channel-id", "").trim();
        username = c.getString("username", "Gawhara Clog");
        avatarUrl = c.getString("avatar-url", "").trim();
        serverName = c.getString("server-name", "Gawharasmp");
        mention = c.getString("mention", "").trim();
        prefix = c.getString("prefix", "&8[&cGawharaeClog&8] &7");

        logEnter = c.getBoolean("log.enter-creative", true);
        logLeave = c.getBoolean("log.leave-creative", false);
        logJoin = c.getBoolean("log.join-in-creative", false);

        incLocation = c.getBoolean("embed.include-location", true);
        incUuid = c.getBoolean("embed.include-uuid", true);
        incExecutor = c.getBoolean("embed.include-executor", true);
        enterTitle = c.getString("embed.enter-title", "Creative Mode Enabled");
        leaveTitle = c.getString("embed.leave-title", "Creative Mode Disabled");
        joinTitle = c.getString("embed.join-title", "Joined In Creative Mode");
        enterColor = color(c.getString("embed.enter-color"), 0xFF5555);
        leaveColor = color(c.getString("embed.leave-color"), 0x55FF55);
        joinColor = color(c.getString("embed.join-color"), 0xFFAA00);

        notifyEnabled = c.getBoolean("ingame-notify.enabled", true);
        notifyMessage = c.getString("ingame-notify.message", "&c[Clog] &f{player} &7{old} -> &c{new}");

        causes = new HashSet<>();
        for (String s : c.getStringList("causes")) causes.add(s.trim().toUpperCase(Locale.ROOT));
        ignored = new HashSet<>();
        for (String s : c.getStringList("ignored-players")) ignored.add(s.trim().toLowerCase(Locale.ROOT));
        gmCommands = new HashSet<>();
        for (String s : c.getStringList("gamemode-commands")) gmCommands.add(s.trim().toLowerCase(Locale.ROOT));
    }

    private static int color(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.replace("#", "").trim(), 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean configured() {
        if (bot) return !botToken.isBlank() && !channelId.isBlank()
                && !botToken.startsWith("PUT_") && !channelId.startsWith("PUT_");
        return !webhookUrl.isBlank() && !webhookUrl.startsWith("PUT_");
    }

    // ---------------------------------------------------------------- commands tracking

    private static String rootOf(String line) {
        String[] parts = line.trim().split("\\s+");
        String r = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        int i = r.indexOf(':');
        return i >= 0 ? r.substring(i + 1) : r;
    }

    private void track(String sender, String rawLine) {
        String line = rawLine.startsWith("/") ? rawLine.substring(1) : rawLine;
        String root = rootOf(line);
        if (!gmCommands.contains(root)) return;
        String[] parts = line.trim().split("\\s+");
        String[] args = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
        long now = System.currentTimeMillis();
        recent.addLast(new Cmd(sender, root, args, line, now));
        while (!recent.isEmpty() && now - recent.peekFirst().at() > 3000) recent.pollFirst();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        track(e.getPlayer().getName(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        track(e.getSender().getName(), e.getCommand());
    }

    private String findExecutor(Player target) {
        long now = System.currentTimeMillis();
        var it = recent.descendingIterator();
        while (it.hasNext()) {
            Cmd c = it.next();
            if (now - c.at() > 3000) continue;
            boolean named = false, selector = false;
            for (String a : c.args()) {
                if (a.equalsIgnoreCase(target.getName())) named = true;
                if (a.startsWith("@")) selector = true;
            }
            if (named || selector || c.sender().equalsIgnoreCase(target.getName())) {
                return c.sender() + " (/" + c.line() + ")";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- game events

    private boolean isIgnored(Player p) {
        return p.hasPermission("gawharaeclog.bypass")
                || ignored.contains(p.getName().toLowerCase(Locale.ROOT))
                || ignored.contains(p.getUniqueId().toString().toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        GameMode old = p.getGameMode();
        GameMode now = e.getNewGameMode();
        boolean enter = now == GameMode.CREATIVE && old != GameMode.CREATIVE;
        boolean leave = old == GameMode.CREATIVE && now != GameMode.CREATIVE;
        if (!enter && !leave) return;
        if (enter && !logEnter) return;
        if (leave && !logLeave) return;
        if (isIgnored(p)) return;

        String cause = e.getCause().name();
        if (!causes.isEmpty() && !causes.contains(cause)) return;

        String executorInfo = "Unknown";
        if ("COMMAND".equals(cause)) {
            String ex = findExecutor(p);
            if (ex != null) executorInfo = ex;
        } else if ("PLUGIN".equals(cause)) {
            executorInfo = "A plugin / F3+F4";
        }

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Player", p.getName(), true));
        fields.add(new Field("Mode", old.name() + " -> " + now.name(), true));
        fields.add(new Field("Cause", cause, true));
        if (incExecutor) fields.add(new Field("Executed by", executorInfo, false));
        addLocation(fields, p);
        if (incUuid) fields.add(new Field("UUID", p.getUniqueId().toString(), false));

        send(payload(enter ? enterTitle : leaveTitle, enter ? enterColor : leaveColor,
                enter ? mention : "", p.getUniqueId().toString(), fields));

        if (notifyEnabled) {
            String text = notifyMessage
                    .replace("{player}", p.getName())
                    .replace("{old}", old.name())
                    .replace("{new}", now.name())
                    .replace("{cause}", cause)
                    .replace("{executor}", executorInfo);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("gawharaeclog.notify")) staff.sendMessage(LEGACY.deserialize(text));
            }
        }
        getLogger().info(p.getName() + " " + old + " -> " + now + " (cause: " + cause + ", by: " + executorInfo + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled || !logJoin) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Player", p.getName(), true));
        fields.add(new Field("Mode", "CREATIVE", true));
        addLocation(fields, p);
        if (incUuid) fields.add(new Field("UUID", p.getUniqueId().toString(), false));
        send(payload(joinTitle, joinColor, "", p.getUniqueId().toString(), fields));
    }

    private void addLocation(List<Field> fields, Player p) {
        if (!incLocation) return;
        Location l = p.getLocation();
        String w = l.getWorld() != null ? l.getWorld().getName() : "?";
        fields.add(new Field("World", w, true));
        fields.add(new Field("Location", l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(), true));
    }

    // ---------------------------------------------------------------- /gclog

    private void msg(CommandSender s, String text) {
        s.sendMessage(LEGACY.deserialize(prefix + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("gawharaeclog.admin")) {
            msg(sender, "&cYou don't have permission.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                msg(sender, "Config reloaded. Mode: &f" + (bot ? "bot" : "webhook")
                        + "&7, target configured: " + (configured() ? "&ayes" : "&cno"));
            }
            case "enable" -> {
                getConfig().set("enabled", true);
                saveConfig();
                enabled = true;
                msg(sender, "Logging &aENABLED&7.");
            }
            case "disable" -> {
                getConfig().set("enabled", false);
                saveConfig();
                enabled = false;
                msg(sender, "Logging &cDISABLED&7.");
            }
            case "status" -> {
                msg(sender, "Logging: " + (enabled ? "&aON" : "&cOFF"));
                msg(sender, "Mode: &f" + (bot ? "bot (channel " + (channelId.isBlank() ? "-" : channelId) + ")" : "webhook"));
                msg(sender, "Target configured: " + (configured() ? "&ayes" : "&cno"));
                msg(sender, "Sent OK: &f" + sentOk.get() + "&7  Failed: &f" + sentFail.get());
                msg(sender, "Last error: &f" + lastError);
            }
            case "test" -> {
                if (!configured()) {
                    msg(sender, "&cDiscord target is not configured in config.yml.");
                    return true;
                }
                List<Field> f = new ArrayList<>();
                f.add(new Field("Triggered by", sender.getName(), true));
                f.add(new Field("Result", "Test message", true));
                send(payload("GawharaeClog Test", 0x5865F2, "", null, f));
                msg(sender, "Test message queued. Check Discord (or /gclog status).");
            }
            default -> {
                msg(sender, "Commands:");
                msg(sender, "&f/gclog reload &7- reload config");
                msg(sender, "&f/gclog enable &7/ &f/gclog disable &7- turn logging on/off");
                msg(sender, "&f/gclog status &7- show state & counters");
                msg(sender, "&f/gclog test &7- send a test message");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("gawharaeclog.admin") || args.length != 1) return List.of();
        String p = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : SUBS) if (s.startsWith(p)) out.add(s);
        return out;
    }

    // ---------------------------------------------------------------- Discord

    private String payload(String title, int color, String content, String uuid, List<Field> fields) {
        StringBuilder sb = new StringBuilder("{");
        if (!bot) {
            sb.append("\"username\":\"").append(esc(username)).append("\",");
            if (!avatarUrl.isBlank()) sb.append("\"avatar_url\":\"").append(esc(avatarUrl)).append("\",");
        }
        if (content != null && !content.isBlank()) {
            sb.append("\"content\":\"").append(esc(content)).append("\",");
            sb.append("\"allowed_mentions\":{\"parse\":[\"roles\",\"users\",\"everyone\"]},");
        }
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"").append(esc(title)).append("\",");
        sb.append("\"color\":").append(color).append(",");
        if (uuid != null) {
            sb.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(esc(uuid)).append("\"},");
        }
        sb.append("\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (i > 0) sb.append(",");
            String v = (f.value() == null || f.value().isBlank()) ? "-" : f.value();
            if (v.length() > 1000) v = v.substring(0, 1000);
            sb.append("{\"name\":\"").append(esc(f.name())).append("\",\"value\":\"")
                    .append(esc(v)).append("\",\"inline\":").append(f.inline()).append("}");
        }
        sb.append("],");
        sb.append("\"footer\":{\"text\":\"").append(esc(serverName)).append("\"},");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\"");
        sb.append("}]}");
        return sb.toString();
    }

    private void send(String json) {
        if (!configured()) {
            lastError = "Discord target not configured";
            return;
        }
        final boolean useBot = bot;
        final String url = useBot
                ? "https://discord.com/api/v10/channels/" + channelId + "/messages"
                : webhookUrl;
        final String token = botToken;
        executor.submit(() -> {
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json));
                    if (useBot) {
                        rb.header("Authorization", "Bot " + token);
                        rb.header("User-Agent", "GawharaeClog (gawharasmp, 1.1)");
                    }
                    HttpResponse<String> res = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                    int code = res.statusCode();
                    if (code >= 200 && code < 300) {
                        sentOk.incrementAndGet();
                        return;
                    }
                    if (code == 429) {
                        Matcher m = RETRY_AFTER.matcher(res.body());
                        double wait = m.find() ? Double.parseDouble(m.group(1)) : 1.5;
                        Thread.sleep((long) (wait * 1000) + 250);
                        continue;
                    }
                    sentFail.incrementAndGet();
                    lastError = "HTTP " + code + " " + trim(res.body());
                    getLogger().warning("Discord returned " + lastError);
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    sentFail.incrementAndGet();
                    lastError = String.valueOf(ex.getMessage());
                    getLogger().warning("Failed to send to Discord: " + lastError);
                    return;
                }
            }
            sentFail.incrementAndGet();
            lastError = "Rate limited too many times";
        });
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 150 ? s.substring(0, 150) : s;
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
