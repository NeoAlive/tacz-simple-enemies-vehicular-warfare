package com.neoalive.tacz_sewv.block;

import java.util.ArrayList;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenSpawnProbeGui;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;

/** Op-only open path for spawn_probe config. */
public final class SpawnProbeEditor {

    private SpawnProbeEditor() {}

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static void deny(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("message.tacz_sewv.spawn_probe.denied"), true);
    }

    public static void open(ServerPlayer player, SpawnProbeBlockEntity be) {
        if (!mayEdit(player)) {
            deny(player);
            return;
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenSpawnProbeGui(
                        be.getBlockPos(),
                        new ArrayList<>(be.getVehicleList()),
                        be.isPreCrewedSpawn(),
                        PoolEditorAccess.catalog()));
    }
}
