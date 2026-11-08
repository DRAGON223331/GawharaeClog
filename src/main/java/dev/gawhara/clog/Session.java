package dev.gawhara.clog;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** جلسة كريتف للاعب: من أول ما دخل Creative لحد ما خرج. */
final class Session {

    static final class Tally {
        final ItemStack proto;
        int amount;

        Tally(ItemStack item, int amount) {
            this.proto = item.clone();
            this.proto.setAmount(1);
            this.amount = amount;
        }
    }

    record Recent(ItemStack item, long at) {}

    final long start = System.currentTimeMillis();

    // الجلسة كلها
    final List<Tally> taken = new ArrayList<>();
    final List<Tally> menuDropped = new ArrayList<>();
    final List<Tally> dropped = new ArrayList<>();
    final List<Tally> pickedUp = new ArrayList<>();
    final Map<Material, Integer> placed = new HashMap<>();
    int broken;
    final List<String> commands = new ArrayList<>();

    // لسه متبعتش (بيتجمّع ويتبعت كل كام ثانية)
    final List<Tally> pendingTaken = new ArrayList<>();
    final List<Tally> pendingMenuDropped = new ArrayList<>();
    final List<Tally> pendingDropped = new ArrayList<>();
    final List<Tally> pendingPickedUp = new ArrayList<>();

    // لكشف "نقل الغرض جوه الشنطة" بدل ما يتحسب سحب جديد
    final Deque<Recent> removedRecent = new ArrayDeque<>();
    final Deque<Recent> placedRecent = new ArrayDeque<>();

    static void add(List<Tally> list, ItemStack item, int amount) {
        if (amount <= 0) return;
        for (Tally t : list) {
            if (t.proto.isSimilar(item)) {
                t.amount += amount;
                return;
            }
        }
        list.add(new Tally(item, amount));
    }

    static void sub(List<Tally> list, ItemStack item, int amount) {
        for (int i = 0; i < list.size(); i++) {
            Tally t = list.get(i);
            if (t.proto.isSimilar(item)) {
                t.amount -= amount;
                if (t.amount <= 0) list.remove(i);
                return;
            }
        }
    }

    static int total(List<Tally> list) {
        int n = 0;
        for (Tally t : list) n += t.amount;
        return n;
    }

    boolean hasPending() {
        return !pendingTaken.isEmpty() || !pendingMenuDropped.isEmpty()
                || !pendingDropped.isEmpty() || !pendingPickedUp.isEmpty();
    }

    void prune(long now) {
        while (!removedRecent.isEmpty() && now - removedRecent.peekFirst().at() > 1500) removedRecent.pollFirst();
        while (!placedRecent.isEmpty() && now - placedRecent.peekFirst().at() > 1500) placedRecent.pollFirst();
    }

    /** بيدوّر على غرض مطابق في الـ deque وبيشيله لو لقاه. */
    static boolean consume(Deque<Recent> dq, ItemStack item) {
        var it = dq.iterator();
        while (it.hasNext()) {
            Recent r = it.next();
            if (r.item().isSimilar(item) && r.item().getAmount() == item.getAmount()) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
