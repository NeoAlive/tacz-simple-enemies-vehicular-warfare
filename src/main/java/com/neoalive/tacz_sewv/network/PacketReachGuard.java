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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;

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
            if (com.neoalive.tacz_sewv.invasion.InvasionOrderGate.denyIfActive(sp)) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc) || !OrderAuth.check(sp, pmc, "PacketReachGuard")) {
                    continue;
                }
                if (!(pmc.getVehicle() instanceof VehicleEntity hull) || hull.getFirstPassenger() != pmc) {
                    continue;
                }
                BlockPos guard = GuardSupport.get(hull);
                if (guard == null) continue;

                if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                        || ((ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
                    PatrolSupport.clearSweepMembership(pmc, "PacketReachGuard");
                }

                Vec3 target = Vec3.atCenterOf(guard);
                ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
                pmc.setMoveToTarget(target); // also sets MOVE_TO_POSITION
                GuardSupport.setReaching(pmc, true);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.guard.reach", ordered,
                    ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}
