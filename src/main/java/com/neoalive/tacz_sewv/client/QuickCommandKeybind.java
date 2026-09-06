package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.radial.QuickCommandWheelScreen;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/**
 * Hold-to-open quick-command wheel. Polled from {@link TickEvent.ClientTickEvent} like
 * {@link TdtKeybind} so it still works in an armed vehicle seat (SBW cancels raw mouse Pre).
 *
 * <p><b>Hold must read GLFW, not {@link KeyMapping#isDown()}.</b> {@link KeyConflictContext#IN_GAME}
 * (correct while closed — do not steal keys from GUIs) reports {@code isDown() == false} the
 * instant a Screen opens, which made the wheel flash shut on the next tick. While the wheel is
 * up we poll the bound key via GLFW so release still closes it.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class QuickCommandKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /**
     * Default comma — clear of SBW's WASD/shift/ctrl/R/N/X/V/C/K/H set and of the TDT's G.
     */
    public static final KeyMapping OPEN_WHEEL = new KeyMapping(
            "key." + TaczSewv.MODID + ".quick_command_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_COMMA,
            CATEGORY);

    private static boolean wasDown;

    private QuickCommandKeybind() {}

    /** Hub unicode sits on the crosshair — hide the vanilla reticle while the wheel is up. */
    @SubscribeEvent
    public static void hideCrosshair(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        if (Minecraft.getInstance().screen instanceof QuickCommandWheelScreen) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            wasDown = false;
            return;
        }

        // While the wheel Screen is open, IN_GAME KeyMapping.isDown is always false — use GLFW.
        boolean down = mc.screen instanceof QuickCommandWheelScreen
                ? isBoundKeyPhysicallyDown(mc)
                : OPEN_WHEEL.isDown();
        boolean rising = down && !wasDown;
        wasDown = down;

        if (mc.screen instanceof QuickCommandWheelScreen) {
            // Release is owned by the screen (hard override before click commit).
            return;
        }

        if (!rising) return;
        if (mc.screen != null) return;

        if (!hasTerminal(mc.player)) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.hold_terminal")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }

        if (QuickCommandRegistry.rootWedges().isEmpty()) {
            QuickCommandRegistry.init();
        }
        mc.setScreen(new QuickCommandWheelScreen());
    }

    /** Whether the bound wheel key is physically held (Screen-safe). */
    public static boolean isBoundKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = OPEN_WHEEL.getKey();
        long window = mc.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return OPEN_WHEEL.isDown();
    }

    /** TDT anywhere in the player's inventory (including offhand / hotbar), not only held. */
    public static boolean hasTerminal(Player player) {
        return com.neoalive.tacz_sewv.item.TacticalTerminal.hasInInventory(player);
    }

    /** @deprecated use {@link #hasTerminal} */
    public static boolean holdingTerminal(Player player) {
        return hasTerminal(player);
    }
}
