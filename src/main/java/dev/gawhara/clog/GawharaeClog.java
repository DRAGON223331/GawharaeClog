package dev.gawhara.clog;

import dev.gawhara.clog.Discord.Embed;
import dev.gawhara.clog.Discord.Field;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class GawharaeClog extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final List<String> SUBS = List.of("reload", "enable", "disable", "status", "test", "list", "help");

    private record Cmd(String sender, String root, String[] args, String line, long at) {}

    private Discord discord;
    private BukkitTask flushTask;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ConcurrentLinkedDeque<Cmd> recent = new ConcurrentLinkedDeque<>();
    private final Map<String, Long> dangerThrottle = new HashMap<>();

    // ---- الإعدادات
    private boolean enabled;
    private String mention, dangerMention, prefix;
    private boolean logEnter, logLeave, logJoin, logQuit;
    private boolean logItems, logMenuDrop, logDrop, logPickup, logWatched, logDangerBlock, logBlocksInSummary;
    private boolean incLocation, incUuid, incExecutor, incIp, incExtended;
    private boolean notifyEnabled;
    private int batchSeconds;
    private String enterTitle, leaveTitle, joinTitle, quitTitle, itemsTitle, dropTitle, pickupTitle, menuDropTitle, cmdTitle;
    private int enterColor, leaveColor, joinColor, itemsColor, dangerColor;
    private String notifyMessage;
    private Set<String> causes = new HashSet<>();
    private Set<String> ignored = new HashSet<>();
    private Set<String> gmCommands = new HashSet<>();
    private Set<String> watchedCommands = new HashSet<>();
    private Set<String> dangerous = new HashSet<>();
    private Map<String, String> itemNamesAr = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        discord = new Discord(getLogger());
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("gclog");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE) sessions.put(p.getUniqueId(), new Session());
        }
        if (!discord.configured()) {
            getLogger().warning("وجهة ديسكورد لسه مش متظبطة. عدّل config.yml وبعدين /gclog reload");
        }
        getLogger().info("GawharaeClog اشتغل (الوضع: " + (discord.bot ? "بوت" : "ويب هوك") + "، التسجيل: " + enabled + ")");
    }

    @Override
    public void onDisable() {
        if (flushTask != null) flushTask.cancel();
        if (discord == null) return;
        if (enabled) {
            for (Map.Entry<UUID, Session> en : new ArrayList<>(sessions.entrySet())) {
                Player p = Bukkit.getPlayer(en.getKey());
                if (p == null) continue;
                flushSession(p, en.getValue());
                if (logQuit) sendSummary(p, "🔌 السيرفر اتقفل واللاعب في الكريتف", leaveColor, en.getValue());
            }
        }
        sessions.clear();
        discord.shutdown();
    }

    // ================================================================ الإعدادات

    private void loadSettings() {
        var c = getConfig();
        enabled = c.getBoolean("enabled", true);

        discord.bot = "bot".equalsIgnoreCase(c.getString("mode", "webhook"));
        discord.webhookUrl = c.getString("webhook-url", "").trim();
        discord.botToken = c.getString("bot-token", "").trim();
        discord.channelId = c.getString("channel-id", "").trim();
        discord.username = c.getString("username", "Gawhara Clog");
        discord.avatarUrl = c.getString("avatar-url", "").trim();
        discord.serverName = c.getString("server-name", "Gawharasmp");

        mention = c.getString("mention", "").trim();
        dangerMention = c.getString("dangerous.mention", "").trim();
        prefix = c.getString("prefix", "&8[&cGawharaeClog&8] &7");

        logEnter = c.getBoolean("log.enter-creative", true);
        logLeave = c.getBoolean("log.leave-creative", true);
        logJoin = c.getBoolean("log.join-in-creative", true);
        logQuit = c.getBoolean("log.quit-in-creative", true);
        logItems = c.getBoolean("log.items-taken", true);
        logMenuDrop = c.getBoolean("log.items-dropped-from-menu", true);
        logDrop = c.getBoolean("log.items-dropped", true);
        logPickup = c.getBoolean("log.items-picked-up", false);
        logWatched = c.getBoolean("log.watched-commands", true);
        logDangerBlock = c.getBoolean("log.dangerous-block-place", true);
        logBlocksInSummary = c.getBoolean("log.blocks-in-summary", true);

        incLocation = c.getBoolean("embed.include-location", true);
        incUuid = c.getBoolean("embed.include-uuid", true);
        incExecutor = c.getBoolean("embed.include-executor", true);
        incIp = c.getBoolean("embed.include-ip", false);
        incExtended = c.getBoolean("embed.extended-info", true);

        enterTitle = c.getString("titles.enter", "🟥 لاعب دخل وضع الكريتف");
        leaveTitle = c.getString("titles.leave", "🟩 لاعب خرج من وضع الكريتف");
        joinTitle = c.getString("titles.join", "🟧 لاعب دخل السيرفر وهو في الكريتف");
        quitTitle = c.getString("titles.quit", "🚪 لاعب خرج من السيرفر وهو في الكريتف");
        itemsTitle = c.getString("titles.items-taken", "📦 أغراض اتسحبت من قايمة الكريتف");
        menuDropTitle = c.getString("titles.items-menu-dropped", "🗑️ أغراض اترمت من قايمة الكريتف");
        dropTitle = c.getString("titles.items-dropped", "📤 أغراض اترمت على الأرض (كريتف)");
        pickupTitle = c.getString("titles.items-picked-up", "📥 أغراض اتلقطت من الأرض (كريتف)");
        cmdTitle = c.getString("titles.watched-command", "⌨️ أمر مراقَب اتنفّذ");
        enterColor = color(c.getString("colors.enter"), 0xFF5555);
        leaveColor = color(c.getString("colors.leave"), 0x55FF55);
        joinColor = color(c.getString("colors.join"), 0xFFAA00);
        itemsColor = color(c.getString("colors.items"), 0x5865F2);
        dangerColor = color(c.getString("colors.danger"), 0xFF0000);

        batchSeconds = Math.max(2, c.getInt("batch-seconds", 5));
        notifyEnabled = c.getBoolean("ingame-notify.enabled", true);
        notifyMessage = c.getString("ingame-notify.message", "&c[Clog] &f{player} &7{old} ← &c{new}");

        causes = upper(c.getStringList("causes"));
        ignored = lower(c.getStringList("ignored-players"));
        gmCommands = lower(c.getStringList("gamemode-commands"));
        watchedCommands = lower(c.getStringList("watched-commands"));
        dangerous = upper(c.getStringList("dangerous.items"));

        itemNamesAr = new HashMap<>();
        ConfigurationSection sec = c.getConfigurationSection("item-names-ar");
        if (sec != null) {
            for (String k : sec.getKeys(false)) itemNamesAr.put(k.toUpperCase(Locale.ROOT), sec.getString(k, ""));
        }

        if (flushTask != null) flushTask.cancel();
        long ticks = batchSeconds * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(this, this::flushAll, ticks, ticks);
    }

    private static Set<String> upper(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(x.trim().toUpperCase(Locale.ROOT));
        return s;
    }

    private static Set<String> lower(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(x.trim().toLowerCase(Locale.ROOT));
        return s;
    }

    private static int color(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.replace("#", "").trim(), 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ================================================================ مساعدات عربي

    private static String modeAr(GameMode m) {
        return switch (m) {
            case SURVIVAL -> "سرفايفل (SURVIVAL)";
            case CREATIVE -> "كريتف (CREATIVE)";
            case ADVENTURE -> "أدفنشر (ADVENTURE)";
            case SPECTATOR -> "مشاهدة (SPECTATOR)";
        };
    }

    private static String causeAr(String cause) {
        return switch (cause) {
            case "COMMAND" -> "أمر (COMMAND)";
            case "PLUGIN" -> "بلجن أو F3+F4 (PLUGIN)";
            case "DEFAULT_GAMEMODE" -> "وضع السيرفر الافتراضي (DEFAULT_GAMEMODE)";
            case "HARDCORE_DEATH" -> "موت هاردكور (HARDCORE_DEATH)";
            default -> "غير معروف (" + cause + ")";
        };
    }

    private static String dirAr(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        int i = (int) ((y + 22.5f) / 45f) % 8;
        return new String[]{"جنوب", "جنوب غرب", "غرب", "شمال غرب", "شمال", "شمال شرق", "شرق", "جنوب شرق"}[i];
    }

    private static String worldAr(World w) {
        String env = switch (w.getEnvironment()) {
            case NORMAL -> "العالم العادي";
            case NETHER -> "النذر";
            case THE_END -> "الإند";
            default -> "عالم مخصص";
        };
        return "`" + w.getName() + "` (" + env + ")";
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" ساعة ");
        if (m > 0) sb.append(m).append(" دقيقة ");
        sb.append(sec).append(" ثانية");
        return sb.toString();
    }

    private static String stamp() {
        long t = System.currentTimeMillis() / 1000;
        return "<t:" + t + ":F>\n(<t:" + t + ":R>)";
    }

    private static String senderName(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return "الكونسول";
        if (s instanceof BlockCommandSender) return "كوماند بلوك";
        return s.getName();
    }

    private boolean isIgnored(Player p) {
        return p.hasPermission("gawharaeclog.bypass")
                || ignored.contains(p.getName().toLowerCase(Locale.ROOT))
                || ignored.contains(p.getUniqueId().toString().toLowerCase(Locale.ROOT));
    }

    private Session session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session());
    }

    // ================================================================ حقول اللاعب

    private List<Field> compactFields(Player p) {
        List<Field> f = new ArrayList<>();
        f.add(new Field("👤 اللاعب", "**" + p.getName() + "**", true));
        Location l = p.getLocation();
        if (l.getWorld() != null) f.add(new Field("🌍 العالم", worldAr(l.getWorld()), true));
        if (incLocation) f.add(new Field("📍 الإحداثيات", "`X: " + l.getBlockX() + " | Y: " + l.getBlockY() + " | Z: " + l.getBlockZ() + "`", true));
        return f;
    }

    private List<Field> playerFields(Player p) {
        List<Field> f = new ArrayList<>();
        Location l = p.getLocation();
        f.add(new Field("👤 اللاعب", "**" + p.getName() + "**" + (p.isOp() ? "\n👑 أوب (OP)" : ""), true));
        if (l.getWorld() != null) f.add(new Field("🌍 العالم", worldAr(l.getWorld()), true));
        if (incLocation) {
            f.add(new Field("📍 الإحداثيات", "`X: " + l.getBlockX() + "`\n`Y: " + l.getBlockY() + "`\n`Z: " + l.getBlockZ() + "`", true));
        }
        if (incExtended) {
            f.add(new Field("🧭 الاتجاه", dirAr(l.getYaw()), true));
            try {
                f.add(new Field("🌿 البيئة (Biome)", "`" + ItemInfo.keyOf(l.getBlock().getBiome()) + "`", true));
            } catch (Exception ignoredEx) {
                // مش مهم
            }
            f.add(new Field("❤️ الصحة", String.format(Locale.ROOT, "%.1f", p.getHealth()), true));
            f.add(new Field("🍗 الجوع", String.valueOf(p.getFoodLevel()), true));
            f.add(new Field("⭐ مستوى الخبرة", String.valueOf(p.getLevel()), true));
            f.add(new Field("📶 البنج", p.getPing() + " ms", true));
            String brand = null;
            try {
                brand = p.getClientBrandName();
            } catch (Exception ignoredEx) {
                // مش مهم
            }
            f.add(new Field("🖥️ الكلاينت", brand == null ? "غير معروف" : "`" + brand + "`", true));
            f.add(new Field("👥 اللاعبين أونلاين", String.valueOf(Bukkit.getOnlinePlayers().size()), true));
            f.add(new Field("🖐️ في اليد", ItemInfo.brief(p.getInventory().getItemInMainHand(), itemNamesAr), true));
            f.add(new Field("🤚 اليد التانية", ItemInfo.brief(p.getInventory().getItemInOffHand(), itemNamesAr), true));
        }
        if (incIp && p.getAddress() != null) {
            f.add(new Field("🌐 عنوان IP", "||" + p.getAddress().getAddress().getHostAddress() + "||", true));
        }
        if (incUuid) f.add(new Field("🆔 UUID", "`" + p.getUniqueId() + "`", false));
        f.add(new Field("🕒 الوقت", stamp(), false));
        return f;
    }

    // ================================================================ تتبّع أوامر الجيم مود

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
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
        long now = System.currentTimeMillis();
        recent.addLast(new Cmd(sender, root, args, line, now));
        while (!recent.isEmpty() && now - recent.peekFirst().at() > 3000) recent.pollFirst();
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
                return c.sender() + " (`/" + c.line() + "`)";
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        track(p.getName(), e.getMessage());
        watch(p, p.getName(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        if (!enabled) return;
        String name = senderName(e.getSender());
        track(name, e.getCommand());
        watch(null, name, e.getCommand());
    }

    /** تسجيل الأوامر المراقَبة (give / summon / ...). */
    private void watch(Player p, String senderLabel, String rawLine) {
        String line = rawLine.startsWith("/") ? rawLine.substring(1) : rawLine;
        if (!watchedCommands.contains(rootOf(line))) return;
        if (p != null && isIgnored(p)) return;

        if (p != null) {
            Session s = sessions.get(p.getUniqueId());
            if (s != null && s.commands.size() < 20) s.commands.add("/" + Discord.cut(line, 80));
        }
        if (!logWatched || !discord.configured()) return;

        List<Field> f = new ArrayList<>();
        f.add(new Field("👤 المنفّذ", "**" + senderLabel + "**" + (p == null ? "" : (p.isOp() ? "\n👑 أوب (OP)" : "")), true));
        if (p != null) {
            f.add(new Field("🎮 وضع اللعب", modeAr(p.getGameMode()), true));
            Location l = p.getLocation();
            if (l.getWorld() != null) f.add(new Field("🌍 العالم", worldAr(l.getWorld()), true));
            if (incLocation) f.add(new Field("📍 الإحداثيات", "`X: " + l.getBlockX() + " | Y: " + l.getBlockY() + " | Z: " + l.getBlockZ() + "`", true));
        }
        f.add(new Field("⌨️ الأمر", "```\n/" + Discord.cut(line, 900) + "\n```", false));
        f.add(new Field("🕒 الوقت", stamp(), false));
        discord.send("", new Embed(cmdTitle, itemsColor, null, f, p == null ? null : p.getUniqueId().toString()));
    }

    // ================================================================ تغيير وضع اللعب

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        GameMode old = p.getGameMode();
        GameMode now = e.getNewGameMode();
        boolean enter = now == GameMode.CREATIVE && old != GameMode.CREATIVE;
        boolean leave = old == GameMode.CREATIVE && now != GameMode.CREATIVE;
        if (!enter && !leave) return;

        UUID id = p.getUniqueId();
        if (isIgnored(p)) {
            sessions.remove(id);
            return;
        }

        String cause = e.getCause().name();
        String executorInfo = "غير معروف";
        if ("COMMAND".equals(cause)) {
            String ex = findExecutor(p);
            if (ex != null) executorInfo = ex;
        } else if ("PLUGIN".equals(cause)) {
            executorInfo = "بلجن أو F3+F4";
        } else if ("DEFAULT_GAMEMODE".equals(cause)) {
            executorInfo = "إعداد السيرفر";
        }

        if (enter) {
            sessions.put(id, new Session());
        }
        Session ended = null;
        if (leave) {
            ended = sessions.remove(id);
            if (ended != null) flushSession(p, ended);
        }

        boolean passes = causes.isEmpty() || causes.contains(cause);
        if (passes && ((enter && logEnter) || (leave && logLeave))) {
            List<Field> fields = new ArrayList<>();
            fields.add(new Field("🔄 تغيير الوضع", modeAr(old) + "\n⬇️\n**" + modeAr(now) + "**", false));
            fields.add(new Field("⚙️ السبب", causeAr(cause), true));
            if (incExecutor) {
                String who = executorInfo;
                if (who.toLowerCase(Locale.ROOT).startsWith(p.getName().toLowerCase(Locale.ROOT) + " ")) {
                    who = who + " — حوّل نفسه";
                }
                fields.add(new Field("🧑‍💻 اتنفّذ بواسطة", who, true));
            }
            fields.addAll(playerFields(p));

            String desc = null;
            if (ended != null) {
                fields.addAll(summaryFields(ended));
                desc = topItemsDescription(ended);
            }
            discord.send(enter ? mention : "",
                    new Embed(enter ? enterTitle : leaveTitle, enter ? enterColor : leaveColor, desc, fields, id.toString()));
        }

        if (enter && logEnter && passes && notifyEnabled) {
            String text = notifyMessage
                    .replace("{player}", p.getName())
                    .replace("{old}", modeAr(old))
                    .replace("{new}", modeAr(now))
                    .replace("{cause}", causeAr(cause))
                    .replace("{executor}", executorInfo.replace("`", ""));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("gawharaeclog.notify")) staff.sendMessage(LEGACY.deserialize(text));
            }
        }
        getLogger().info(p.getName() + " " + old + " -> " + now + " (السبب: " + cause + "، بواسطة: " + executorInfo.replace("`", "") + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        sessions.put(p.getUniqueId(), new Session());
        if (!logJoin) return;
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("🎮 وضع اللعب", "**" + modeAr(GameMode.CREATIVE) + "**", false));
        fields.addAll(playerFields(p));
        discord.send("", new Embed(joinTitle, joinColor, null, fields, p.getUniqueId().toString()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.remove(p.getUniqueId());
        if (!enabled || s == null) return;
        flushSession(p, s);
        if (logQuit) sendSummary(p, quitTitle, leaveColor, s);
    }

    // ================================================================ أغراض الكريتف

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent e) {
        if (!enabled || !(e.getWhoClicked() instanceof Player p) || isIgnored(p)) return;
        Session s = session(p);
        long now = System.currentTimeMillis();
        s.prune(now);

        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        boolean hasCursor = cursor != null && !cursor.getType().isAir();
        boolean hasCurrent = current != null && !current.getType().isAir();

        if (hasCursor) {
            // اترمى من القايمة على الأرض
            if (e.getSlotType() == InventoryType.SlotType.OUTSIDE) {
                if (logMenuDrop) {
                    Session.add(s.menuDropped, cursor, cursor.getAmount());
                    Session.add(s.pendingMenuDropped, cursor, cursor.getAmount());
                }
                return;
            }
            // نقل غرض جوه الشنطة (اتشال من مكان قبل كده) مش سحب جديد
            if (Session.consume(s.removedRecent, cursor)) return;

            int add = cursor.getAmount();
            if (hasCurrent && current.isSimilar(cursor)) {
                add = cursor.getAmount() - current.getAmount();
                if (add <= 0) return;
            }
            s.placedRecent.addLast(new Session.Recent(cursor.clone(), now));
            if (logItems) {
                Session.add(s.taken, cursor, add);
                Session.add(s.pendingTaken, cursor, add);
            }
        } else if (hasCurrent) {
            if (Session.consume(s.placedRecent, current)) {
                Session.sub(s.taken, current, current.getAmount());
                Session.sub(s.pendingTaken, current, current.getAmount());
            } else {
                s.removedRecent.addLast(new Session.Recent(current.clone(), now));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!enabled || !logDrop || p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        ItemStack it = e.getItemDrop().getItemStack();
        Session s = session(p);
        Session.add(s.dropped, it, it.getAmount());
        Session.add(s.pendingDropped, it, it.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!enabled || !logPickup || !(e.getEntity() instanceof Player p)) return;
        if (p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        ItemStack it = e.getItem().getItemStack();
        Session s = session(p);
        Session.add(s.pickedUp, it, it.getAmount());
        Session.add(s.pendingPickedUp, it, it.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!enabled || p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        Material m = e.getBlockPlaced().getType();
        session(p).placed.merge(m, 1, Integer::sum);

        if (logDangerBlock && dangerous.contains(m.name()) && discord.configured()) {
            long now = System.currentTimeMillis();
            String key = p.getUniqueId() + ":" + m.name();
            Long last = dangerThrottle.get(key);
            if (last != null && now - last < 5000) return;
            dangerThrottle.put(key, now);
            if (dangerThrottle.size() > 500) dangerThrottle.clear();

            Location l = e.getBlockPlaced().getLocation();
            List<Field> f = new ArrayList<>();
            f.add(new Field("🧱 البلوك", ItemInfo.name(m, itemNamesAr) + " `" + m.name() + "`", true));
            f.add(new Field("📍 مكان الوضع", "`X: " + l.getBlockX() + " | Y: " + l.getBlockY() + " | Z: " + l.getBlockZ() + "`", true));
            f.addAll(compactFields(p));
            f.add(new Field("🕒 الوقت", stamp(), false));
            discord.send(dangerMention, new Embed("⚠️ لاعب كريتف حط بلوك خطير", dangerColor, null, f, p.getUniqueId().toString()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!enabled || p.getGameMode() != GameMode.CREATIVE || isIgnored(p)) return;
        session(p).broken++;
    }

    // ================================================================ الإرسال المجمّع

    private void flushAll() {
        if (!enabled) return;
        for (Map.Entry<UUID, Session> en : new ArrayList<>(sessions.entrySet())) {
            Session s = en.getValue();
            if (!s.hasPending()) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                clearPending(s);
                continue;
            }
            flushSession(p, s);
        }
    }

    private static void clearPending(Session s) {
        s.pendingTaken.clear();
        s.pendingMenuDropped.clear();
        s.pendingDropped.clear();
        s.pendingPickedUp.clear();
    }

    private void flushSession(Player p, Session s) {
        if (!s.hasPending()) return;
        if (!discord.configured()) {
            clearPending(s);
            return;
        }
        List<Embed> embeds = new ArrayList<>();
        boolean danger = false;
        danger |= itemEmbeds(embeds, itemsTitle, s.pendingTaken, p);
        danger |= itemEmbeds(embeds, menuDropTitle, s.pendingMenuDropped, p);
        danger |= itemEmbeds(embeds, dropTitle, s.pendingDropped, p);
        danger |= itemEmbeds(embeds, pickupTitle, s.pendingPickedUp, p);
        clearPending(s);
        if (embeds.isEmpty()) return;
        discord.send(danger ? dangerMention : "", embeds);
    }

    /** بيحوّل قايمة أغراض لإيمبد (أو أكتر لو طويلة). بيرجّع true لو فيها حاجة خطيرة. */
    private boolean itemEmbeds(List<Embed> out, String title, List<Session.Tally> list, Player p) {
        if (list.isEmpty()) return false;
        List<Session.Tally> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(b.amount, a.amount));

        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean danger = false;
        boolean truncated = false;
        for (Session.Tally t : sorted) {
            String line = ItemInfo.line(t.proto, t.amount, itemNamesAr, dangerous);
            if (line.contains("🚨")) danger = true;
            if (cur.length() + line.length() + 1 > 3500) {
                chunks.add(cur.toString());
                cur = new StringBuilder();
                if (chunks.size() >= 3) {
                    truncated = true;
                    break;
                }
            }
            cur.append(line).append("\n");
        }
        if (cur.length() > 0 && chunks.size() < 3) chunks.add(cur.toString());
        if (truncated) {
            chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n… وفيه أغراض تانية مش معروضة (الكمية كبيرة)");
        }

        int color = danger ? dangerColor : itemsColor;
        for (int i = 0; i < chunks.size(); i++) {
            if (i == 0) {
                List<Field> f = compactFields(p);
                f.add(new Field("🔢 إجمالي القطع", String.valueOf(Session.total(list)), true));
                f.add(new Field("🗂️ عدد الأنواع", String.valueOf(list.size()), true));
                f.add(new Field("🎮 وضع اللعب", modeAr(p.getGameMode()), true));
                f.add(new Field("🕒 الوقت", stamp(), false));
                out.add(new Embed(danger ? "🚨 " + title : title, color, chunks.get(i), f, p.getUniqueId().toString()));
            } else {
                out.add(new Embed(title + " (تكملة " + (i + 1) + ")", color, chunks.get(i), List.of(), null));
            }
        }
        return danger;
    }

    // ================================================================ ملخّص الجلسة

    private List<Field> summaryFields(Session s) {
        List<Field> f = new ArrayList<>();
        f.add(new Field("⏱️ مدة البقاء في الكريتف", duration(System.currentTimeMillis() - s.start), false));
        f.add(new Field("📦 أغراض سحبها", Session.total(s.taken) + " قطعة (" + s.taken.size() + " نوع)", true));
        f.add(new Field("🗑️ رماها من القايمة", Session.total(s.menuDropped) + " قطعة", true));
        f.add(new Field("📤 رماها على الأرض", Session.total(s.dropped) + " قطعة", true));
        if (logPickup) f.add(new Field("📥 لقطها من الأرض", Session.total(s.pickedUp) + " قطعة", true));
        if (logBlocksInSummary) {
            int placedTotal = 0;
            for (int n : s.placed.values()) placedTotal += n;
            f.add(new Field("🧱 بلوكات حطها", String.valueOf(placedTotal), true));
            f.add(new Field("⛏️ بلوكات كسرها", String.valueOf(s.broken), true));
            if (!s.placed.isEmpty()) {
                List<Map.Entry<Material, Integer>> top = new ArrayList<>(s.placed.entrySet());
                top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(6, top.size()); i++) {
                    sb.append("• ").append(ItemInfo.name(top.get(i).getKey(), itemNamesAr)).append(" ×").append(top.get(i).getValue()).append("\n");
                }
                f.add(new Field("🏗️ أكتر بلوكات اتحطت", sb.toString(), false));
            }
        }
        if (!s.commands.isEmpty()) {
            f.add(new Field("⌨️ أوامر استخدمها (" + s.commands.size() + ")",
                    "```\n" + String.join("\n", s.commands.subList(0, Math.min(8, s.commands.size()))) + "\n```", false));
        }
        return f;
    }

    private String topItemsDescription(Session s) {
        if (s.taken.isEmpty()) return null;
        List<Session.Tally> sorted = new ArrayList<>(s.taken);
        sorted.sort((a, b) -> Integer.compare(b.amount, a.amount));
        StringBuilder sb = new StringBuilder("**📦 أهم الأغراض اللي سحبها في الجلسة دي:**\n");
        int n = 0;
        for (Session.Tally t : sorted) {
            String line = ItemInfo.line(t.proto, t.amount, itemNamesAr, dangerous);
            if (sb.length() + line.length() > 3300 || n >= 12) {
                sb.append("… والباقي (").append(sorted.size() - n).append(" نوع تاني)");
                break;
            }
            sb.append(line).append("\n");
            n++;
        }
        return sb.toString();
    }

    private void sendSummary(Player p, String title, int color, Session s) {
        if (!discord.configured()) return;
        List<Field> f = new ArrayList<>(playerFields(p));
        f.addAll(summaryFields(s));
        discord.send("", new Embed(title, color, topItemsDescription(s), f, p.getUniqueId().toString()));
    }

    // ================================================================ /gclog

    private void msg(CommandSender s, String text) {
        s.sendMessage(LEGACY.deserialize(prefix + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("gawharaeclog.admin")) {
            msg(sender, "&cمعندكش صلاحية.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                msg(sender, "اتعمل ريلود للإعدادات. الوضع: &f" + (discord.bot ? "بوت" : "ويب هوك")
                        + "&7، الوجهة متظبطة: " + (discord.configured() ? "&aأيوه" : "&cلأ"));
            }
            case "enable" -> {
                getConfig().set("enabled", true);
                saveConfig();
                enabled = true;
                msg(sender, "التسجيل &aاشتغل&7.");
            }
            case "disable" -> {
                getConfig().set("enabled", false);
                saveConfig();
                enabled = false;
                msg(sender, "التسجيل &cوقف&7.");
            }
            case "status" -> {
                msg(sender, "التسجيل: " + (enabled ? "&aشغال" : "&cموقوف"));
                msg(sender, "الوضع: &f" + (discord.bot ? "بوت (روم " + (discord.channelId.isBlank() ? "-" : discord.channelId) + ")" : "ويب هوك"));
                msg(sender, "الوجهة متظبطة: " + (discord.configured() ? "&aأيوه" : "&cلأ"));
                msg(sender, "اتبعت بنجاح: &f" + discord.ok.get() + "&7  فشل: &f" + discord.fail.get());
                msg(sender, "آخر خطأ: &f" + discord.lastError);
                msg(sender, "لاعبين في كريتف دلوقتي: &f" + sessions.size());
            }
            case "test" -> {
                if (!discord.configured()) {
                    msg(sender, "&cوجهة ديسكورد مش متظبطة في config.yml.");
                    return true;
                }
                List<Field> f = new ArrayList<>();
                f.add(new Field("👤 اتجرّب بواسطة", sender.getName(), true));
                f.add(new Field("✅ النتيجة", "رسالة تجريبية", true));
                f.add(new Field("🕒 الوقت", stamp(), false));
                discord.send("", new Embed("🧪 اختبار GawharaeClog", 0x5865F2, "لو شايف الرسالة دي يبقى الربط شغال تمام.", f, null));
                msg(sender, "رسالة الاختبار اتبعتت. بص على ديسكورد (أو /gclog status).");
            }
            case "list" -> {
                if (sessions.isEmpty()) {
                    msg(sender, "مفيش حد في الكريتف دلوقتي.");
                    return true;
                }
                msg(sender, "اللاعبين في الكريتف دلوقتي:");
                for (Map.Entry<UUID, Session> en : sessions.entrySet()) {
                    Player p = Bukkit.getPlayer(en.getKey());
                    if (p == null) continue;
                    Session s = en.getValue();
                    msg(sender, "&f" + p.getName() + " &7- مدة: &f" + duration(System.currentTimeMillis() - s.start)
                            + " &7- سحب: &f" + Session.total(s.taken) + " &7قطعة");
                }
            }
            default -> {
                msg(sender, "الأوامر:");
                msg(sender, "&f/gclog reload &7- ريلود للإعدادات");
                msg(sender, "&f/gclog enable &7/ &f/gclog disable &7- تشغيل / إيقاف التسجيل");
                msg(sender, "&f/gclog status &7- الحالة والعدادات");
                msg(sender, "&f/gclog list &7- مين في الكريتف دلوقتي");
                msg(sender, "&f/gclog test &7- رسالة اختبار");
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
}
