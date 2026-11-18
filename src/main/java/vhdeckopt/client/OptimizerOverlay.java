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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import vhdeckopt.Network;
import vhdeckopt.Weights;
import vhdeckopt.search.Annealer;
import vhdeckopt.search.Problem;
import vhdeckopt.vault.VaultAdapter;

/**
 * The optimizer panel drawn over the Card Binder: a list of the best layouts
 * found, the deck grid for the selected one, and every attribute with its
 * weight and its total before and after. Immediate-mode: each frame redraws
 * everything and records the clickable regions.
 */
final class OptimizerOverlay {
    private static final int WHITE = 0xFFFFFF;
    private static final int GREY = 0xA0A0A0;
    private static final int YELLOW = 0xFFD040;
    private static final int GREEN = 0x60E060;
    private static final int RED = 0xFF6060;
    private static final int CELL = 18;
    private static final int ROW = 11;
    private static final int LEFT_W = 170;
    private static final int MAX_LAYOUTS = 5;
    private static final int BAR_Y = 4;
    private static final int DECK_Y = 23;
    private static final int TOP = 38;

    /** One layout shown in the list. Index 0 is always the deck as it is now. */
    private static final class View {
        final int[] layout;
        final double[] totals;
        final Map<String, String> display;
        final int changes;

        View(int[] layout, double[] totals, Map<String, String> display, int changes) {
            this.layout = layout;
            this.totals = totals;
            this.display = display;
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
    private AbstractContainerMenu menu;
    private List<VaultAdapter.DeckChoice> choices = List.of();
    private int choice;
    private VaultAdapter.DeckProblem problem;
    private VaultAdapter.Evaluator evaluator;
    private List<Integer> attributeOrder = List.of();
    private String message;
    private int selected;
    private int scroll;
    private boolean weightsChanged;
    /** Apply is a two-click action: the first click arms it, the second sends. */
    private boolean applyArmed;
    private boolean applying;
    private String notice;
    private boolean noticeGood;
    private int rescanIn = -1;
    private boolean closeRequested;

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
        this.views.clear();
        this.problem = null;
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
        return new View(layout.clone(), this.evaluator.totals(layout), this.evaluator.display(layout), changes);
    }

    private double[] scaledWeights() {
        List<String> keys = this.problem.attributeKeys();
        double[] norms = this.problem.norms();
        double[] scaled = new double[keys.size()];
        for (int i = 0; i < scaled.length; i++) {
            scaled[i] = this.weights.get(keys.get(i)) / norms[i];
        }
        return scaled;
    }

    private static double dot(double[] scaledWeights, double[] totals) {
        double sum = 0.0;
        for (int i = 0; i < totals.length; i++) {
            sum += scaledWeights[i] * totals[i];
        }
        return sum;
    }

    private void search() {
        this.stopSearch();
        final Run run = new Run();
        this.run = run;
        this.error = null;
        this.weightsChanged = false;
        while (this.views.size() > 1) {
            this.views.remove(this.views.size() - 1);
        }
        this.selected = 0;

        final VaultAdapter.DeckProblem deck = this.problem;
        final VaultAdapter.Evaluator worker = deck.newEvaluator();
        final double[] scaled = this.scaledWeights();
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

            public int[] current() {
                return deck.current();
            }

            public double score(int[] layout) {
                return dot(scaled, worker.totals(layout));
            }
        };
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
        double[] scaled = this.scaledWeights();
        List<View> found = new ArrayList<>(this.views.subList(1, this.views.size()));
        found.sort((a, b) -> Double.compare(dot(scaled, b.totals), dot(scaled, a.totals)));
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
        for (int i = this.regions.size() - 1; i >= 0; i--) {
            Region region = this.regions.get(i);
            if (mouseX >= region.x && mouseX < region.x + region.w && mouseY >= region.y && mouseY < region.y + region.h) {
                click();
                boolean wasArmed = this.applyArmed;
                if (!this.applying) {
                    this.notice = null;
                }
                region.action.run();
                if (wasArmed) {
                    this.applyArmed = false;
                }
                break;
            }
        }
        return this.closeRequested;
    }

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
                barX += this.button(pose, barX, BAR_Y, this.weightsChanged ? 104 : 50, this.weightsChanged ? "Re-run (new weights)" : "Re-run",
                    mouseX, mouseY, this::search) + 4;
            }
        }
        if (inside(mouseX, mouseY, barX, BAR_Y, 50, 14)) {
            tooltip = List.of(new TextComponent("Read the decks, inventory and binder cards again"),
                new TextComponent("\u00a77Flip through the binder's pages first; only"),
                new TextComponent("\u00a77pages you have looked at are known."));
        }
        barX += this.button(pose, barX, BAR_Y, 50, "Rescan", mouseX, mouseY, () -> this.start(this.menu)) + 4;
        barX += this.button(pose, barX, BAR_Y, 50, "Close", mouseX, mouseY, () -> this.closeRequested = true) + 4;

        // Apply: only for a suggestion for the deck in the binder, and only if the server has this mod too.
        if (this.problem != null && this.run == null && !this.applying && this.selected > 0 && this.selected < this.views.size()) {
            View target = this.views.get(this.selected);
            boolean possible = this.problem.inBinder() && this.serverHasMod();
            String label = !possible ? "Apply" : this.applyArmed ? "Confirm: move " + target.changes + " slots" : "Apply #" + this.selected;
            int applyW = Math.max(60, font.width(label) + 12);
            if (inside(mouseX, mouseY, barX, BAR_Y, applyW, 14)) {
                tooltip = !this.problem.inBinder()
                    ? List.of(new TextComponent("Only the deck in the binder's deck slot can be rearranged."),
                        new TextComponent("\u00a77Put this deck in that slot and Rescan."))
                    : !this.serverHasMod()
                    ? List.of(new TextComponent("The server does not have this mod installed."),
                        new TextComponent("\u00a77Move the cards by hand, or add the mod to the server."))
                    : List.of(new TextComponent("Have the server move the cards into layout #" + this.selected),
                        new TextComponent("\u00a77Cards come from the deck, the binder and your inventory."),
                        new TextComponent("\u00a77Cards leaving the deck go into the binder."),
                        new TextComponent("\u00a77Either everything moves or nothing does. Click twice to confirm."));
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
            String name = chosen.label() + "  \u00a78" + (this.choice + 1) + "/" + this.choices.size();
            font.draw(pose, font.plainSubstrByWidth(name, width - 68), 60, DECK_Y, chosen.isPreview() ? 0x9FD0FF : WHITE);
            if (inside(mouseX, mouseY, 8, DECK_Y - 1, LEFT_W + 60, 12)) {
                tooltip = List.of(new TextComponent("Choose which deck to optimize"),
                    new TextComponent("\u00a77Your own decks come first, then an empty deck of"),
                    new TextComponent("\u00a77every type in the pack, to compare shapes."),
                    new TextComponent("\u00a77Previews have no deck modifiers."),
                    new TextComponent("\u00a78Click < > or scroll here."));
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

        double[] scaled = this.scaledWeights();
        View current = this.views.get(0);
        View shown = this.views.get(Math.min(this.selected, this.views.size() - 1));
        double currentScore = dot(scaled, current.totals);

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
            double score = dot(scaled, view.totals);
            font.draw(pose, i == 0 ? (this.choices.get(this.choice).isPreview() ? "Empty" : "Current") : "#" + i, x + 2, y, WHITE);
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
            if (kind >= 0) {
                this.mc.getItemRenderer().renderAndDecorateItem(this.problem.kindStack(kind), cx, cy);
            }
            if (inside(mouseX, mouseY, cx, cy, CELL - 2, CELL - 2)) {
                tooltip = new ArrayList<>();
                if (kind >= 0) {
                    tooltip.addAll(screen.getTooltipFromItem(this.problem.kindStack(kind)));
                    tooltip.add(new TextComponent("§8Found in: " + this.problem.kindSources(kind)));
                } else {
                    tooltip.add(new TextComponent(this.problem.slotArcane(slot) ? "Empty arcane slot" : "Empty slot"));
                }
                if (changed) {
                    String was = now[slot] < 0 ? "empty" : this.problem.kindStack(now[slot]).getHoverName().getString();
                    tooltip.add(new TextComponent("§eChange: this slot currently holds " + was));
                }
            }
        }

        // Attribute table.
        int tableX = x + LEFT_W + 8;
        int nameW = Math.max(70, Math.min(150, width - tableX - 8 - 56 - 100));
        int weightX = tableX + nameW + 4;
        int valueX = weightX + 56;
        int tableY = TOP;
        font.draw(pose, "Attribute", tableX, tableY, YELLOW);
        font.draw(pose, "Weight", weightX + 8, tableY, YELLOW);
        font.draw(pose, shown == current ? "Current" : "Current > selected", valueX, tableY, YELLOW);
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
            String weightText = number(weight);
            font.draw(pose, weightText, weightX + 27 - font.width(weightText) / 2.0F, ry, weight == 0.0 ? GREY : WHITE);
            this.smallButton(pose, weightX + 44, ry - 1, "+", mouseX, mouseY, () -> this.nudge(key, 1));

            double before = current.totals[attribute];
            double after = shown.totals[attribute];
            String values = shown == current ? number(before) : number(before) + " > " + number(after);
            font.draw(pose, values, valueX, ry, after > before ? GREEN : after < before ? RED : GREY);

            if (hover && !inside(mouseX, mouseY, weightX, ry - 1, 54, ROW)) {
                tooltip = new ArrayList<>();
                tooltip.add(new TextComponent(pretty(key)));
                tooltip.add(new TextComponent("§8" + key));
                tooltip.add(new TextComponent("§7Current: §f" + current.display.getOrDefault(key, number(before))));
                if (shown != current) {
                    tooltip.add(new TextComponent("§7Selected: §f" + shown.display.getOrDefault(key, number(after))));
                }
                tooltip.add(new TextComponent("§8Score = weight x total / " + number(this.problem.norms()[attribute])
                    + " (the best single card)"));
            }
        }
        if (this.attributeOrder.size() > visible) {
            String more = (this.scroll + 1) + "-" + Math.min(this.attributeOrder.size(), this.scroll + visible)
                + " of " + this.attributeOrder.size() + " (scroll)";
            font.draw(pose, more, width - 8 - font.width(more), DECK_Y, GREY);
        }

        // Bottom edge: a hint only, kept clear of the corners where other mods put buttons.
        String hint = "Suggestions only. Yellow = slot to change. Shift-click +/- for bigger steps. Esc closes.";
        font.draw(pose, font.plainSubstrByWidth(hint, Math.max(0, width - 64)), 32, height - 12, GREY);

        if (tooltip != null) {
            screen.renderComponentTooltip(pose, tooltip, mouseX, mouseY);
        }
    }

    private void nudge(String key, int direction) {
        double step = Screen.hasShiftDown() ? 1.0 : 0.25;
        this.weights.set(key, this.weights.get(key) + direction * step);
        this.weights.save(this.configFile);
        this.weightsChanged = true;
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
