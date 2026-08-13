package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenTargetPriority;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Opens the op-only target-priority editor ({@code /sewv targetPriority}). */
public final class TargetPriorityAccess {

    private TargetPriorityAccess() {}

    public static int open(ServerPlayer player) {
        if (!PoolEditorAccess.mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.denied"), true);
            return 0;
        }
        WorldTargetPriority data = WorldTargetPriority.get(player.serverLevel());
        Map<TankFaction, Set<String>> excluded = new EnumMap<>(TankFaction.class);
        Map<TankFaction, Set<String>> defaults = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            excluded.put(faction, new LinkedHashSet<>(data.excludedOf(faction)));
            defaults.put(faction, WorldTargetPriority.builtInExcluded());
        }
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenTargetPriority(excluded, defaults, new ArrayList<>(WorldTargetPriority.catalog())));
        return 1;
    }
}
