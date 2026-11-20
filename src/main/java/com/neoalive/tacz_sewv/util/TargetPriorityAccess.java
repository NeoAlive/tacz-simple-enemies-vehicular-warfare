package com.neoalive.tacz_sewv.util;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenTargetStack;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Opens the op-only target priority stack editor ({@code /sewv targetpriority}, alias {@code targetingAllow}). */
public final class TargetPriorityAccess {

    private TargetPriorityAccess() {}

    public static int open(ServerPlayer player) {
        if (!PoolEditorAccess.mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.denied"), true);
            return 0;
        }
        WorldTargetStack data = WorldTargetStack.get(player.serverLevel());
        Map<TankFaction, WorldTargetStack.Stack> stacks = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) stacks.put(faction, data.of(faction));
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenTargetStack(stacks, WorldTargetStack.builtInDefault()));
        return 1;
    }
}
