package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.lwjgl.glfw.GLFW;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketBoardVehicle;
import com.neoalive.tacz_sewv.network.PacketDismountVehicle;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BoardKeybind {

    public static final KeyMapping BOARD_KEY = new KeyMapping(
            "key.tacz_sewv.board",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.tacz_sewv"
    );

    public static void onKeyPressed() {
    Minecraft mc = Minecraft.getInstance();
    Player player = mc.player;
    if (player == null || mc.level == null) return;

    // Gather owned PMCs (your existing logic, works for both board and dismount)
    List<Integer> unitIds = new ArrayList<>();
    List<PmcUnitEntity> units = mc.level.getEntitiesOfClass(
            PmcUnitEntity.class, player.getBoundingBox().inflate(64.0));
    for (PmcUnitEntity pmc : units) {
        if (pmc.isOwnedBy(player)) unitIds.add(pmc.getId());
    }
    if (unitIds.isEmpty()) return;

    boolean ctrlHeld = InputConstants.isKeyDown(
            mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);

    if (ctrlHeld) {
        // CTRL+K → dismount all
        NetworkHandler.CHANNEL.sendToServer(new PacketDismountVehicle(unitIds));
        player.displayClientMessage(
                Component.literal("§eDismount order sent to " + unitIds.size() + " units."), true);
    } else {
        // Plain K → board the looked-at vehicle (your existing logic)
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof VehicleEntity vehicle)) return;

        NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, vehicle.getId()));
        player.displayClientMessage(
                Component.literal("§aBoarding order sent to " + unitIds.size() + " units."), true);
    }
}
}