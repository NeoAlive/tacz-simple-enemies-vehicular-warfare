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

        // What's the player looking at?
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof VehicleEntity vehicle)) return;

        // Gather owned PMCs nearby (simple first pass: all owned within 64)
        List<Integer> unitIds = new ArrayList<>();
        List<PmcUnitEntity> units = mc.level.getEntitiesOfClass(
                PmcUnitEntity.class,
                player.getBoundingBox().inflate(64.0)
        );
        for (PmcUnitEntity pmc : units) {
            if (pmc.isOwnedBy(player)) {
                unitIds.add(pmc.getId());
            }
        }

        if (!unitIds.isEmpty()) {
            // ModNetworking is your bridge's network channel — wire it up
            NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, vehicle.getId()));
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§aBoarding order sent to " + unitIds.size() + " units."),
                    true
            );
        }
    }
}