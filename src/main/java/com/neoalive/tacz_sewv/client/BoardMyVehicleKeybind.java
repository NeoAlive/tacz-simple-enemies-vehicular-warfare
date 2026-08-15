package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketClearBoarding;

/**
 * "Board my vehicle" — the player's own signal, from inside the seat they picked, that a squad
 * ordered aboard as passengers may now pile in. See {@link com.neoalive.tacz_sewv.entity.ai.goal.BoardVehicleGoal}
 * for the wait this releases.
 *
 * <p>Read the same way {@link TdtKeybind} reads {@code OPEN_TDT} — polled from the client tick
 * rather than an input event — because SuperbWarfare cancels the raw mouse/keyboard event for
 * anyone in a weapon-bearing seat (see that class's doc), and a seat is exactly where this key
 * has to work.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class BoardMyVehicleKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /** Default <b>B</b>: unbound in vanilla and unclaimed by SuperbWarfare or FCP. */
    public static final KeyMapping BOARD_MY_VEHICLE = new KeyMapping(
            "key." + TaczSewv.MODID + ".board_my_vehicle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY);

    private BoardMyVehicleKeybind() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean pressed = false;
        while (BOARD_MY_VEHICLE.consumeClick()) {
            pressed = true;
        }
        if (!pressed || mc.screen != null) return;

        NetworkHandler.CHANNEL.sendToServer(new PacketClearBoarding());
    }
}
