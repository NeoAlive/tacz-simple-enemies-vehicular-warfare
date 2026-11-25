package com.neoalive.tacz_sewv.network;

import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.VehiclePoolCatalogSource;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/** Client to server: replace every faction's target priority stack with the editor's copy. */
public class PacketUpdateTargetStack {

    private final Map<TankFaction, WorldTargetStack.Stack> stacks;

    public PacketUpdateTargetStack(Map<TankFaction, WorldTargetStack.Stack> stacks) {
        this.stacks = stacks;
    }

    public PacketUpdateTargetStack(FriendlyByteBuf buf) {
        this.stacks = PacketOpenTargetStack.readStacks(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        PacketOpenTargetStack.writeStacks(buf, this.stacks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PoolEditorAccess.mayEdit(player)) return;
            WorldTargetStack data = WorldTargetStack.get(player.serverLevel());
            for (TankFaction f : TankFaction.values()) {
                WorldTargetStack.Stack s = this.stacks.get(f);
                if (s == null) continue;
                // Parsed and cleaned again here: the client's copy is never trusted.
                data.set(f, s.priority(), s.excluded(), VehiclePoolCatalogSource::isVehicleId);
            }
            player.displayClientMessage(Component.translatable("message.tacz_sewv.target_stack.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
