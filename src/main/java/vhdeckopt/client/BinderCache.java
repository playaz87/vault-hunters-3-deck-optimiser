package vhdeckopt.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import vhdeckopt.vault.VaultAdapter;

/**
 * Remembers the cards seen in the open Card Binder. The client is only sent
 * the page on screen, so cards are collected as the player flips pages.
 * Entries are keyed by the binder's storage index. The cache lasts while one
 * binder screen stays open, and resets itself if the storage is seen to change.
 */
final class BinderCache {
    private static final int SLOTS_PER_PAGE = 38;
    /** Ticks a page must look the same before it is trusted (slot contents arrive after the page flip). */
    private static final int SETTLE_TICKS = 6;

    private final Map<Integer, ItemStack> cards = new TreeMap<>();
    private final Set<Integer> pages = new TreeSet<>();
    private AbstractContainerMenu menu;
    private List<VaultAdapter.Seen> last = List.of();
    private int lastPage = -1;
    private int stableTicks;

    void observe(AbstractContainerMenu current) {
        if (current != this.menu) {
            this.clear();
            this.menu = current;
        }
        List<VaultAdapter.Seen> seen = VaultAdapter.visibleBinderCards(current);
        int page = VaultAdapter.binderPage(current);
        if (page != this.lastPage || !same(seen, this.last)) {
            this.stableTicks = 0;
        } else if (this.stableTicks < SETTLE_TICKS) {
            this.stableTicks++;
            if (this.stableTicks == SETTLE_TICKS) {
                this.commit(seen, page);
            }
        }
        this.last = seen;
        this.lastPage = page;
    }

    private void commit(List<VaultAdapter.Seen> seen, int page) {
        for (VaultAdapter.Seen slot : seen) {
            ItemStack known = this.cards.get(slot.index());
            if (known != null && !ItemStack.matches(known, slot.stack())) {
                // The binder's contents moved under us; everything remembered may be stale.
                this.cards.clear();
                this.pages.clear();
                break;
            }
        }
        for (VaultAdapter.Seen slot : seen) {
            if (VaultAdapter.isCard(slot.stack())) {
                this.cards.put(slot.index(), slot.stack());
            }
        }
        this.pages.add(page);
    }

    private static boolean same(List<VaultAdapter.Seen> a, List<VaultAdapter.Seen> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).index() != b.get(i).index() || !ItemStack.matches(a.get(i).stack(), b.get(i).stack())) {
                return false;
            }
        }
        return true;
    }

    void clear() {
        this.cards.clear();
        this.pages.clear();
        this.last = List.of();
        this.lastPage = -1;
        this.stableTicks = 0;
    }

    int pagesSeen() {
        return this.pages.size();
    }

    int size() {
        return this.cards.size();
    }

    List<VaultAdapter.Sourced> sourced() {
        List<VaultAdapter.Sourced> out = new ArrayList<>();
        this.cards.forEach((index, stack) -> out.add(new VaultAdapter.Sourced(stack, "Binder p." + (index / SLOTS_PER_PAGE + 1))));
        return out;
    }
}
