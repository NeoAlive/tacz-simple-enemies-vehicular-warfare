package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Flight commands for owned helicopter crews, mirroring {@link BoardKeybind}: plain
 * L orders takeoff to cruise altitude; CTRL+L orders a landing at the block the
 * player is looking at. Both are rebindable and share the L base via KeyModifier.
 */
public class HelicopterKeybind {

    public static final KeyMapping TAKEOFF_KEY = new KeyMapping(
            "key.tacz_sewv.heli_takeoff",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.tacz_sewv"
    );

    public static final KeyMapping LAND_KEY = new KeyMapping(
            "key.tacz_sewv.heli_land",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.tacz_sewv"
    );

    public static void onTakeoffPressed() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_TAKEOFF, null));
    }

    // How far out the landing-pad raycast reaches. mc.hitResult only covers the
    // player's ~5-block interaction range — useless for designating a pad across
    // the field — so the land order does its own long-range block pick.
    private static final double LAND_PICK_RANGE = 128.0;

    public static void onLandPressed() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Land where the player is looking. A block must be under the crosshair —
        // the helicopter sets down on top of it.
        HitResult hit = player.pick(LAND_PICK_RANGE, 0.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            hint(player, "message.tacz_sewv.heli.no_pad");
            return;
        }

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }
        BlockPos pad = bhr.getBlockPos();
        NetworkHandler.CHANNEL.sendToServer(
                new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_LANDING, pad));
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
