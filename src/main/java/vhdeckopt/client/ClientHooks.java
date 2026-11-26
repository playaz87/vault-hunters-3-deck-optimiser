package vhdeckopt.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vhdeckopt.vault.VaultAdapter;

/**
 * Hooks the Card Binder screen from outside: draws one extra button on it,
 * outlines required cards and lets Alt+click mark them, and, while the
 * optimizer is open, draws that instead of the binder and swallows input. The
 * binder container itself stays open and untouched. The only thing ever sent
 * to the server is the Apply request, and only when the player confirms it.
 */
public final class ClientHooks {
    private static final int BUTTON_X = 227;
    private static final int BUTTON_Y = 41;
    private static final int BUTTON_W = 28;
    private static final int BUTTON_H = 13;
    private static final int KEY_ESCAPE = 256;
    static final int REQUIRED_COLOUR = 0xFFFFC000;

    private static final BinderCache CACHE = new BinderCache();
    private static OptimizerOverlay overlay;

    private ClientHooks() {
    }

    private static AbstractContainerScreen<?> binder(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> container && VaultAdapter.isBinder(container.getMenu()) ? container : null;
    }

    private static void closeOverlay() {
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AbstractContainerScreen<?> screen = binder(Minecraft.getInstance().screen);
        if (screen == null) {
            closeOverlay();
            return;
        }
        CACHE.observe(screen.getMenu());
        if (overlay != null) {
            overlay.tick();
        }
    }

    /** Called when the server answers an Apply request. */
    public static void applyResult(boolean applied, String text) {
        if (overlay != null) {
            overlay.applyResult(applied, text);
        }
    }

    @SubscribeEvent
    public static void onDrawPre(ScreenEvent.DrawScreenEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDrawPost(ScreenEvent.DrawScreenEvent.Post event) {
        AbstractContainerScreen<?> screen = binder(event.getScreen());
        if (screen == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        if (overlay != null) {
            overlay.render(pose, screen, event.getMouseX(), event.getMouseY());
            return;
        }
        drawRequiredOutlines(pose, screen);
        int x = screen.getGuiLeft() + BUTTON_X;
        int y = screen.getGuiTop() + BUTTON_Y;
        boolean hover = overButton(screen, event.getMouseX(), event.getMouseY());
        GuiComponent.fill(pose, x, y, x + BUTTON_W, y + BUTTON_H, 0xFF000000);
        GuiComponent.fill(pose, x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1, hover ? 0xFF7C6FB0 : 0xFF4A4A58);
        Minecraft mc = Minecraft.getInstance();
        String label = "Best";
        mc.font.draw(pose, label, x + (BUTTON_W - mc.font.width(label)) / 2.0F, y + 3, 0xFFFFFF);
        if (hover) {
            List<Component> lines = List.of(
                new TextComponent("Find the best card layout"),
                new TextComponent("§7Shows suggestions only; moves nothing."),
                new TextComponent("§7Alt+click a card to require it in every layout"),
                new TextComponent("§7(gold outline). " + RequiredCards.size() + " required now."));
            screen.renderComponentTooltip(pose, lines, event.getMouseX(), event.getMouseY());
        }
    }

    /** A gold outline around every required card on screen, drawn above the items. */
    private static void drawRequiredOutlines(PoseStack pose, AbstractContainerScreen<?> screen) {
        pose.pushPose();
        pose.translate(0.0, 0.0, 300.0);
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive() && RequiredCards.isRequired(slot.getItem())) {
                int x = screen.getGuiLeft() + slot.x;
                int y = screen.getGuiTop() + slot.y;
                GuiComponent.fill(pose, x - 1, y - 1, x + 17, y, REQUIRED_COLOUR);
                GuiComponent.fill(pose, x - 1, y + 16, x + 17, y + 17, REQUIRED_COLOUR);
                GuiComponent.fill(pose, x - 1, y, x, y + 16, REQUIRED_COLOUR);
                GuiComponent.fill(pose, x + 16, y, x + 17, y + 16, REQUIRED_COLOUR);
            }
        }
        pose.popPose();
    }

    /** The shown slot under the mouse, or null. */
    private static Slot slotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        for (Slot slot : screen.getMenu().slots) {
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            if (slot.isActive() && mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slot;
            }
        }
        return null;
    }

    private static boolean overButton(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        int x = screen.getGuiLeft() + BUTTON_X;
        int y = screen.getGuiTop() + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @SubscribeEvent
    public static void onMouseClicked(ScreenEvent.MouseClickedEvent.Pre event) {
        AbstractContainerScreen<?> screen = binder(event.getScreen());
        if (screen == null) {
            return;
        }
        if (overlay != null) {
            if (overlay.mouseClicked(event.getMouseX(), event.getMouseY())) {
                closeOverlay();
            }
            event.setCanceled(true);
        } else if (event.getButton() == 0 && overButton(screen, event.getMouseX(), event.getMouseY())) {
            OptimizerOverlay.click();
            overlay = new OptimizerOverlay(CACHE);
            overlay.start(screen.getMenu());
            event.setCanceled(true);
        } else if (event.getButton() == 0 && Screen.hasAltDown()) {
            // Alt+click marks a card as required; the click never reaches the binder.
            Slot slot = slotAt(screen, event.getMouseX(), event.getMouseY());
            String id = slot == null ? null : RequiredCards.id(slot.getItem());
            if (id != null) {
                OptimizerOverlay.click();
                RequiredCards.toggle(id);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseReleasedEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDrag(ScreenEvent.MouseDragEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrollEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            overlay.scroll(event.getScrollDelta(), event.getMouseX(), event.getMouseY());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyboardKeyPressedEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            // While a number is being typed, the overlay takes the keys (Escape cancels the edit).
            if (!overlay.keyPressed(event.getKeyCode()) && event.getKeyCode() == KEY_ESCAPE) {
                closeOverlay();
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyReleased(ScreenEvent.KeyboardKeyReleasedEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.KeyboardCharTypedEvent.Pre event) {
        if (overlay != null && binder(event.getScreen()) != null) {
            overlay.charTyped(event.getCodePoint());
            event.setCanceled(true);
        }
    }
}
