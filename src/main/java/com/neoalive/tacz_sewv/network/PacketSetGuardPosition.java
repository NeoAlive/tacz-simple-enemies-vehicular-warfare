package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

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
            if (com.neoalive.tacz_sewv.invasion.InvasionOrderGate.denyIfActive(sp)) return;

            int set = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(sp, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!OrderAuth.check(sp, pmc, "PacketSetGuardPosition")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (!(pmc.getVehicle() instanceof VehicleEntity hull)) {
                    OrderReport.fail(sp, OrderFailure.NOT_MOUNTED, pmc);
                    continue;
                }
                // A gunner from the same hull: its driver is in this list and takes the order.
                if (hull.getFirstPassenger() != pmc) continue;
                GuardSupport.set(hull, this.pos);
                set++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.guard.set", set,
                    ChatFormatting.GREEN, set);
        });
        ctx.get().setPacketHandled(true);
    }
}
