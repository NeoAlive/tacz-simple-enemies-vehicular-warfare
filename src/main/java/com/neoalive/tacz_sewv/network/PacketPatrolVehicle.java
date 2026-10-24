package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

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
        this.unitIds = PacketLists.readUnitIds(buf);
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
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(sp, () -> handle(ctx))) return;

            if (this.mode == MODE_DISMISS) {
                dismiss(player);
                return;
            }

            if (this.mode == IVehiclePatrol.MODE_CRUISE) {
                assignCruise(sp);
                return;
            }

            int radius = Mth.clamp(this.radius, MIN_RADIUS, MAX_RADIUS);
            boolean search = this.mode == IVehiclePatrol.MODE_SEARCH;
            BlockPos origin = this.origin != null ? this.origin : player.blockPosition();

            // Collected before assigning: a search sweep hands each unit its own slice of the
            // circle, so it has to know how many are taking the task. A crew is one entry (its
            // driver), however many of its seats were selected.
            Set<PmcUnitEntity> units = new LinkedHashSet<>();
            for (int unitId : this.unitIds) {
                PmcUnitEntity pmc = areaTaskUnit(sp, unitId, true);
                if (pmc != null) units.add(pmc);
            }

            List<PmcUnitEntity> crews = new ArrayList<>(units);
            for (int i = 0; i < crews.size(); i++) {
                PmcUnitEntity pmc = crews.get(i);
                OrderStandDown.clearForNewOrder(pmc, "PacketPatrolVehicle");
                // Last order wins: an area task outranks the SEM order queue, so a stale
                // MOVE_TO_POSITION left under it would spring back the moment the task ends.
                pmc.setOrder(OrderType.FREE_FIRE);
                if (search) {
                    PatrolSupport.beginSearch(pmc, origin, radius, i, crews.size());
                } else {
                    PatrolSupport.beginPatrol(pmc, origin, radius);
                }
                liftOffIfParked(pmc);
            }

            NetworkHandler.orderFeedback(player, search ? "search" : "patrol",
                    (search ? "message.tacz_sewv.search" : "message.tacz_sewv.patrol") + ".ordered",
                    crews.size(), ChatFormatting.GREEN, crews.size(), radius);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Assign the plotted route. A cruise is driven along points on the ground, so only ground crews
     * take it (no infantry, no aircraft). Same stand-down off the SEM order queue as the other area
     * tasks, since an area task outranks it and a stale MOVE underneath would spring back the
     * moment the cruise ends.
     *
     * <p>The route is capped rather than trusted: a plot is a list from the client, and nothing
     * else bounds how long it could be.
     */
    private void assignCruise(ServerPlayer player) {
        if (this.route.isEmpty()) {
            OrderReport.fail(player, OrderFailure.NO_ROUTE);
            return;
        }
        List<BlockPos> route = this.route.size() > MAX_ROUTE_NODES
                ? this.route.subList(0, MAX_ROUTE_NODES) : this.route;

        Set<PmcUnitEntity> crews = new LinkedHashSet<>();
        for (int unitId : this.unitIds) {
            PmcUnitEntity pmc = areaTaskUnit(player, unitId, false);
            if (pmc != null) crews.add(pmc);
        }
        int ordered = 0;
        for (PmcUnitEntity pmc : crews) {
            OrderStandDown.clearForNewOrder(pmc, "PacketPatrolVehicle.cruise");
            pmc.setOrder(OrderType.FREE_FIRE);
            PatrolSupport.beginCruise(pmc, route);
            ordered++;
        }

        NetworkHandler.orderFeedback(player, "cruise", "message.tacz_sewv.cruise.ordered", ordered,
                ChatFormatting.GREEN, ordered, route.size());
    }

    /**
     * Who takes an area task for {@code unitId}: a unit on foot walks it, a crew member hands it to
     * its hull's driver (so selecting a tank by its gunner still orders the tank). Patrol and Search
     * also take helicopters, which fly the same waypoints and hover at each one; planes never do,
     * and a cruise ({@code walkOrFly == false}) is ground crews only.
     */
    @Nullable
    private static PmcUnitEntity areaTaskUnit(ServerPlayer player, int unitId, boolean walkOrFly) {
        PmcUnitEntity pmc = OrderGuard.orderBody(player, unitId, "PacketPatrolVehicle");
        if (pmc == null) return null;
        if (pmc.getVehicle() == null && !walkOrFly) {
            OrderReport.fail(player, OrderFailure.MUST_BE_MOUNTED, pmc);
            return null;
        }
        if (OrderGuard.rejectIfDowned(player, pmc)) return null;
        if (OrderGuard.rejectIfTerritory(player, pmc, "patrol")) return null;
        if (QuickCommandUnits.refuseFobOrRoute(player, pmc)) return null;
        if (pmc.getVehicle() instanceof VehicleEntity v) {
            boolean plane = HullFacts.isPlaneHull(v);
            boolean heli = !plane && HullFacts.isHelicopterHull(v);
            if (plane || (heli && !walkOrFly)) {
                OrderReport.fail(player, OrderFailure.WRONG_HULL, pmc);
                return null;
            }
        }
        return pmc;
    }

    /**
     * A PMC helicopter on the ground sits in sticky LANDED and ignores destinations, so an area task
     * would read as "ordered" while nothing moved. Lift it off at its own cruise trim; once airborne
     * it flies the task's waypoints like any other destination.
     */
    private static void liftOffIfParked(PmcUnitEntity pmc) {
        if (!(pmc.getVehicle() instanceof VehicleEntity v) || !HullFacts.isHelicopterHull(v)) return;
        int cmd = ((IHelicopterPilot) pmc).sewv$getHeliCommand();
        if (cmd == IHelicopterPilot.HELI_CMD_TAKEOFF) return;
        if (v.onGround() || cmd == IHelicopterPilot.HELI_CMD_LANDED || cmd == IHelicopterPilot.HELI_CMD_LANDING
                || cmd == IHelicopterPilot.HELI_CMD_EMERGENCY_LAND || cmd == IHelicopterPilot.HELI_CMD_LAND_HELIPAD) {
            FlightOrders.takeoff(pmc, v, ((IHelicopterPilot) pmc).sewv$getCruiseAltitude());
        }
    }

    /**
     * Cancel the area task — and any standing formation — on every owned unit that has one. No
     * driver/hull filter: clearing is harmless on a unit that was never tasked, and only units
     * that actually stood down are counted back to the player.
     *
     * <p>Units on a preferred pathway are also included when they are in funnel range but were not
     * in the selection — "funnel all" never puts infantry on the map marker list, so dismiss must
     * still reach them.
     */
    private void dismiss(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;

        Set<Integer> targets = new LinkedHashSet<>(this.unitIds);
        for (PmcUnitEntity pmc : PathwaySupport.funnelCandidates(sp)) {
            if (((com.neoalive.tacz_sewv.bridge.IPathwayInfantry) pmc).sewv$hasPathway()) {
                targets.add(pmc.getId());
            }
        }

        int dismissed = 0;
        for (int unitId : targets) {
            if (!(player.level().getEntity(unitId) instanceof PmcUnitEntity pmc)
                    || !OrderAuth.check(sp, pmc, "PacketPatrolVehicle.dismiss")) continue;
            boolean had = ((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                    || ((com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc).sewv$hasInfantrySweep()
                    || ((com.neoalive.tacz_sewv.bridge.IPathwayInfantry) pmc).sewv$hasPathway()
                    || EntrenchSupport.isEntrenched(pmc)
                    || ((IEscort) pmc).tacz_sewv$getEscortTargetId() != -1
                    || GuardSupport.isReaching(pmc)
                    || (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder())
                    || ((IFormationMember) pmc).sewv$getFormationDirection() != null;
            if (had) {
                OrderStandDown.clearForNewOrder(pmc, "PacketPatrolVehicle.dismiss");
                VehicleFormation.clear(pmc);
                if (pmc.getVehicle() == null) {
                    pmc.getNavigation().stop();
                }
                dismissed++;
            }
        }

        NetworkHandler.orderFeedback(player, "dismiss", "message.tacz_sewv.dismiss", dismissed,
                ChatFormatting.YELLOW, dismissed);
    }
}
