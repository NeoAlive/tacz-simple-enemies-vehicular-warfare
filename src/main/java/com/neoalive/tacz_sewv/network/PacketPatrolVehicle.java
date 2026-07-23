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
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Player → server patrol order for owned ground-vehicle crews. The radius is clamped to the valid
 * range before it reaches {@link PatrolSupport}. Only the DRIVER of a non-helicopter hull takes
 * it — that is the unit whose drive goal reads the destination.
 *
 * <p>The origin is the sender's own position unless one is given: the terminal has no way to name a
 * remote point, so it sends none, while the world map's order menu exists precisely to name one.
 * That is not a new trust boundary — SEM's own {@code MOVE_TO_POSITION} already takes an arbitrary
 * position from the client — and the radius is clamped either way.
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

    /** Ceiling on a plotted cruise, so a client cannot hand the server an unbounded route. */
    public static final int MAX_ROUTE_NODES = 64;

    private final List<Integer> unitIds;
    private final int radius;
    private final int mode; // IVehiclePatrol.MODE_PATROL / MODE_SEARCH / MODE_CRUISE, or MODE_DISMISS
    @Nullable
    private final BlockPos origin; // null = centre the area on the sender
    private final List<BlockPos> route; // MODE_CRUISE only; empty otherwise

    public PacketPatrolVehicle(List<Integer> unitIds, int radius, int mode) {
        this(unitIds, radius, mode, null, List.of());
    }

    public PacketPatrolVehicle(List<Integer> unitIds, int radius, int mode, @Nullable BlockPos origin) {
        this(unitIds, radius, mode, origin, List.of());
    }

    public PacketPatrolVehicle(List<Integer> unitIds, int radius, int mode, @Nullable BlockPos origin,
                               List<BlockPos> route) {
        this.unitIds = unitIds;
        this.radius = radius;
        this.mode = mode;
        this.origin = origin;
        this.route = route;
    }

    /** A cruise: loop these plotted waypoints in order. */
    public static PacketPatrolVehicle cruise(List<Integer> unitIds, List<BlockPos> route) {
        return new PacketPatrolVehicle(unitIds, 0, IVehiclePatrol.MODE_CRUISE, null, route);
    }

    public PacketPatrolVehicle(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.radius = buf.readVarInt();
        this.mode = buf.readVarInt();
        this.origin = buf.readBoolean() ? buf.readBlockPos() : null;
        this.route = buf.readList(FriendlyByteBuf::readBlockPos);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.radius);
        buf.writeVarInt(this.mode);
        buf.writeBoolean(this.origin != null);
        if (this.origin != null) buf.writeBlockPos(this.origin);
        buf.writeCollection(this.route, FriendlyByteBuf::writeBlockPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            if (this.mode == MODE_DISMISS) {
                dismiss(player);
                return;
            }

            if (this.mode == IVehiclePatrol.MODE_CRUISE) {
                assignCruise(player);
                return;
            }

            int radius = Mth.clamp(this.radius, MIN_RADIUS, MAX_RADIUS);
            boolean search = this.mode == IVehiclePatrol.MODE_SEARCH;
            BlockPos origin = this.origin != null ? this.origin : player.blockPosition();

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
                // Last order wins: an area task outranks the SEM order queue, so a stale
                // MOVE_TO_POSITION left under it would spring back the moment the task ends.
                crews.get(i).setOrder(OrderType.FREE_FIRE);
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
     * Assign the plotted route. Same crew filter as the other area tasks — a cruise is driven, so
     * only the driver of a non-helicopter hull takes it — and the same stand-down off the SEM order
     * queue, since an area task outranks it and a stale MOVE underneath would spring back the
     * moment the cruise ends.
     *
     * <p>The route is capped rather than trusted: a plot is a list from the client, and nothing
     * else bounds how long it could be.
     */
    private void assignCruise(Player player) {
        if (this.route.isEmpty()) return;
        List<BlockPos> route = this.route.size() > MAX_ROUTE_NODES
                ? this.route.subList(0, MAX_ROUTE_NODES) : this.route;

        int ordered = 0;
        for (int unitId : this.unitIds) {
            Entity e = player.level().getEntity(unitId);
            if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)
                    && pmc.getVehicle() instanceof VehicleEntity v
                    && v.getFirstPassenger() == pmc
                    && !(v.getEngineInfo() instanceof EngineInfo.Helicopter)) {
                pmc.setOrder(OrderType.FREE_FIRE);
                PatrolSupport.beginCruise(pmc, route);
                ordered++;
            }
        }

        NetworkHandler.orderFeedback(player, "message.tacz_sewv.cruise.ordered", ordered,
                ChatFormatting.GREEN, ordered, route.size());
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
