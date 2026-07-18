package com.neoalive.tacz_sewv.network;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.PatrolSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Player → server patrol order for owned ground-vehicle crews. The origin is the sender's own
 * position (never trusted from the client), and the radius is clamped to the valid range before it
 * reaches {@link PatrolSupport}. Only the DRIVER of a non-helicopter hull takes it — that is the
 * unit whose drive goal reads the destination.
 */
public class PacketPatrolVehicle {

    /** The floor the TDT enforces (and the server re-checks) and the ceiling the server clamps to. */
    public static final int MIN_RADIUS = 48;
    public static final int MAX_RADIUS = 1024;

    /**
     * Stand every crew down off its area task, back onto the SEM order queue. Deliberately NOT the
     * dismount order: that also empties seats and frees mortars, where this only cancels the task
     * and leaves the crew where it is.
     */
    public static final int MODE_DISMISS = -1;

    private final List<Integer> unitIds;
    private final int radius;
    private final int mode; // IVehiclePatrol.MODE_PATROL or MODE_SEARCH

    public PacketPatrolVehicle(List<Integer> unitIds, int radius, int mode) {
        this.unitIds = unitIds;
        this.radius = radius;
        this.mode = mode;
    }

    public PacketPatrolVehicle(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.radius = buf.readVarInt();
        this.mode = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.radius);
        buf.writeVarInt(this.mode);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            if (this.mode == MODE_DISMISS) {
                dismiss(player);
                return;
            }

            int radius = Mth.clamp(this.radius, MIN_RADIUS, MAX_RADIUS);
            boolean search = this.mode == IVehiclePatrol.MODE_SEARCH;
            BlockPos origin = player.blockPosition();

            // Collected before assigning: a search sweep hands each hull its own slice of the
            // circle, so it has to know how many hulls are taking the task.
            List<PmcUnitEntity> crews = new ArrayList<>();
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                // Ownership-checked per unit (a spoofed id can't task another player's crews), and
                // only the driver of a ground hull — a gunner/passenger doesn't drive, and a
                // helicopter is flown by DriveHelicopterGoal, which doesn't read area tasks.
                if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)
                        && pmc.getVehicle() instanceof VehicleEntity v
                        && v.getFirstPassenger() == pmc
                        && !(v.getEngineInfo() instanceof EngineInfo.Helicopter)) {
                    crews.add(pmc);
                }
            }

            for (int i = 0; i < crews.size(); i++) {
                if (search) {
                    PatrolSupport.beginSearch(crews.get(i), origin, radius, i, crews.size());
                } else {
                    PatrolSupport.beginPatrol(crews.get(i), origin, radius);
                }
            }

            NetworkHandler.orderFeedback(player,
                    (search ? "message.tacz_sewv.search" : "message.tacz_sewv.patrol") + ".ordered",
                    crews.size(), ChatFormatting.GREEN, crews.size(), radius);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Cancel the area task on every owned unit that has one. No driver/hull filter: clearing is
     * harmless on a unit that was never tasked, and only units that actually stood down are counted
     * back to the player.
     */
    private void dismiss(Player player) {
        int dismissed = 0;
        for (int unitId : this.unitIds) {
            if (player.level().getEntity(unitId) instanceof PmcUnitEntity pmc
                    && pmc.isOwnedBy(player)
                    && ((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null) {
                PatrolSupport.clear(pmc);
                dismissed++;
            }
        }

        NetworkHandler.orderFeedback(player, "message.tacz_sewv.dismiss", dismissed,
                ChatFormatting.YELLOW, dismissed);
    }
}
