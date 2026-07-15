package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.lwjgl.glfw.GLFW;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketBoardVehicle;
import com.neoalive.tacz_sewv.network.PacketDismountVehicle;
import com.neoalive.tacz_sewv.network.PacketManMortar;

import java.util.ArrayList;
import java.util.List;

public class BoardKeybind {

    // Plain K boards; CTRL+K dismounts. Both are rebindable in Controls — the
    // KeyModifier lets them share the K base without one firing for the other
    // (NONE is inactive while CTRL is held, and vice-versa).
    public static final KeyMapping BOARD_KEY = new KeyMapping(
            "key.tacz_sewv.board",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.tacz_sewv"
    );

    public static final KeyMapping DISMOUNT_KEY = new KeyMapping(
            "key.tacz_sewv.dismount",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.tacz_sewv"
    );

    public static void onBoardPressed() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) {
            hint(player, "message.tacz_sewv.board.no_vehicle");
            return;
        }

        // The server validates ownership and reports the result — no optimistic
        // client-side "order sent" message that could lie on failure.
        //
        // A mortar is a VehicleEntity too, but it has no seats to ride: crewing one
        // means standing beside it, which is a different order entirely. Check it first,
        // since the vehicle branch would otherwise swallow it and send units to board
        // something they can never sit in.
        if (ehr.getEntity() instanceof MortarEntity mortar) {
            NetworkHandler.CHANNEL.sendToServer(new PacketManMortar(unitIds, mortar.getId()));
        } else if (ehr.getEntity() instanceof VehicleEntity vehicle) {
            NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, vehicle.getId()));
        } else {
            hint(player, "message.tacz_sewv.board.no_vehicle");
        }
    }

    public static void onDismountPressed() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketDismountVehicle(unitIds));
    }

    // This player's units within the configured radius; the server re-checks
    // ownership per unit before acting on the order.
    private static List<Integer> gatherOwnedUnits(Minecraft mc, Player player) {
        double radius = SewvConfig.BOARD_SCAN_RADIUS.get();
        List<Integer> unitIds = new ArrayList<>();
        List<PmcUnitEntity> units = mc.level.getEntitiesOfClass(
                PmcUnitEntity.class, player.getBoundingBox().inflate(radius));
        for (PmcUnitEntity pmc : units) {
            if (pmc.isOwnedBy(player)) unitIds.add(pmc.getId());
        }
        return unitIds;
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
