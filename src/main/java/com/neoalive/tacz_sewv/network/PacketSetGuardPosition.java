package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;

/**
 * Cache a GUARD_POSITION on each named driver's hull. Does not change the standing SEM order.
 */
public class PacketSetGuardPosition {

    private final List<Integer> unitIds;
    private final BlockPos pos;

    public PacketSetGuardPosition(List<Integer> unitIds, BlockPos pos) {
        this.unitIds = unitIds;
        this.pos = pos;
    }

    public PacketSetGuardPosition(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.pos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeBlockPos(this.pos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(sp, () -> handle(ctx))) return;

            int set = 0;
            // A crew is one post (on its hull), however many of its seats were selected.
            java.util.Set<Entity> holders = new java.util.HashSet<>();
            for (int unitId : this.unitIds) {
                PmcUnitEntity pmc = com.neoalive.tacz_sewv.order.OrderGuard.orderBody(sp, unitId,
                        "PacketSetGuardPosition");
                if (pmc == null) continue;
                if (com.neoalive.tacz_sewv.order.OrderGuard.rejectIfDowned(sp, pmc)) continue;
                if (com.neoalive.tacz_sewv.order.OrderGuard.rejectIfTerritory(sp, pmc, "set_guard")) continue;
                if (!holders.add(GuardSupport.holder(pmc))) continue;
                GuardSupport.set(GuardSupport.holder(pmc), this.pos);
                set++;
            }

            NetworkHandler.orderFeedback(player, "set_guard", "message.tacz_sewv.guard.set", set,
                    ChatFormatting.GREEN, set);
        });
        ctx.get().setPacketHandled(true);
    }
}
