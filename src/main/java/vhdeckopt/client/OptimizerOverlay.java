package vhdeckopt.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import vhdeckopt.Network;
import vhdeckopt.Weights;
import vhdeckopt.search.Annealer;
import vhdeckopt.search.Problem;
import vhdeckopt.search.Scoring;
import vhdeckopt.vault.VaultAdapter;

/**
 * The optimizer panel drawn over the Card Binder: a list of the best layouts
 * found, the required cards, the deck grid for the selected layout, and on the
 * right either every attribute with its weight, cap and totals, or the deck's
 * own stat text for the current and the selected layout. Immediate-mode: each
 * frame redraws everything and records the clickable regions.
 */
final class OptimizerOverlay {
    private static final int WHITE = 0xFFFFFF;
    private static final int GREY = 0xA0A0A0;
    private static final int YELLOW = 0xFFD040;
    private static final int GREEN = 0x60E060;
    private static final int RED = 0xFF6060;
    private static final int ORANGE = 0xFFA040;
    private static final int TRIAL = 0xA0FFA0;
    private static final int CELL = 18;
    private static final int ROW = 11;
    private static final int TEXT_ROW = 10;
    private static final int LEFT_W = 170;
    private static final int MAX_LAYOUTS = 5;
    private static final int BAR_Y = 4;
    private static final int DECK_Y = 23;
    private static final int TOP = 38;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_KEYPAD_ENTER = 335;

    /** One layout shown in the list. Index 0 is always the deck as it is now. */
    private static final class View {
        final int[] layout;
        final double[] totals;
        final Map<String, String> display;
        final List<Component> deckText;
        final int changes;

        View(int[] layout, double[] totals, Map<String, String> display, List<Component> deckText, int changes) {
            this.layout = layout;
            this.totals = totals;
            this.display = display;
            this.deckText = deckText;
            this.changes = changes;
        }
    }

    private record Region(int x, int y, int w, int h, Runnable action) {
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final BinderCache cache;
    private final Path configFile = FMLPaths.CONFIGDIR.get().resolve("vhdeckoptimizer.json");
    private final List<View> views = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();

    private Weights weights = new Weights();
    private Scoring scoring;
    private AbstractContainerMenu menu;
    private List<VaultAdapter.DeckChoice> choices = List.of();
    private int choice;
    private VaultAdapter.DeckProblem problem;
    private VaultAdapter.Evaluator evaluator;
    /** The problem the last search ran on, kept to check required cards. */
    private Problem searched;
    private List<Integer> attributeOrder = List.of();
    private String message;
    private int selected;
    private int scroll;
    /** Weights, caps or required cards changed since the last search. */
    private boolean settingsChanged;
    /** The right-hand side shows the deck's own stat text instead of the attribute table. */
    private boolean statsView;
    /** Apply is a two-click action: the first click arms it, the second sends. */
    private boolean applyArmed;
    private boolean applying;
    private String notice;
    private boolean noticeGood;
    private int rescanIn = -1;
    private boolean closeRequested;

    /** A number being typed into the attribute table: which attribute, and its weight or its cap. */
    private String editKey;
    private boolean editCap;
    private String editText = "";

    /** One background search. A new search gets a new Run, so a cancelled one can never report into it. */
    private static final class Run {
        volatile boolean cancel;
        volatile double progress;
        volatile List<Annealer.Result> finished;
        volatile String error;
    }

    private Run run;
    private String error;

    OptimizerOverlay(BinderCache cache) {
        this.cache = cache;
    }

    static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Finds the decks on offer and opens the first (binder slot, else equipped, else inventory, else a preview). */
    void start(AbstractContainerMenu menu) {
        this.menu = menu;
        this.weights = Weights.load(this.configFile);
        String previous = this.choices.isEmpty() ? null : this.choices.get(this.choice).label();
        this.choices = VaultAdapter.deckChoices(menu, this.mc.player);
        this.choice = 0;
        for (int i = 0; i < this.choices.size(); i++) {
            if (this.choices.get(i).label().equals(previous)) {
                this.choice = i;
            }
        }
        this.load();
    }

    private void pick(int direction) {
        if (!this.choices.isEmpty()) {
            this.choice = Math.floorMod(this.choice + direction, this.choices.size());
            this.load();
        }
    }

    void tick() {
        if (this.rescanIn > 0 && --this.rescanIn == 0) {
            this.rescanIn = -1;
            this.start(this.menu);
        }
    }

    private boolean serverHasMod() {
        return this.mc.getConnection() != null && Network.CHANNEL.isRemotePresent(this.mc.getConnection().getConnection());
    }

    private void apply(View view) {
        if (!this.applyArmed) {
            this.applyArmed = true;
            return;
        }
        this.applyArmed = false;
        this.applying = true;
        this.notice = "Asking the server to move the cards...";
        this.noticeGood = true;
        Network.CHANNEL.sendToServer(new Network.Apply(this.menu.containerId, this.problem.wanted(view.layout)));
    }

    void applyResult(boolean applied, String text) {
        this.applying = false;
        this.notice = text;
        this.noticeGood = applied;
        if (applied) {
            // Give the server's slot updates a moment to arrive, then read everything again.
            this.rescanIn = 10;
        }
    }

    /** Snapshots the chosen deck and every known card, then searches in the background. */
    private void load() {
        this.stopSearch();
        this.applyArmed = false;
        this.editKey = null;
        this.views.clear();
        this.problem = null;
        this.searched = null;
        this.message = null;
        this.error = null;
        this.selected = 0;
        this.scroll = 0;
        if (this.choices.isEmpty()) {
            this.message = "No card deck found. Put one in the binder's deck slot, or equip one.";
            return;
        }

        List<VaultAdapter.Sourced> spare = new ArrayList<>();
        if (this.mc.player != null) {
            for (ItemStack stack : this.mc.player.getInventory().items) {
                if (VaultAdapter.isCard(stack)) {
                    spare.add(new VaultAdapter.Sourced(stack.copy(), "Inventory"));
                }
            }
        }
        spare.addAll(this.cache.sourced());

        try {
            this.problem = VaultAdapter.capture(this.choices.get(this.choice), this.choices, spare);
            if (this.problem == null) {
                this.message = "Could not read that deck.";
                return;
            }
            this.evaluator = this.problem.newEvaluator();
            this.rebuildScoring();
            this.views.add(this.view(this.problem.current()));
        } catch (RuntimeException e) {
            this.problem = null;
            this.message = "Could not read the deck: " + e;
            return;
        }
        List<String> keys = this.problem.attributeKeys();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> pretty(keys.get(i))));
        this.attributeOrder = order;
        this.search();
    }

    private View view(int[] layout) {
        int[] current = this.problem.current();
        int changes = 0;
        for (int slot = 0; slot < layout.length; slot++) {
            if (layout[slot] != current[slot]) {
                changes++;
            }
        }
        List<Component> text;
        try {
            text = this.evaluator.deckText(layout);
        } catch (RuntimeException e) {
            text = List.of(new TextComponent("Could not build the deck's text: " + e));
        }
        return new View(layout.clone(), this.evaluator.totals(layout), this.evaluator.display(layout), text, changes);
    }

    /** Builds the scoring from the current weights and caps. Call after either changes. */
    private void rebuildScoring() {
        List<String> keys = this.problem.attributeKeys();
        double[] weights = new double[keys.size()];
        double[] caps = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            weights[i] = this.weights.get(keys.get(i));
            Double cap = this.weights.cap(keys.get(i));
            caps[i] = cap == null ? Double.NaN : cap;
        }
        this.scoring = new Scoring(weights, this.problem.norms(), caps);
    }

    /** How many copies of each kind every layout must hold, from the cards marked as required. */
    private int[] requiredCounts() {
        int[] required = new int[this.problem.kindCount()];
        for (Map.Entry<String, Integer> entry : RequiredCards.all().entrySet()) {
            int kind = this.problem.kindOf(entry.getKey());
            if (kind >= 0) {
                required[kind] = Math.min(entry.getValue(), this.problem.available(kind));
            }
        }
        return required;
    }

    /** Required cards that are not among the known cards at all (not in any deck, page seen or inventory). */
    private int requiredUnknown() {
        int unknown = 0;
        for (String id : RequiredCards.all().keySet()) {
            if (this.problem.kindOf(id) < 0) {
                unknown++;
            }
        }
        return unknown;
    }

    private void search() {
        this.stopSearch();
        final Run run = new Run();
        this.run = run;
        this.error = null;
        this.settingsChanged = false;
        while (this.views.size() > 1) {
            this.views.remove(this.views.size() - 1);
        }
        this.selected = 0;

        final VaultAdapter.DeckProblem deck = this.problem;
        final VaultAdapter.Evaluator worker = deck.newEvaluator();
        final Scoring scoring = this.scoring;
        final int[] required = this.requiredCounts();
        final int iterations = this.weights.iterations;
        final int restarts = this.weights.restarts;
        Problem search = new Problem() {
            public int slotCount() {
                return deck.slotCount();
            }

            public int kindCount() {
                return deck.kindCount();
            }

            public int available(int kind) {
                return deck.available(kind);
            }

            public boolean allowed(int slot, int kind) {
                return deck.allowed(slot, kind);
            }

            public int required(int kind) {
                return required[kind];
            }

            public int[] current() {
                return deck.current();
            }

            public double score(int[] layout) {
                return scoring.score(worker.totals(layout));
            }
        };
        this.searched = search;
        Thread thread = new Thread(() -> {
            List<Annealer.Result> results;
            try {
                results = Annealer.run(search, iterations, restarts, System.nanoTime(), () -> run.cancel, p -> run.progress = p);
            } catch (Throwable t) {
                run.error = "Search failed: " + t;
                results = List.of();
            }
            run.finished = results;
        }, "vhdeckoptimizer-search");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopSearch() {
        if (this.run != null) {
            this.run.cancel = true;
            this.run = null;
        }
    }

    void close() {
        this.stopSearch();
    }

    /** Moves finished results from the search thread onto the screen. */
    private void collect() {
        if (this.run == null || this.run.finished == null) {
            return;
        }
        List<Annealer.Result> results = this.run.finished;
        this.error = this.run.error;
        this.run = null;
        for (Annealer.Result result : results) {
            if (this.views.size() > MAX_LAYOUTS) {
                break;
            }
            if (!Arrays.equals(result.layout(), this.problem.current())) {
                this.views.add(this.view(result.layout()));
            }
        }
        List<View> found = new ArrayList<>(this.views.subList(1, this.views.size()));
        found.sort((a, b) -> Double.compare(this.scoring.score(b.totals), this.scoring.score(a.totals)));
        while (this.views.size() > 1) {
            this.views.remove(this.views.size() - 1);
        }
        this.views.addAll(found);
        this.selected = this.views.size() > 1 ? 1 : 0;
    }

    void scroll(double delta, double mouseX, double mouseY) {
        if (inside(mouseX, mouseY, 8, DECK_Y - 1, LEFT_W + 60, 12)) {
            this.pick(delta > 0 ? -1 : 1);
        } else {
            this.scroll -= (int) Math.signum(delta) * 3;
        }
    }

    /** Returns true when the overlay should close. */
    boolean mouseClicked(double mouseX, double mouseY) {
        boolean wasEditing = this.editKey != null;
        for (int i = this.regions.size() - 1; i >= 0; i--) {
            Region region = this.regions.get(i);
            if (mouseX >= region.x && mouseX < region.x + region.w && mouseY >= region.y && mouseY < region.y + region.h) {
                click();
                boolean wasArmed = this.applyArmed;
                if (!this.applying) {
                    this.notice = null;
                }
                if (wasEditing) {
                    this.commitEdit();
                }
                region.action.run();
                if (wasArmed) {
                    this.applyArmed = false;
                }
                return this.closeRequested;
            }
        }
        if (wasEditing) {
            this.commitEdit();
        }
        return this.closeRequested;
    }

    // ---- Typing a weight or a cap ----

    private void startEdit(String key, boolean cap) {
        this.editKey = key;
        this.editCap = cap;
        Double value = cap ? this.weights.cap(key) : Double.valueOf(this.weights.get(key));
        this.editText = value == null ? "" : number(value);
    }

    /** Returns true when the key was used for typing (the caller must not act on it). */
    boolean keyPressed(int key) {
        if (this.editKey == null) {
            return false;
        }
        if (key == KEY_ESCAPE) {
            this.editKey = null;
        } else if (key == KEY_ENTER || key == KEY_KEYPAD_ENTER) {
            this.commitEdit();
        } else if (key == KEY_BACKSPACE && !this.editText.isEmpty()) {
            this.editText = this.editText.substring(0, this.editText.length() - 1);
        }
        return true;
    }

    void charTyped(char c) {
        if (this.editKey != null && this.editText.length() < 12 && (Character.isDigit(c) || c == '.' || c == '-')) {
            this.editText += c;
        }
    }

    /** Saves what was typed. An empty cap removes the cap; anything unreadable changes nothing. */
    private void commitEdit() {
        String key = this.editKey;
        this.editKey = null;
        if (key == null || this.problem == null) {
            return;
        }
        String text = this.editText.trim();
        try {
            if (this.editCap) {
                this.weights.setCap(key, text.isEmpty() ? null : Double.parseDouble(text));
            } else if (!text.isEmpty()) {
                this.weights.set(key, Double.parseDouble(text));
            }
        } catch (NumberFormatException e) {
            return;
        }
        this.weights.save(this.configFile);
        this.rebuildScoring();
        this.settingsChanged = true;
    }

    private void nudge(String key, int direction) {
        double step = Screen.hasShiftDown() ? 1.0 : 0.25;
        this.weights.set(key, this.weights.get(key) + direction * step);
        this.weights.save(this.configFile);
        this.rebuildScoring();
        this.settingsChanged = true;
    }

    private void toggleRequired(String id) {
        RequiredCards.toggle(id);
        this.settingsChanged = true;
    }

    private void adjustRequired(String id, int delta) {
        RequiredCards.adjust(id, delta);
        this.settingsChanged = true;
    }

    // ---- Drawing ----

    void render(PoseStack pose, Screen screen, int mouseX, int mouseY) {
        this.collect();
        this.regions.clear();
        Font font = this.mc.font;
        int width = screen.width;
        int height = screen.height;
        List<Component> tooltip = null;

        GuiComponent.fill(pose, 0, 0, width, height, 0xF0101018);

        // Buttons sit at the top: other mods (JEI) draw their own buttons along the bottom edge.
        int barX = 8;
        if (this.problem != null) {
            if (this.run != null) {
                barX += this.button(pose, barX, BAR_Y, 50, "Cancel", mouseX, mouseY, this::stopSearch) + 4;
            } else {
                barX += this.button(pose, barX, BAR_Y, this.settingsChanged ? 104 : 50, this.settingsChanged ? "Re-run (changes)" : "Re-run",
                    mouseX, mouseY, this::search) + 4;
            }
        }
        if (inside(mouseX, mouseY, barX, BAR_Y, 50, 14)) {
            tooltip = List.of(new TextComponent("Read the decks, inventory and binder cards again"),
                new TextComponent("§7Flip through the binder's pages first; only"),
                new TextComponent("§7pages you have looked at are known."));
        }
        barX += this.button(pose, barX, BAR_Y, 50, "Rescan", mouseX, mouseY, () -> this.start(this.menu)) + 4;
        if (this.problem != null) {
            String view = this.statsView ? "Weights" : "Deck stats";
            if (inside(mouseX, mouseY, barX, BAR_Y, 64, 14)) {
                tooltip = this.statsView
                    ? List.of(new TextComponent("Show the attribute table: weights, caps and totals"))
                    : List.of(new TextComponent("Show the stats as the deck itself lists them"),
                        new TextComponent("§7Current deck beside the selected layout."));
            }
            barX += this.button(pose, barX, BAR_Y, 64, view, mouseX, mouseY, () -> {
                this.statsView = !this.statsView;
                this.scroll = 0;
            }) + 4;
        }
        barX += this.button(pose, barX, BAR_Y, 50, "Close", mouseX, mouseY, () -> this.closeRequested = true) + 4;

        // Apply: only for a suggestion for the deck in the binder, and only if the server has this mod too.
        if (this.problem != null && this.run == null && !this.applying && this.selected > 0 && this.selected < this.views.size()) {
            View target = this.views.get(this.selected);
            VaultAdapter.DeckChoice chosen = this.problem.choice();
            boolean possible = this.problem.inBinder() && this.serverHasMod();
            String label = !possible ? "Apply" : this.applyArmed ? "Confirm: move " + target.changes + " slots" : "Apply #" + this.selected;
            int applyW = Math.max(60, font.width(label) + 12);
            if (inside(mouseX, mouseY, barX, BAR_Y, applyW, 14)) {
                tooltip = chosen.isCoreTrial()
                    ? List.of(new TextComponent("This is a trial of the " + chosen.core() + "."),
                        new TextComponent("§7Socket the core into the deck at an anvil first,"),
                        new TextComponent("§7then Rescan and pick the deck itself."))
                    : !this.problem.inBinder()
                    ? List.of(new TextComponent("Only the deck in the binder's deck slot can be rearranged."),
                        new TextComponent("§7Put this deck in that slot and Rescan."))
                    : !this.serverHasMod()
                    ? List.of(new TextComponent("The server does not have this mod installed."),
                        new TextComponent("§7Move the cards by hand, or add the mod to the server."))
                    : List.of(new TextComponent("Have the server move the cards into layout #" + this.selected),
                        new TextComponent("§7Cards come from the deck, the binder and your inventory."),
                        new TextComponent("§7Cards leaving the deck go into the binder."),
                        new TextComponent("§7Either everything moves or nothing does. Click twice to confirm."));
            }
            if (possible) {
                barX += this.button(pose, barX, BAR_Y, applyW, label, mouseX, mouseY, () -> this.apply(target)) + 8;
            } else {
                GuiComponent.fill(pose, barX, BAR_Y, barX + applyW, BAR_Y + 14, 0xFF2B2B33);
                font.draw(pose, label, barX + (applyW - font.width(label)) / 2.0F, BAR_Y + 3, 0x707070);
                barX += applyW + 8;
            }
        } else {
            barX += 4;
        }

        // Deck picker.
        font.draw(pose, "Deck", 8, DECK_Y, YELLOW);
        if (!this.choices.isEmpty()) {
            this.smallButton(pose, 34, DECK_Y - 1, "<", mouseX, mouseY, () -> this.pick(-1));
            this.smallButton(pose, 46, DECK_Y - 1, ">", mouseX, mouseY, () -> this.pick(1));
            VaultAdapter.DeckChoice chosen = this.choices.get(this.choice);
            String name = chosen.label() + "  §8" + (this.choice + 1) + "/" + this.choices.size();
            int colour = chosen.isPreview() ? 0x9FD0FF : chosen.isCoreTrial() ? TRIAL : WHITE;
            font.draw(pose, font.plainSubstrByWidth(name, width - 68), 60, DECK_Y, colour);
            if (inside(mouseX, mouseY, 8, DECK_Y - 1, LEFT_W + 60, 12)) {
                List<Component> lines = new ArrayList<>();
                lines.add(new TextComponent("Choose which deck to optimize"));
                lines.add(new TextComponent("§7Your own decks come first, each followed by a trial"));
                lines.add(new TextComponent("§7of every core in your inventory it could take (green)."));
                lines.add(new TextComponent("§7Then an empty deck of every type in the pack, to"));
                lines.add(new TextComponent("§7compare shapes (blue). Previews have no cores."));
                if (chosen.coreRollsSlots()) {
                    lines.add(new TextComponent("§6This core picks its slots at random when socketed."));
                    lines.add(new TextComponent("§6The trial used one roll, so the real deck may differ."));
                }
                lines.add(new TextComponent("§8Click < > or scroll here."));
                tooltip = lines;
            }
        }

        int bottom = height - 16;
        if (this.problem == null) {
            font.draw(pose, this.message == null ? "" : this.message, 8, TOP, YELLOW);
            if (tooltip != null) {
                screen.renderComponentTooltip(pose, tooltip, mouseX, mouseY);
            }
            return;
        }

        String pool = this.problem.totalCards() + " cards known (" + this.problem.kindCount() + " distinct), "
            + this.cache.size() + " from binder pages " + this.cache.pagesSeen() + "/" + VaultAdapter.binderPages(this.menu);
        if (this.notice != null) {
            font.draw(pose, font.plainSubstrByWidth(this.notice, Math.max(0, width - barX - 8)), barX, BAR_Y + 3, this.noticeGood ? GREEN : RED);
        } else {
            font.draw(pose, font.plainSubstrByWidth(pool, Math.max(0, width - barX - 8)), barX, BAR_Y + 3, GREY);
        }

        View current = this.views.get(0);
        View shown = this.views.get(Math.min(this.selected, this.views.size() - 1));
        double currentScore = this.scoring.score(current.totals);

        // Layout list.
        int x = 8;
        int y = TOP;
        font.draw(pose, "Layouts by weighted score", x, y, YELLOW);
        y += ROW + 1;
        for (int i = 0; i < this.views.size(); i++) {
            View view = this.views.get(i);
            boolean hover = inside(mouseX, mouseY, x, y, LEFT_W, ROW);
            if (i == this.selected || hover) {
                GuiComponent.fill(pose, x, y - 1, x + LEFT_W, y + ROW - 1, i == this.selected ? 0x50FFFFFF : 0x25FFFFFF);
            }
            double score = this.scoring.score(view.totals);
            font.draw(pose, i == 0 ? (this.problem.choice().isPreview() ? "Empty" : "Current") : "#" + i, x + 2, y, WHITE);
            String scoreText = number(score);
            font.draw(pose, scoreText, x + 86 - font.width(scoreText), y, WHITE);
            if (i > 0) {
                double gain = score - currentScore;
                font.draw(pose, (gain >= 0 ? "+" : "") + number(gain), x + 92, y, gain > 0 ? GREEN : gain < 0 ? RED : GREY);
                String moves = view.changes + (view.changes == 1 ? " slot" : " slots");
                font.draw(pose, moves, x + LEFT_W - 2 - font.width(moves), y, GREY);
            }
            final int index = i;
            this.regions.add(new Region(x, y - 1, LEFT_W, ROW, () -> this.selected = index));
            y += ROW;
        }
        if (this.run != null) {
            double progress = this.run.progress;
            GuiComponent.fill(pose, x, y + 1, x + LEFT_W, y + 9, 0xFF2B2B33);
            GuiComponent.fill(pose, x, y + 1, x + (int) (LEFT_W * Math.min(1.0, progress)), y + 9, 0xFF7C6FB0);
            font.draw(pose, "Searching " + (int) (progress * 100) + "%", x + 3, y + 1, WHITE);
            y += ROW;
        } else if (this.views.size() == 1) {
            font.draw(pose, "No better layout found.", x + 2, y, GREY);
            y += ROW;
        }
        if (this.error != null) {
            font.draw(pose, font.plainSubstrByWidth(this.error, width - 16), x, bottom - ROW, RED);
        }

        // Required cards.
        y += 4;
        List<Component> requiredTip = this.drawRequired(pose, font, x, y, mouseX, mouseY, shown);
        if (requiredTip != null) {
            tooltip = requiredTip;
        }
        y = this.requiredBottom;

        // Deck grid for the selected layout.
        y += 6;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (int slot = 0; slot < this.problem.slotCount(); slot++) {
            minX = Math.min(minX, this.problem.slotX(slot));
            minY = Math.min(minY, this.problem.slotY(slot));
        }
        int[] now = this.problem.current();
        for (int slot = 0; slot < this.problem.slotCount(); slot++) {
            int cx = x + (this.problem.slotX(slot) - minX) * CELL;
            int cy = y + (this.problem.slotY(slot) - minY) * CELL;
            int kind = shown.layout[slot];
            boolean changed = kind != now[slot];
            if (changed) {
                GuiComponent.fill(pose, cx - 1, cy - 1, cx + CELL - 1, cy + CELL - 1, 0xFF000000 | YELLOW);
            }
            GuiComponent.fill(pose, cx, cy, cx + CELL - 2, cy + CELL - 2, this.problem.slotArcane(slot) ? 0xFF3B2A55 : 0xFF2B2B33);
            boolean required = kind >= 0 && RequiredCards.count(this.problem.kindId(kind)) > 0;
            if (kind >= 0) {
                this.mc.getItemRenderer().renderAndDecorateItem(this.problem.kindStack(kind), cx, cy);
                final String id = this.problem.kindId(kind);
                this.regions.add(new Region(cx, cy, CELL - 2, CELL - 2, () -> this.toggleRequired(id)));
            }
            if (required) {
                // A gold corner marks a required card, drawn above the item.
                pose.pushPose();
                pose.translate(0.0, 0.0, 300.0);
                GuiComponent.fill(pose, cx + CELL - 6, cy, cx + CELL - 2, cy + 4, ClientHooks.REQUIRED_COLOUR);
                pose.popPose();
            }
            if (inside(mouseX, mouseY, cx, cy, CELL - 2, CELL - 2)) {
                tooltip = new ArrayList<>();
                if (kind >= 0) {
                    tooltip.addAll(screen.getTooltipFromItem(this.problem.kindStack(kind)));
                    tooltip.add(new TextComponent("§8Found in: " + this.problem.kindSources(kind)));
                    tooltip.add(new TextComponent(required ? "§6Required. Click to stop requiring it." : "§7Click to require this card in every layout."));
                } else {
                    tooltip.add(new TextComponent(this.problem.slotArcane(slot) ? "Empty arcane slot" : "Empty slot"));
                }
                if (changed) {
                    String was = now[slot] < 0 ? "empty" : this.problem.kindStack(now[slot]).getHoverName().getString();
                    tooltip.add(new TextComponent("§eChange: this slot currently holds " + was));
                }
            }
        }

        // Right-hand side.
        int tableX = x + LEFT_W + 8;
        List<Component> rightTip = this.statsView
            ? this.drawDeckStats(pose, font, tableX, width, bottom, current, shown)
            : this.drawAttributes(pose, font, tableX, width, bottom, mouseX, mouseY, current, shown);
        if (rightTip != null) {
            tooltip = rightTip;
        }

        // Bottom edge: a hint only, kept clear of the corners where other mods put buttons.
        String hint = this.editKey != null
            ? "Type a number. Enter saves, Esc cancels. An empty cap removes the cap."
            : "Suggestions only. Yellow = slot to change. Click a card to require it. Click a weight or cap to type it. Esc closes.";
        font.draw(pose, font.plainSubstrByWidth(hint, Math.max(0, width - 64)), 32, height - 12, GREY);

        if (tooltip != null) {
            screen.renderComponentTooltip(pose, tooltip, mouseX, mouseY);
        }
    }

    /** Where drawRequired finished, for the grid below it. */
    private int requiredBottom;

    /**
     * The required cards: one icon each with its count, - and + to change it.
     * Returns a tooltip when the mouse is over something, else null.
     */
    private List<Component> drawRequired(PoseStack pose, Font font, int x, int y, int mouseX, int mouseY, View shown) {
        List<Component> tooltip = null;
        int unknown = this.requiredUnknown();
        int missing = this.searched == null ? 0 : Annealer.missingRequired(this.searched, shown.layout);
        String head = "Required cards";
        font.draw(pose, head, x, y, YELLOW);
        int headW = font.width(head);
        if (missing > 0 && shown != this.views.get(0)) {
            font.draw(pose, missing + " did not fit", x + headW + 6, y, RED);
        } else if (unknown > 0) {
            font.draw(pose, unknown + " not found", x + headW + 6, y, ORANGE);
        }
        if (inside(mouseX, mouseY, x, y - 1, LEFT_W, ROW)) {
            tooltip = List.of(new TextComponent("Cards every suggested layout must hold"),
                new TextComponent("§7Alt+click a card in the binder or your inventory,"),
                new TextComponent("§7or click one in the grid below. Then Re-run."),
                new TextComponent("§7Use - and + for more than one copy."),
                new TextComponent("§7\"Did not fit\": no free slot of the right kind (arcane)."),
                new TextComponent("§7\"Not found\": not in a deck, a binder page you have"),
                new TextComponent("§7seen, or your inventory."));
        }
        y += ROW + 1;
        int column = 0;
        boolean any = false;
        for (Map.Entry<String, Integer> entry : RequiredCards.all().entrySet()) {
            int kind = this.problem.kindOf(entry.getKey());
            if (kind < 0) {
                continue;
            }
            any = true;
            int cx = x + column * 42;
            this.mc.getItemRenderer().renderAndDecorateItem(this.problem.kindStack(kind), cx, y);
            final String id = entry.getKey();
            String count = "x" + entry.getValue();
            font.draw(pose, count, cx + 17, y + 1, entry.getValue() > this.problem.available(kind) ? RED : WHITE);
            this.smallButton(pose, cx + 17, y + 9, "-", mouseX, mouseY, () -> this.adjustRequired(id, -1));
            this.smallButton(pose, cx + 28, y + 9, "+", mouseX, mouseY, () -> this.adjustRequired(id, 1));
            if (inside(mouseX, mouseY, cx, y, 16, 16)) {
                List<Component> lines = new ArrayList<>(this.mc.screen == null ? List.of()
                    : this.mc.screen.getTooltipFromItem(this.problem.kindStack(kind)));
                lines.add(new TextComponent("§6Required: " + entry.getValue() + " of " + this.problem.available(kind) + " known"));
                tooltip = lines;
            }
            column++;
            if ((column + 1) * 42 > LEFT_W) {
                column = 0;
                y += CELL + 2;
            }
        }
        if (!any) {
            font.draw(pose, "None. Alt+click cards to add.", x + 2, y, GREY);
            y += ROW;
        } else if (column > 0) {
            y += CELL + 2;
        }
        this.requiredBottom = y;
        return tooltip;
    }

    /** The attribute table: name, weight (- typed +), cap (typed), and totals. Returns a tooltip or null. */
    private List<Component> drawAttributes(PoseStack pose, Font font, int tableX, int width, int bottom, int mouseX, int mouseY,
                                           View current, View shown) {
        List<Component> tooltip = null;
        int nameW = Math.max(70, Math.min(150, width - tableX - 8 - 56 - 46 - 100));
        int weightX = tableX + nameW + 4;
        int capX = weightX + 58;
        int valueX = capX + 46;
        int tableY = TOP;
        font.draw(pose, "Attribute", tableX, tableY, YELLOW);
        font.draw(pose, "Weight", weightX + 8, tableY, YELLOW);
        font.draw(pose, "Cap", capX + 2, tableY, YELLOW);
        font.draw(pose, shown == current ? "Current" : "Current > selected", valueX, tableY, YELLOW);
        if (inside(mouseX, mouseY, capX, tableY - 1, 44, ROW)) {
            tooltip = List.of(new TextComponent("The largest total that still counts"),
                new TextComponent("§7Click a row's cap to type one, in the same units"),
                new TextComponent("§7as the totals beside it. Anything above the cap"),
                new TextComponent("§7scores nothing, so the search stops stacking it."),
                new TextComponent("§7Leave it empty for no cap. An orange * means"),
                new TextComponent("§7the selected layout is over the cap."));
        }
        int rowsY = tableY + ROW + 1;
        int visible = Math.max(1, (bottom - 2 - rowsY) / ROW);
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.attributeOrder.size() - visible)));
        List<String> keys = this.problem.attributeKeys();
        for (int row = 0; row < visible && row + this.scroll < this.attributeOrder.size(); row++) {
            int attribute = this.attributeOrder.get(row + this.scroll);
            String key = keys.get(attribute);
            int ry = rowsY + row * ROW;
            boolean hover = inside(mouseX, mouseY, tableX, ry - 1, width - 8 - tableX, ROW);
            if (hover) {
                GuiComponent.fill(pose, tableX - 2, ry - 1, width - 8, ry + ROW - 1, 0x25FFFFFF);
            }
            double weight = this.weights.get(key);
            font.draw(pose, font.plainSubstrByWidth(pretty(key), nameW), tableX, ry, weight == 0.0 ? GREY : WHITE);

            this.smallButton(pose, weightX, ry - 1, "-", mouseX, mouseY, () -> this.nudge(key, -1));
            boolean editingWeight = key.equals(this.editKey) && !this.editCap;
            this.editableCell(pose, font, weightX + 11, ry, 32, editingWeight, number(weight), weight == 0.0 ? GREY : WHITE,
                () -> this.startEdit(key, false));
            this.smallButton(pose, weightX + 44, ry - 1, "+", mouseX, mouseY, () -> this.nudge(key, 1));

            Double cap = this.weights.cap(key);
            boolean editingCap = key.equals(this.editKey) && this.editCap;
            this.editableCell(pose, font, capX, ry, 42, editingCap, cap == null ? "-" : number(cap), cap == null ? GREY : WHITE,
                () -> this.startEdit(key, true));

            double before = current.totals[attribute];
            double after = shown.totals[attribute];
            boolean over = this.scoring.overCap(attribute, after);
            String values = shown == current ? number(before) : number(before) + " > " + number(after);
            font.draw(pose, values + (over ? "*" : ""), valueX, ry, over ? ORANGE : after > before ? GREEN : after < before ? RED : GREY);

            if (hover && !inside(mouseX, mouseY, weightX, ry - 1, 104, ROW)) {
                tooltip = new ArrayList<>();
                tooltip.add(new TextComponent(pretty(key)));
                tooltip.add(new TextComponent("§8" + key));
                if (Weights.CORE_CONDITIONS.equals(key)) {
                    tooltip.add(new TextComponent("§7How many of the deck's cores have their condition met,"));
                    tooltip.add(new TextComponent("§7for example a Bounty core's resource cards. Raise the"));
                    tooltip.add(new TextComponent("§7weight until the search gives up cards to meet it."));
                }
                tooltip.add(new TextComponent("§7Current: §f" + current.display.getOrDefault(key, number(before))));
                if (shown != current) {
                    tooltip.add(new TextComponent("§7Selected: §f" + shown.display.getOrDefault(key, number(after))));
                }
                if (cap != null) {
                    tooltip.add(new TextComponent("§7Cap: §f" + number(cap) + (over ? " §6(the selected layout is over it; only "
                        + number(cap) + " counts)" : "")));
                }
                tooltip.add(new TextComponent("§8Score = weight x " + (cap == null ? "total" : "min(total, cap)") + " / "
                    + number(this.problem.norms()[attribute]) + " (the best single card)"));
            }
        }
        if (this.attributeOrder.size() > visible) {
            String more = (this.scroll + 1) + "-" + Math.min(this.attributeOrder.size(), this.scroll + visible)
                + " of " + this.attributeOrder.size() + " (scroll)";
            font.draw(pose, more, width - 8 - font.width(more), DECK_Y, GREY);
        }
        return tooltip;
    }

    /** A number that turns into a text box when clicked. */
    private void editableCell(PoseStack pose, Font font, int x, int y, int w, boolean editing, String text, int colour, Runnable onClick) {
        if (editing) {
            GuiComponent.fill(pose, x, y - 2, x + w, y + 9, 0xFF000000);
            GuiComponent.fill(pose, x, y - 2, x + w, y - 1, 0xFF7C6FB0);
            GuiComponent.fill(pose, x, y + 8, x + w, y + 9, 0xFF7C6FB0);
            String shown = this.editText + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
            font.draw(pose, font.plainSubstrByWidth(shown, w - 2), x + 2, y, WHITE);
        } else {
            font.draw(pose, text, x + (w - font.width(text)) / 2.0F, y, colour);
            this.regions.add(new Region(x, y - 1, w, ROW, onClick));
        }
    }

    /**
     * The deck's own tooltip text for the current deck and the selected layout,
     * side by side, as the game writes it. Returns a tooltip or null.
     */
    private List<Component> drawDeckStats(PoseStack pose, Font font, int tableX, int width, int bottom, View current, View shown) {
        int columnW = Math.max(60, (width - 8 - tableX - 8) / 2);
        int rightX = tableX + columnW + 8;
        font.draw(pose, this.problem.choice().isPreview() ? "Empty deck" : "Current deck", tableX, TOP, YELLOW);
        font.draw(pose, shown == current ? "(select a layout)" : "Layout #" + this.selected, rightX, TOP, YELLOW);
        List<FormattedCharSequence> left = wrap(font, current.deckText, columnW);
        List<FormattedCharSequence> right = shown == current ? List.of() : wrap(font, shown.deckText, columnW);
        int rowsY = TOP + ROW + 1;
        int visible = Math.max(1, (bottom - 2 - rowsY) / TEXT_ROW);
        int lines = Math.max(left.size(), right.size());
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, lines - visible)));
        for (int row = 0; row < visible; row++) {
            int line = row + this.scroll;
            int ry = rowsY + row * TEXT_ROW;
            if (line < left.size()) {
                font.draw(pose, left.get(line), tableX, ry, WHITE);
            }
            if (line < right.size()) {
                font.draw(pose, right.get(line), rightX, ry, WHITE);
            }
        }
        if (lines > visible) {
            String more = (this.scroll + 1) + "-" + Math.min(lines, this.scroll + visible) + " of " + lines + " lines (scroll)";
            font.draw(pose, more, width - 8 - font.width(more), DECK_Y, GREY);
        }
        return null;
    }

    private static List<FormattedCharSequence> wrap(Font font, List<Component> text, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component line : text) {
            out.addAll(font.split(line, width));
        }
        return out;
    }

    private int button(PoseStack pose, int x, int y, int w, String label, int mouseX, int mouseY, Runnable action) {
        boolean hover = inside(mouseX, mouseY, x, y, w, 14);
        GuiComponent.fill(pose, x, y, x + w, y + 14, 0xFF000000);
        GuiComponent.fill(pose, x + 1, y + 1, x + w - 1, y + 13, hover ? 0xFF7C6FB0 : 0xFF4A4A58);
        this.mc.font.draw(pose, label, x + (w - this.mc.font.width(label)) / 2.0F, y + 3, WHITE);
        this.regions.add(new Region(x, y, w, 14, action));
        return w;
    }

    private void smallButton(PoseStack pose, int x, int y, String label, int mouseX, int mouseY, Runnable action) {
        boolean hover = inside(mouseX, mouseY, x, y, 10, 10);
        GuiComponent.fill(pose, x, y, x + 10, y + 10, hover ? 0xFF7C6FB0 : 0xFF4A4A58);
        this.mc.font.draw(pose, label, x + (10 - this.mc.font.width(label)) / 2.0F, y + 1, WHITE);
        this.regions.add(new Region(x, y, 10, 10, action));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** "the_vault:attack_damage" becomes "Attack Damage". */
    static String pretty(String key) {
        if (Weights.NON_STAT_CARDS.equals(key)) {
            return "Cards With No Stat";
        }
        if (Weights.CORE_CONDITIONS.equals(key)) {
            return "Core Conditions Met";
        }
        String path = key.substring(key.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : path.split(" ")) {
            if (!word.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    static String number(double value) {
        double size = Math.abs(value);
        String text = size >= 100.0 ? String.format(Locale.ROOT, "%.0f", value)
            : size >= 10.0 ? String.format(Locale.ROOT, "%.1f", value)
            : String.format(Locale.ROOT, "%.3f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.equals("-0") ? "0" : text;
    }
}
