package vhdeckopt.vault;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import iskallia.vault.container.CardBinderContainer;
import iskallia.vault.container.inventory.CardDeckContainer;
import iskallia.vault.core.card.Card;
import iskallia.vault.core.card.CardDeck;
import iskallia.vault.core.card.CardEntry;
import iskallia.vault.core.card.CardPos;
import iskallia.vault.core.card.modifier.card.CardModifier;
import iskallia.vault.core.card.modifier.card.GreedCardModifier;
import iskallia.vault.core.card.modifier.deck.BountyDeckModifier;
import iskallia.vault.core.card.modifier.deck.DeckModifier;
import iskallia.vault.core.card.modifier.deck.ISlotModifier;
import iskallia.vault.core.random.JavaRandom;
import iskallia.vault.gear.attribute.VaultGearAttribute;
import iskallia.vault.gear.attribute.VaultGearAttributeInstance;
import iskallia.vault.gear.attribute.VaultGearModifier;
import iskallia.vault.item.CardDeckItem;
import iskallia.vault.item.CardItem;
import iskallia.vault.item.DeckSocketItem;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import vhdeckopt.Weights;

/**
 * The only class that touches the Vault mod (iskallia.vault.*). Its internals
 * are not a stable API, so when the pack updates this is the file to fix.
 *
 * Built against the_vault 1.18.2-21.6.1-remastered.6956. What it relies on:
 *  - CardBinderContainer: slots, CardSlot.getContainerIndex(), CardDeckInputSlot,
 *    getCurrentPage(), getTotalPages()
 *  - CardDeckItem.getCardDeck(stack); an equipped deck is found through Curios
 *  - CardItem.getCard(stack) / create(card), card NBT under the stack's "data" tag
 *  - CardDeck: getSlots, getCards, getCard, setCard, getSnapshotAttributes; new CardPos(x, y[, "Arcane"])
 *  - Server-side apply: CardBinderContainer.getDeckContainer() with getSlotMapping()
 *    (slot index = x + y * 9), CardSlot.container (the binder's storage), and the
 *    private CardBinderContainer.refreshAfterStorageChange() by reflection
 *  - config/the_vault/card/decks.json: values.<id>.name and .layout[].value / .weight
 *  - CardEntry.getModifier().getSnapshotAttributes(tier)
 *  - Arcane rule: slots whose CardPos.allowedGroups has "Arcane" take only cards
 *    with the "Arcane" group, and other slots refuse them (DeckSlot.mayPlace)
 *  - Deck cores: DeckSocketItem.getDeckModifier(stack); CardDeck.getModifiers,
 *    getSocketCount, getMatchingModifier, addModifier(modifier, JavaRandom),
 *    getModifiersOfType; DeckModifier.isBetter; CardDeckItem.setCardDeck.
 *    The socketing rule copies DeckSocketAnvilRecipe.onCraft. Cores implementing
 *    ISlotModifier roll their slots when socketed.
 *  - BountyDeckModifier.test(deck): whether the layout meets a Bounty core's condition
 *  - CardDeck.addText(lines, lines.size(), flag, tick): the deck tooltip, as
 *    CardDeckItem.appendHoverText calls it
 */
public final class VaultAdapter {
    private static final String ARCANE = "Arcane";

    private VaultAdapter() {
    }

    public static boolean isBinder(AbstractContainerMenu menu) {
        return menu instanceof CardBinderContainer;
    }

    public static boolean isCard(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CardItem;
    }

    /** A storage slot of the binder as shown on the current page. The stack may be empty. */
    public record Seen(int index, ItemStack stack) {
    }

    public static List<Seen> visibleBinderCards(AbstractContainerMenu menu) {
        List<Seen> seen = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot instanceof CardBinderContainer.CardSlot cardSlot && cardSlot.isActive() && cardSlot.getContainerIndex() >= 0) {
                seen.add(new Seen(cardSlot.getContainerIndex(), cardSlot.getItem().copy()));
            }
        }
        return seen;
    }

    public static int binderPage(AbstractContainerMenu menu) {
        return ((CardBinderContainer) menu).getCurrentPage();
    }

    public static int binderPages(AbstractContainerMenu menu) {
        return ((CardBinderContainer) menu).getTotalPages();
    }

    /** A card the player could use, and where it was seen. */
    public record Sourced(ItemStack stack, String source) {
    }

    /**
     * A deck the optimizer can be run for: a real deck item (in the binder,
     * equipped, or in the inventory), the same deck with a core from the
     * inventory socketed into it as a trial, or an empty deck of a type from
     * the pack's deck config, for trying a shape the player does not own.
     *
     * @param source the real deck item this choice comes from (the same object
     *               for a deck and its core trials); ItemStack.EMPTY for previews
     * @param core   the trial core's name, or null when no core is being tried
     * @param coreRollsSlots true when the core picks its slots at random when
     *               socketed, so the real result can differ from the trial
     */
    public record DeckChoice(String label, ItemStack stack, String[] layout, boolean inBinder,
                             ItemStack source, String core, boolean coreRollsSlots) {
        public boolean isPreview() {
            return this.layout != null;
        }

        /** A real deck with a core added that it does not have yet. Only a what-if: it cannot be applied. */
        public boolean isCoreTrial() {
            return this.core != null;
        }
    }

    /**
     * Real decks first (binder slot, equipped, inventory), each followed by a
     * trial of every core in the inventory it could take, then every deck type
     * in the pack config.
     */
    public static List<DeckChoice> deckChoices(AbstractContainerMenu menu, Player player) {
        List<DeckChoice> real = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot instanceof CardBinderContainer.CardDeckInputSlot && isDeck(slot.getItem())) {
                real.add(real("In binder", slot.getItem(), true));
            }
        }
        List<ItemStack> cores = new ArrayList<>();
        if (player != null) {
            ItemStack equipped = equippedDeck(player);
            if (isDeck(equipped)) {
                real.add(real("Equipped", equipped, false));
            }
            for (ItemStack stack : player.getInventory().items) {
                if (isDeck(stack)) {
                    real.add(real("Inventory", stack, false));
                }
            }
            cores = cores(player.getInventory().items);
        }
        List<DeckChoice> choices = new ArrayList<>();
        for (DeckChoice deck : real) {
            choices.add(deck);
            for (ItemStack core : cores) {
                DeckChoice trial = coreTrial(deck, core);
                if (trial != null) {
                    choices.add(trial);
                }
            }
        }
        choices.addAll(configDecks());
        return choices;
    }

    private static boolean isDeck(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CardDeckItem
            && CardDeckItem.getCardDeck(stack).map(deck -> !deck.getSlots().isEmpty()).orElse(false);
    }

    private static DeckChoice real(String where, ItemStack stack, boolean inBinder) {
        ItemStack copy = stack.copy();
        return new DeckChoice(where + ": " + stack.getHoverName().getString(), copy, null, inBinder, copy, null, false);
    }

    /** The distinct deck cores (the_vault:deck_socket items holding a modifier) among these stacks. */
    private static List<ItemStack> cores(List<ItemStack> stacks) {
        Map<String, ItemStack> distinct = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.getItem() instanceof DeckSocketItem && DeckSocketItem.getDeckModifier(stack).isPresent()) {
                distinct.putIfAbsent(String.valueOf(stack.getTag()), stack);
            }
        }
        return new ArrayList<>(distinct.values());
    }

    /**
     * The deck with this core socketed into a copy of it, or null if the core
     * could not go in. Uses the anvil's rule: a core that matches one already in
     * the deck must be better than it and replaces it; any other core needs a
     * free socket.
     */
    private static DeckChoice coreTrial(DeckChoice deck, ItemStack core) {
        try {
            DeckModifier<?> modifier = DeckSocketItem.getDeckModifier(core).orElse(null);
            ItemStack copy = deck.stack().copy();
            CardDeck cards = CardDeckItem.getCardDeck(copy).orElse(null);
            if (modifier == null || cards == null) {
                return null;
            }
            DeckModifier<?> matching = cards.getMatchingModifier(modifier).orElse(null);
            boolean fits = matching == null ? cards.getModifiers().size() < cards.getSocketCount() : modifier.isBetter(matching);
            if (!fits) {
                return null;
            }
            // Rolled once here, so every evaluator scores the same trial deck.
            cards.addModifier(modifier, JavaRandom.ofNanoTime());
            CardDeckItem.setCardDeck(copy, cards);
            String name = core.getHoverName().getString();
            return new DeckChoice(deck.label() + " + " + name, copy, null, false, deck.source(), name, modifier instanceof ISlotModifier);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Deck shapes from config/the_vault/card/decks.json: rows of characters where
     * 'O' is a slot and 'A' an arcane slot. Types whose layouts all have weight 0
     * cannot be obtained and are skipped. This is the client's copy of the file.
     */
    private static List<DeckChoice> configDecks() {
        List<DeckChoice> out = new ArrayList<>();
        Path file = FMLPaths.CONFIGDIR.get().resolve("the_vault").resolve("card").resolve("decks.json");
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject values = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("values");
            for (Map.Entry<String, JsonElement> type : values.entrySet()) {
                JsonObject entry = type.getValue().getAsJsonObject();
                String name = entry.has("name") ? entry.get("name").getAsString() : type.getKey();
                JsonArray layouts = entry.getAsJsonArray("layout");
                int number = 0;
                for (JsonElement element : layouts) {
                    JsonObject layout = element.getAsJsonObject();
                    number++;
                    if (layout.has("weight") && layout.get("weight").getAsDouble() <= 0.0) {
                        continue;
                    }
                    JsonArray rows = layout.getAsJsonArray("value");
                    String[] lines = new String[rows.size()];
                    int slots = 0;
                    for (int row = 0; row < lines.length; row++) {
                        lines[row] = rows.get(row).getAsString();
                        for (char c : lines[row].toCharArray()) {
                            slots += c == 'O' || c == 'A' ? 1 : 0;
                        }
                    }
                    if (slots > 0) {
                        String variant = layouts.size() > 1 ? " #" + number : "";
                        out.add(new DeckChoice("Preview: " + name + variant + " (" + slots + " slots, empty)", ItemStack.EMPTY, lines, false,
                            ItemStack.EMPTY, null, false));
                    }
                }
            }
        } catch (Exception e) {
            // No readable deck config: only real decks are offered.
        }
        return out;
    }

    private static CardDeck buildDeck(DeckChoice choice) {
        if (!choice.isPreview()) {
            return CardDeckItem.getCardDeck(choice.stack()).orElse(null);
        }
        CardDeck deck = new CardDeck();
        for (int row = 0; row < choice.layout().length; row++) {
            String line = choice.layout()[row];
            for (int column = 0; column < line.length(); column++) {
                if (line.charAt(column) == 'O') {
                    deck.setCard(new CardPos(column, row), null);
                } else if (line.charAt(column) == 'A') {
                    deck.setCard(new CardPos(column, row, ARCANE), null);
                }
            }
        }
        return deck;
    }

    /**
     * Copies everything needed out of the live game objects. Call on the client
     * thread. The cards inside every other real deck in "all" are offered as
     * spare cards too. Returns null when the chosen deck cannot be read.
     */
    public static DeckProblem capture(DeckChoice choice, List<DeckChoice> all, List<Sourced> spareCards) {
        CardDeck live = buildDeck(choice);
        if (live == null || live.getSlots().isEmpty()) {
            return null;
        }

        DeckProblem problem = new DeckProblem(choice);
        List<CardPos> slots = new ArrayList<>(live.getSlots());
        slots.sort(Comparator.<CardPos>comparingInt(pos -> pos.y).thenComparingInt(pos -> pos.x));
        int[] current = new int[slots.size()];
        for (int slot = 0; slot < current.length; slot++) {
            CardPos pos = slots.get(slot);
            problem.slotX.add(pos.x);
            problem.slotY.add(pos.y);
            problem.slotArcane.add(pos.allowedGroups != null && pos.allowedGroups.contains(ARCANE));
            Card card = live.getCard(pos).orElse(null);
            current[slot] = card == null || card.getEntries().isEmpty() ? -1 : problem.addCard(CardItem.create(card), "This deck");
        }
        problem.current = current;
        problem.hasCoreConditions = !live.getModifiersOfType(BountyDeckModifier.class).isEmpty();
        for (DeckChoice other : all) {
            // Core trials are copies of a real deck: their cards are that deck's cards, counted once.
            if (other.isPreview() || other.isCoreTrial() || other.source() == choice.source()) {
                continue;
            }
            CardDeck otherDeck = buildDeck(other);
            if (otherDeck != null) {
                for (Card card : otherDeck.getCards().values()) {
                    if (card != null && !card.getEntries().isEmpty()) {
                        problem.addCard(CardItem.create(card), other.label());
                    }
                }
            }
        }
        for (Sourced spare : spareCards) {
            problem.addCard(spare.stack(), spare.source());
        }
        problem.indexAttributes();
        return problem;
    }

    private static ItemStack equippedDeck(Player player) {
        try {
            return CuriosApi.getCuriosHelper()
                .findFirstCurio(player, stack -> stack.getItem() instanceof CardDeckItem)
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** One deck slot of a layout to apply: its position and the identity of its card (null for empty). */
    public record Wanted(int x, int y, CompoundTag card) {
    }

    /**
     * SERVER SIDE. Rearranges the deck in the binder the player has open so it
     * matches the wanted layout, taking cards from the deck itself, the binder's
     * storage and the player's inventory, and putting removed cards into the
     * binder. Everything is checked before anything is moved: either the whole
     * layout is applied or nothing changes. Returns null on success, or the
     * reason it was refused.
     */
    public static String apply(ServerPlayer player, int containerId, List<Wanted> wanted) {
        if (!(player.containerMenu instanceof CardBinderContainer menu) || menu.containerId != containerId || !menu.stillValid(player)) {
            return "The binder is no longer open.";
        }
        CardDeckContainer deckSlots = menu.getDeckContainer();
        Container storage = null;
        for (Slot slot : menu.slots) {
            if (slot instanceof CardBinderContainer.CardSlot) {
                storage = slot.container;
                break;
            }
        }
        if (deckSlots == null || storage == null) {
            return "There is no deck in the binder's deck slot.";
        }

        // ---- Plan. Nothing is modified in this part. ----
        Map<Integer, CardPos> mapping = deckSlots.getSlotMapping();
        Map<Integer, CompoundTag> want = new HashMap<>();
        for (Wanted slot : wanted) {
            int index = slot.x() + slot.y() * 9;
            if (!mapping.containsKey(index) || want.containsKey(index)) {
                return "The deck in the binder has a different shape. Rescan and try again.";
            }
            want.put(index, slot.card());
        }
        if (want.size() != mapping.size()) {
            return "The deck in the binder has a different shape. Rescan and try again.";
        }

        List<Integer> changed = new ArrayList<>();
        List<ItemStack> leaving = new ArrayList<>();
        for (int index : new TreeSet<>(mapping.keySet())) {
            ItemStack now = deckSlots.getItem(index);
            if (!Objects.equals(cardKey(now), want.get(index))) {
                changed.add(index);
                if (!now.isEmpty()) {
                    leaving.add(now);
                }
            }
        }
        if (changed.isEmpty()) {
            return null;
        }

        Map<CompoundTag, Deque<Integer>> inStorage = new HashMap<>();
        int emptyStorage = 0;
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (stack.isEmpty()) {
                emptyStorage++;
            } else {
                CompoundTag key = cardKey(stack);
                if (key != null) {
                    inStorage.computeIfAbsent(key, k -> new ArrayDeque<>()).add(i);
                }
            }
        }
        Inventory inventory = player.getInventory();
        int[] inventoryTaken = new int[inventory.items.size()];

        Map<Integer, ItemStack> fromDeck = new HashMap<>();
        Map<Integer, Integer> fromStorage = new HashMap<>();
        Map<Integer, Integer> fromInventory = new HashMap<>();
        for (int index : changed) {
            CompoundTag key = want.get(index);
            if (key == null) {
                continue;
            }
            Card card = new Card();
            card.readNbt(key.copy());
            CardPos pos = mapping.get(index);
            boolean arcaneSlot = pos.allowedGroups != null && pos.allowedGroups.contains(ARCANE);
            if (card.getEntries().isEmpty() || arcaneSlot != card.hasGroup(ARCANE)) {
                return "A card in this layout does not fit its slot.";
            }
            ItemStack moved = null;
            for (ItemStack candidate : leaving) {
                if (key.equals(cardKey(candidate))) {
                    moved = candidate;
                    break;
                }
            }
            if (moved != null) {
                leaving.remove(moved);
                fromDeck.put(index, moved);
                continue;
            }
            Deque<Integer> stored = inStorage.get(key);
            if (stored != null && !stored.isEmpty()) {
                fromStorage.put(index, stored.poll());
                continue;
            }
            boolean found = false;
            for (int i = 0; i < inventoryTaken.length && !found; i++) {
                ItemStack stack = inventory.items.get(i);
                if (stack.getCount() > inventoryTaken[i] && key.equals(cardKey(stack))) {
                    inventoryTaken[i]++;
                    fromInventory.put(index, i);
                    found = true;
                }
            }
            if (!found) {
                return "A card in this layout is no longer where it was. Rescan and try again.";
            }
        }
        if (leaving.size() > emptyStorage + fromStorage.size()) {
            return "The binder has no room for the cards coming out of the deck.";
        }

        // ---- Commit. Cards taken out and not yet put somewhere are tracked in "held";
        // whatever is still held at the end, for any reason, goes to the player. ----
        Set<ItemStack> held = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Map<Integer, ItemStack> placing = new HashMap<>(fromDeck);
            for (Map.Entry<Integer, Integer> take : fromStorage.entrySet()) {
                ItemStack stack = storage.getItem(take.getValue());
                storage.setItem(take.getValue(), ItemStack.EMPTY);
                held.add(stack);
                placing.put(take.getKey(), stack);
            }
            for (Map.Entry<Integer, Integer> take : fromInventory.entrySet()) {
                ItemStack stack = inventory.items.get(take.getValue()).split(1);
                held.add(stack);
                placing.put(take.getKey(), stack);
            }
            for (int index : changed) {
                ItemStack old = deckSlots.getItem(index);
                if (!old.isEmpty()) {
                    held.add(old);
                    deckSlots.setItem(index, ItemStack.EMPTY);
                }
            }
            for (Map.Entry<Integer, ItemStack> place : placing.entrySet()) {
                deckSlots.setItem(place.getKey(), place.getValue());
                held.remove(place.getValue());
            }
            int next = 0;
            for (ItemStack stack : new ArrayList<>(held)) {
                while (next < storage.getContainerSize() && !storage.getItem(next).isEmpty()) {
                    next++;
                }
                if (next >= storage.getContainerSize()) {
                    break;
                }
                storage.setItem(next, stack);
                held.remove(stack);
            }
            deckSlots.setChanged();
        } finally {
            for (ItemStack stack : held) {
                inventory.placeItemBackInInventory(stack);
            }
            if (storage instanceof BlockEntity blockEntity) {
                blockEntity.setChanged();
            }
            inventory.setChanged();
            refreshBinder(menu);
        }
        return null;
    }

    /** Makes the binder re-pack its storage and resend the page to the player, as it does after a click. */
    private static void refreshBinder(CardBinderContainer menu) {
        try {
            Method refresh = CardBinderContainer.class.getDeclaredMethod("refreshAfterStorageChange");
            refresh.setAccessible(true);
            refresh.invoke(menu);
        } catch (ReflectiveOperationException | RuntimeException e) {
            menu.broadcastChanges();
        }
    }

    private static double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : 1.0;
    }

    private static <T> Component displayOf(VaultGearAttributeInstance<T> instance) {
        return instance.getAttribute().getReader().getDisplay(instance, VaultGearModifier.AffixType.PREFIX);
    }

    /**
     * A card's identity as text: identical cards give the same id wherever they
     * are. Null if the stack is not a real card. Used to remember required cards.
     */
    public static String cardId(ItemStack stack) {
        CompoundTag key = cardKey(stack);
        return key == null ? null : key.toString();
    }

    /**
     * A card's identity: its data after a read/write round trip through the Vault
     * code, so the same card compares equal whether it came from a deck, the
     * binder or an inventory, on the client or the server. Null if not a real card.
     */
    private static CompoundTag cardKey(ItemStack stack) {
        if (!isCard(stack)) {
            return null;
        }
        Card card = CardItem.getCard(stack);
        return card.getEntries().isEmpty() ? null : card.writeNbt().orElse(null);
    }

    /** One distinct card; identical cards share a kind and add to its count. */
    private static final class Kind {
        final ItemStack stack;
        final CompoundTag key;
        final boolean arcane;
        final Map<String, Integer> sources = new LinkedHashMap<>();
        int count;
        boolean nonStat;

        Kind(ItemStack stack, CompoundTag key, boolean arcane) {
            this.stack = stack;
            this.key = key;
            this.arcane = arcane;
        }
    }

    /** A snapshot of one deck and the cards that could go in it. Holds no live game state. */
    public static final class DeckProblem {
        private final List<Integer> slotX = new ArrayList<>();
        private final List<Integer> slotY = new ArrayList<>();
        private final List<Boolean> slotArcane = new ArrayList<>();
        private final List<Kind> kinds = new ArrayList<>();
        private final Map<String, Integer> kindByKey = new LinkedHashMap<>();
        private final Map<VaultGearAttribute<?>, Integer> attributeIndex = new IdentityHashMap<>();
        private final List<String> attributeKeys = new ArrayList<>();
        private double[] norms = new double[0];
        private int[] current = new int[0];
        /** The deck holds a core with a condition on the layout (a Bounty core). */
        private boolean hasCoreConditions;
        /** Index of Weights.CORE_CONDITIONS in attributeKeys, or -1. */
        private int coreConditionIndex = -1;

        private final DeckChoice choice;

        private DeckProblem(DeckChoice choice) {
            this.choice = choice;
        }

        private int addCard(ItemStack stack, String source) {
            CompoundTag tag = cardKey(stack);
            if (tag == null) {
                return -1;
            }
            String key = tag.toString();
            Integer index = this.kindByKey.get(key);
            if (index == null) {
                ItemStack single = stack.copy();
                single.setCount(1);
                index = this.kinds.size();
                this.kinds.add(new Kind(single, tag, CardItem.getCard(stack).hasGroup(ARCANE)));
                this.kindByKey.put(key, index);
            }
            Kind kind = this.kinds.get(index);
            kind.count += stack.getCount();
            kind.sources.merge(source, stack.getCount(), Integer::sum);
            return index;
        }

        /** Finds every attribute any card can give, and the largest single value of each. */
        private void indexAttributes() {
            List<Double> largest = new ArrayList<>();
            for (Kind kind : this.kinds) {
                boolean givesStat = false;
                try {
                    Card card = CardItem.getCard(kind.stack);
                    for (CardEntry entry : card.getEntries()) {
                        CardModifier<?> modifier = entry.getModifier();
                        if (modifier == null) {
                            continue;
                        }
                        if (modifier instanceof GreedCardModifier) {
                            givesStat = true;
                        }
                        for (VaultGearAttributeInstance<?> instance : modifier.getSnapshotAttributes(card.getTier())) {
                            givesStat = true;
                            Integer index = this.attributeIndex.get(instance.getAttribute());
                            if (index == null) {
                                index = this.attributeKeys.size();
                                this.attributeIndex.put(instance.getAttribute(), index);
                                this.attributeKeys.add(String.valueOf(instance.getAttribute().getRegistryName()));
                                largest.add(0.0);
                            }
                            largest.set(index, Math.max(largest.get(index), Math.abs(numeric(instance.getValue()))));
                        }
                    }
                } catch (RuntimeException e) {
                    // A card the Vault code cannot read is treated as giving nothing.
                }
                kind.nonStat = !givesStat;
            }
            if (this.hasCoreConditions) {
                this.coreConditionIndex = this.attributeKeys.size();
                this.attributeKeys.add(Weights.CORE_CONDITIONS);
                largest.add(1.0);
            }
            this.attributeKeys.add(Weights.NON_STAT_CARDS);
            largest.add(1.0);
            this.norms = new double[largest.size()];
            for (int i = 0; i < this.norms.length; i++) {
                this.norms[i] = largest.get(i) > 0.0 ? largest.get(i) : 1.0;
            }
        }

        public int slotCount() {
            return this.slotX.size();
        }

        public int slotX(int slot) {
            return this.slotX.get(slot);
        }

        public int slotY(int slot) {
            return this.slotY.get(slot);
        }

        public boolean slotArcane(int slot) {
            return this.slotArcane.get(slot);
        }

        public int kindCount() {
            return this.kinds.size();
        }

        public int available(int kind) {
            return this.kinds.get(kind).count;
        }

        public int totalCards() {
            return this.kinds.stream().mapToInt(kind -> kind.count).sum();
        }

        public boolean allowed(int slot, int kind) {
            return this.slotArcane.get(slot) == this.kinds.get(kind).arcane;
        }

        public ItemStack kindStack(int kind) {
            return this.kinds.get(kind).stack;
        }

        /** The kind's card id, as given by VaultAdapter.cardId. */
        public String kindId(int kind) {
            return this.kinds.get(kind).key.toString();
        }

        /** The kind with this card id, or -1 if no known card has it. */
        public int kindOf(String cardId) {
            Integer kind = this.kindByKey.get(cardId);
            return kind == null ? -1 : kind;
        }

        /** The choice this problem was captured from. */
        public DeckChoice choice() {
            return this.choice;
        }

        /** For example "Deck x1, Binder p.3 x2". */
        public String kindSources(int kind) {
            StringBuilder out = new StringBuilder();
            this.kinds.get(kind).sources.forEach((source, count) -> {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(source).append(" x").append(count);
            });
            return out.toString();
        }

        public int[] current() {
            return this.current;
        }

        /** True when this deck sits in the binder's deck slot, the only place the server can rearrange. */
        public boolean inBinder() {
            return this.choice.inBinder();
        }

        /** A layout in the form the server needs: each slot's position and card identity. */
        public List<Wanted> wanted(int[] layout) {
            List<Wanted> out = new ArrayList<>();
            for (int slot = 0; slot < layout.length; slot++) {
                out.add(new Wanted(this.slotX.get(slot), this.slotY.get(slot), layout[slot] < 0 ? null : this.kinds.get(layout[slot]).key));
            }
            return out;
        }

        /**
         * Attribute ids, in the order used by Evaluator.totals(). The last is
         * Weights.NON_STAT_CARDS; Weights.CORE_CONDITIONS comes just before it
         * when the deck holds a core with a condition.
         */
        public List<String> attributeKeys() {
            return this.attributeKeys;
        }

        /** Largest value a single card gives for each attribute; used to put attributes on one scale. */
        public double[] norms() {
            return this.norms;
        }

        /** Each evaluator owns a private deck copy, so use one per thread. */
        public Evaluator newEvaluator() {
            return new Evaluator(this);
        }
    }

    /** Scores layouts by asking the Vault mod's own deck code. Not thread-safe; one per thread. */
    public static final class Evaluator {
        private final DeckProblem problem;
        private final CardDeck deck;
        private final CardPos[] positions;
        private final Card[][] copies;
        private final int[] used;

        private Evaluator(DeckProblem problem) {
            this.problem = problem;
            // A fresh deck object per evaluator: real decks are re-read from the item's NBT.
            this.deck = buildDeck(problem.choice);
            int slots = problem.slotCount();
            this.positions = new CardPos[slots];
            for (CardPos pos : this.deck.getSlots()) {
                for (int slot = 0; slot < slots; slot++) {
                    if (pos.x == problem.slotX(slot) && pos.y == problem.slotY(slot)) {
                        this.positions[slot] = pos;
                    }
                }
            }
            for (int slot = 0; slot < slots; slot++) {
                if (this.positions[slot] == null) {
                    throw new IllegalStateException("Deck copy is missing slot " + problem.slotX(slot) + "," + problem.slotY(slot));
                }
            }
            this.copies = new Card[problem.kindCount()][];
            for (int kind = 0; kind < this.copies.length; kind++) {
                this.copies[kind] = new Card[Math.min(problem.available(kind), slots)];
            }
            this.used = new int[problem.kindCount()];
        }

        /**
         * The Vault code counts neighbouring cards by object identity, so two
         * copies of the same card must be two separate Card objects.
         */
        private Card copy(int kind, int n) {
            if (this.copies[kind][n] == null) {
                this.copies[kind][n] = CardItem.getCard(this.problem.kinds.get(kind).stack);
            }
            return this.copies[kind][n];
        }

        private double place(int[] layout) {
            java.util.Arrays.fill(this.used, 0);
            double nonStat = 0.0;
            for (int slot = 0; slot < layout.length; slot++) {
                int kind = layout[slot];
                if (kind < 0) {
                    this.deck.setCard(this.positions[slot], null);
                } else {
                    this.deck.setCard(this.positions[slot], this.copy(kind, this.used[kind]++));
                    if (this.problem.kinds.get(kind).nonStat) {
                        nonStat += 1.0;
                    }
                }
            }
            return nonStat;
        }

        /** Attribute totals for a layout, indexed like DeckProblem.attributeKeys(). */
        public double[] totals(int[] layout) {
            double[] totals = new double[this.problem.attributeKeys.size()];
            totals[totals.length - 1] = this.place(layout);
            for (VaultGearAttributeInstance<?> instance : this.deck.getSnapshotAttributes()) {
                Integer index = this.problem.attributeIndex.get(instance.getAttribute());
                if (index != null) {
                    totals[index] += numeric(instance.getValue());
                }
            }
            if (this.problem.coreConditionIndex >= 0) {
                for (BountyDeckModifier bounty : this.deck.getModifiersOfType(BountyDeckModifier.class)) {
                    if (bounty.test(this.deck)) {
                        totals[this.problem.coreConditionIndex] += 1.0;
                    }
                }
            }
            return totals;
        }

        /**
         * The deck's own tooltip text for a layout, exactly as the game builds it
         * (stats, card effects, sockets and modifiers). Client thread only.
         */
        public List<Component> deckText(int[] layout) {
            this.place(layout);
            List<Component> lines = new ArrayList<>();
            this.deck.addText(lines, lines.size(), TooltipFlag.Default.NORMAL, 0.0F);
            return lines;
        }

        /** The game's own wording for each attribute of a layout, keyed by attribute id. Client thread only. */
        public Map<String, String> display(int[] layout) {
            this.place(layout);
            Map<String, String> out = new LinkedHashMap<>();
            for (VaultGearAttributeInstance<?> instance : this.deck.getSnapshotAttributes()) {
                Component text = displayOf(instance);
                if (text != null) {
                    out.merge(String.valueOf(instance.getAttribute().getRegistryName()), text.getString(), (a, b) -> a + ", " + b);
                }
            }
            return out;
        }
    }
}
