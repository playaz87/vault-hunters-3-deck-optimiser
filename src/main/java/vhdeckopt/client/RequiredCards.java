package vhdeckopt.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.item.ItemStack;
import vhdeckopt.vault.VaultAdapter;

/**
 * The cards the player has marked as required: every suggested layout must
 * hold them, even when they score nothing (resource and task cards). Kept by
 * card identity, so marking one copy marks every identical copy. Lasts until
 * the game closes.
 */
final class RequiredCards {
    /** Card id to how many copies every layout must hold. */
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    /** Card ids by stack, so outlines can be drawn every frame without re-reading card data. */
    private static final Map<ItemStack, String> IDS = new WeakHashMap<>();
    private static final String NOT_A_CARD = "";

    private RequiredCards() {
    }

    /** The stack's card id, or null if it is not a card. */
    static String id(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String id = IDS.get(stack);
        if (id == null) {
            String read = VaultAdapter.cardId(stack);
            id = read == null ? NOT_A_CARD : read;
            IDS.put(stack, id);
        }
        return id.isEmpty() ? null : id;
    }

    static boolean isRequired(ItemStack stack) {
        String id = id(stack);
        return id != null && COUNTS.containsKey(id);
    }

    static int count(String id) {
        return COUNTS.getOrDefault(id, 0);
    }

    /** Requires one copy of the card, or stops requiring it if it already was. */
    static void toggle(String id) {
        if (COUNTS.remove(id) == null) {
            COUNTS.put(id, 1);
        }
    }

    /** Changes how many copies are required; at 0 the card is no longer required. */
    static void adjust(String id, int delta) {
        int next = count(id) + delta;
        if (next <= 0) {
            COUNTS.remove(id);
        } else {
            COUNTS.put(id, next);
        }
    }

    static Map<String, Integer> all() {
        return Collections.unmodifiableMap(COUNTS);
    }

    static int size() {
        return COUNTS.size();
    }

    static void clear() {
        COUNTS.clear();
    }
}
