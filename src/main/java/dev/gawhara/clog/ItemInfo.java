package dev.gawhara.clog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** وصف مفصّل للغرض بالعربي. */
final class ItemInfo {

    private static final Pattern KEY = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)");

    private ItemInfo() {}

    /** بيطلّع الاسم (مثلاً sharpness) من أي كائن بيرجّع minecraft:sharpness في toString. */
    static String keyOf(Object o) {
        String s = String.valueOf(o);
        Matcher m = KEY.matcher(s);
        if (m.find()) {
            String k = m.group(1);
            int i = k.indexOf(':');
            return i >= 0 ? k.substring(i + 1) : k;
        }
        return s;
    }

    static String pretty(String id) {
        String[] parts = id.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    static String name(Material m, Map<String, String> ar) {
        String a = ar.get(m.name());
        return a != null ? a : pretty(m.name());
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** سطر واحد بيوصف الغرض وعدده. */
    static String line(ItemStack it, int amount, Map<String, String> ar, Set<String> dangerous) {
        StringBuilder sb = new StringBuilder();
        boolean danger = dangerous.contains(it.getType().name());
        List<String> d = new ArrayList<>();
        boolean illegal = details(it, d, ar);
        sb.append(danger ? "⚠️ " : "• ");
        sb.append("**×").append(amount).append("** ").append(name(it.getType(), ar));
        sb.append(" `").append(it.getType().name()).append("`");
        if (danger) sb.append(" 🚨 **غرض خطير**");
        if (illegal) sb.append(" 🚨 **تطعيم فوق الحد المسموح**");
        for (String x : d) sb.append("\n   ↳ ").append(x);
        return sb.toString();
    }

    /** بيرجّع true لو فيه تطعيم أعلى من الحد الطبيعي. */
    private static boolean details(ItemStack it, List<String> out, Map<String, String> ar) {
        boolean illegal = false;
        if (!it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;

        if (meta.hasDisplayName() && meta.displayName() != null) {
            out.add("📛 اسم مخصص: «" + plain(meta.displayName()) + "»");
        }

        if (meta.hasEnchants()) {
            List<String> es = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> en : meta.getEnchants().entrySet()) {
                boolean over = en.getValue() > en.getKey().getMaxLevel();
                if (over) illegal = true;
                es.add(keyOf(en.getKey()) + " " + en.getValue() + (over ? " ⚠️" : ""));
            }
            out.add("✨ تطعيمات: " + String.join("، ", es));
        }

        if (meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchants()) {
            List<String> es = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> en : esm.getStoredEnchants().entrySet()) {
                boolean over = en.getValue() > en.getKey().getMaxLevel();
                if (over) illegal = true;
                es.add(keyOf(en.getKey()) + " " + en.getValue() + (over ? " ⚠️" : ""));
            }
            out.add("📖 تطعيمات مخزّنة (كتاب): " + String.join("، ", es));
        }

        if (meta instanceof PotionMeta pm) {
            if (pm.getBasePotionType() != null) {
                out.add("🧪 نوع البوشن: " + keyOf(pm.getBasePotionType()));
            }
            if (pm.hasCustomEffects()) {
                List<String> es = new ArrayList<>();
                for (PotionEffect pe : pm.getCustomEffects()) {
                    es.add(keyOf(pe.getType()) + " " + (pe.getAmplifier() + 1) + " (" + (pe.getDuration() / 20) + "ث)");
                }
                out.add("🧪 تأثيرات مخصصة: " + String.join("، ", es));
            }
        }

        List<Component> lore = meta.lore();
        if (lore != null && !lore.isEmpty()) {
            String first = plain(lore.get(0));
            out.add("📜 وصف (" + lore.size() + " سطر): " + Discord.cut(first, 60));
        }

        if (meta.isUnbreakable()) {
            out.add("♾️ غير قابل للكسر");
        }

        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container c) {
            int stacks = 0;
            int items = 0;
            List<String> top = new ArrayList<>();
            for (ItemStack in : c.getInventory().getContents()) {
                if (in == null || in.getType().isAir()) continue;
                stacks++;
                items += in.getAmount();
                if (top.size() < 4) top.add(name(in.getType(), ar) + " ×" + in.getAmount());
            }
            if (stacks > 0) {
                out.add("📦 جواه " + stacks + " نوع (" + items + " قطعة): " + String.join("، ", top) + (stacks > 4 ? "..." : ""));
            } else {
                out.add("📦 فاضي");
            }
        }

        if (meta instanceof BundleMeta bm && bm.hasItems()) {
            int items = 0;
            List<String> top = new ArrayList<>();
            for (ItemStack in : bm.getItems()) {
                items += in.getAmount();
                if (top.size() < 4) top.add(name(in.getType(), ar) + " ×" + in.getAmount());
            }
            out.add("🎒 باندل جواه " + items + " قطعة: " + String.join("، ", top));
        }
        return illegal;
    }

    /** وصف قصير للغرض اللي في اليد. */
    static String brief(ItemStack it, Map<String, String> ar) {
        if (it == null || it.getType().isAir()) return "فاضية";
        return name(it.getType(), ar) + " ×" + it.getAmount();
    }
}
