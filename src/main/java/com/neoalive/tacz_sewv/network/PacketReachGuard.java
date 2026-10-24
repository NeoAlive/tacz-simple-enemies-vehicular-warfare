package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * REACH_GUARD_POSITION: MOVE_TO_POSITION to the hull's cached guard, then promote to HOLD on arrive
 * ({@link GuardSupport} reach flag + {@code DriveVehicleGoal}).
 */
public class PacketReachGuard {

    private final List<Integer> unitIds;

    public PacketReachGuard(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketReachGuard(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(sp, () -> handle(ctx))) return;

            int ordered = 0;
            java.util.Set<PmcUnitEntity> done = new java.util.HashSet<>();
            for (int unitId : this.unitIds) {
                PmcUnitEntity pmc = OrderGuard.orderBody(sp, unitId, "PacketReachGuard");
                if (pmc == null || !done.add(pmc)) continue;
                if (OrderGuard.rejectIfDowned(sp, pmc)) continue;
                if (OrderGuard.rejectIfTerritory(sp, pmc, "reach_guard")) continue;
                if (QuickCommandUnits.refuseFobOrRoute(sp, pmc)) continue;
                BlockPos guard = GuardSupport.get(GuardSupport.holder(pmc));
                if (guard == null) {
                    OrderReport.fail(sp, OrderFailure.NO_GUARD_POST, pmc);
                    continue;
                }

                OrderStandDown.clearForNewOrder(pmc, "PacketReachGuard");
                pmc.setMoveToTarget(Vec3.atCenterOf(guard)); // also sets MOVE_TO_POSITION
                // Only the drive goal reads this (promote to HOLD on arrival); on foot SEM's own
                // MOVE already stops and holds at the point.
                if (pmc.getVehicle() != null) GuardSupport.setReaching(pmc, true);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.guard.reach", ordered,
                    ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}
