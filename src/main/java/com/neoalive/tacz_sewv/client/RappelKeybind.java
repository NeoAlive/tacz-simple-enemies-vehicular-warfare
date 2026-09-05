package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPlayerCrewRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappelLock;

/**
 * In-vehicle rappel keybinds — polled from the client tick because SuperbWarfare cancels
 * seat input events (see {@link TdtKeybind}).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RappelKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /** Unbound by default — set in Controls to avoid fighting SBW's busy vehicle map. */
    public static final KeyMapping SELF_RAPPEL = new KeyMapping(
            "key." + TaczSewv.MODID + ".player_self_rappel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    public static final KeyMapping CREW_RAPPEL = new KeyMapping(
            "key." + TaczSewv.MODID + ".player_crew_rappel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    private RappelKeybind() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        PlayerRappelClient.clearIfStale(mc.player);

        if (mc.screen != null) return;

        boolean self = false;
        while (SELF_RAPPEL.consumeClick()) {
            self = true;
        }
        if (self) {
            trySelf(mc);
        }

        boolean crew = false;
        while (CREW_RAPPEL.consumeClick()) {
            crew = true;
        }
        if (crew) {
            tryCrew(mc);
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (event.getEntity() instanceof net.minecraft.client.player.LocalPlayer local) {
            PlayerRappelClient.clearIfStale(local);
        }
        if (!PlayerRappelClient.isMovementLocked()) return;
        Input input = event.getInput();
        input.leftImpulse = 0.0F;
        input.forwardImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    private static void trySelf(Minecraft mc) {
        if (!(mc.player.getVehicle() instanceof VehicleEntity hull)
                || !HullFacts.isHelicopterHull(hull)) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.rappel.not_heli").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }
        if (PlayerRappelClient.lockMode() != PacketPlayerSelfRappelLock.MODE_OFF) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.rappel.already").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketPlayerSelfRappel());
    }

    private static void tryCrew(Minecraft mc) {
        if (!(mc.player.getVehicle() instanceof VehicleEntity hull)
                || !HullFacts.isHelicopterHull(hull)) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.rappel.not_heli").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }
        if (hull.getFirstPassenger() != mc.player) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.rappel.not_driver").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketPlayerCrewRappel());
    }
}
