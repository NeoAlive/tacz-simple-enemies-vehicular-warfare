package com.neoalive.tacz_sewv.network;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.WorldTargetPriority;

/** Client → server: replace world target-priority excludes with the editor snapshot. */
public class PacketUpdateTargetPriority {

    private final Map<TankFaction, Set<String>> excluded;

    public PacketUpdateTargetPriority(Map<TankFaction, Set<String>> excluded) {
        this.excluded = excluded;
    }

    public PacketUpdateTargetPriority(FriendlyByteBuf buf) {
        this.excluded = PacketOpenTargetPriority.readFactionSets(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        PacketOpenTargetPriority.writeFactionSets(buf, this.excluded);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PoolEditorAccess.mayEdit(player)) return;

            WorldTargetPriority data = WorldTargetPriority.get(player.serverLevel());
            for (TankFaction faction : TankFaction.values()) {
                Set<String> set = this.excluded.get(faction);
                data.setExcluded(faction, set == null ? new LinkedHashSet<>() : set);
            }
            player.displayClientMessage(Component.translatable("message.tacz_sewv.target_priority.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
