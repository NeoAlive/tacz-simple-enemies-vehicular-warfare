package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Opens the Tactical Data Terminal from a key, so it can be reached <b>from inside a vehicle</b>.
 *
 * <h2>Why this exists at all</h2>
 * The terminal's original (and still working) trigger is a left click with it in hand, handled in
 * {@link ClientEvents}. That path is dead the moment the player sits in a vehicle seat:
 * SuperbWarfare's {@code ClickEventHandler.onButtonPressed} cancels the raw
 * {@code InputEvent.MouseButton.Pre} whenever {@code vehicle.banHand(player)} — which is true for
 * <em>any</em> seat carrying a weapon. Cancelling the raw event means vanilla's
 * {@code KeyMapping.click} never runs, so {@code keyAttack.consumeClick()} is false,
 * {@code Minecraft.startAttack()} never runs, and {@code InteractionKeyMappingTriggered} — the
 * only thing {@link ClientEvents} listens for — never fires. Nothing is broken on our side; the
 * click genuinely never happens. Which is unfortunate, because sitting in a tank is exactly when
 * you most want to give orders.
 *
 * <h2>Why a tick poll and not an input event</h2>
 * SuperbWarfare has three separate input guards, and this deliberately sidesteps all of them: the
 * mouse-button cancel above, a {@code KeyMapping.consumeClick} mixin, and a
 * {@code Minecraft.handleKeybinds} cancel. The latter two are scoped to the <b>hotbar number
 * keys</b>, so they cannot touch a binding of ours — but only if we read it somewhere other than
 * inside {@code handleKeybinds}. Polling {@code consumeClick} from the client tick does exactly
 * that, and it is also how the one other keybind in this workspace (FCP's debug renderer) is read.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class TdtKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /**
     * Default <b>G</b>: unbound in vanilla, unbound in SuperbWarfare (which claims WASD, space,
     * both shifts/controls, R, N, X, V, C, K, H, the arrow keys, PgUp/PgDn and the mouse), and
     * clear of FCP's F6. Close enough to the movement keys to reach without letting go of them.
     */
    public static final KeyMapping OPEN_TDT = new KeyMapping(
            "key." + TaczSewv.MODID + ".open_tdt",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private TdtKeybind() {}

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_TDT);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // consumeClick drains a queue of presses, so loop it — but the screen only needs opening
        // once however many are pending.
        boolean pressed = false;
        while (OPEN_TDT.consumeClick()) {
            pressed = true;
        }
        if (!pressed) return;

        // A screen is already up (very likely the terminal itself, from holding the key down).
        // Opening over it would re-snapshot the crosshair against the GUI rather than the world.
        if (mc.screen != null) return;

        if (!carriesTerminal(mc.player)) {
            // Otherwise this is indistinguishable from "wrong key" — the player has no way to
            // tell the binding fired at all.
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.no_terminal").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }

        TdtScreen.open();
    }

    /**
     * Whether the terminal is anywhere on the player — not just in hand.
     *
     * <p>Requiring it in hand would hand back the problem this class solves: a driver is holding a
     * weapon or nothing, and telling them to scroll to the terminal first is the same friction as
     * telling them to get out of the tank.
     *
     * <p>Iterating the {@link Inventory} as a {@code Container} covers the hotbar, main inventory,
     * armour and offhand in one pass, and {@code ItemStack.is(Item)} compares the item alone — so a
     * renamed or otherwise NBT-tagged terminal still counts, which {@code Inventory.contains(ItemStack)}
     * (it compares tags) would silently reject.
     */
    private static boolean carriesTerminal(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(ModItems.TACTICAL_DATA_TERMINAL.get())) return true;
        }
        return false;
    }
}
