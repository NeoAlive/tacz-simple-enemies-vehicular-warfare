package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Shared "where should this crew go?" resolution for any mounted-vehicle drive
 * goal — ground ({@link DriveVehicleGoal}) or flight ({@link DriveHelicopterGoal}).
 *
 * <p>PmcUnitEntity (player-commandable) drives its destination from the SEM order
 * queue; RUunitEntity/USunitEntity (plain hostiles, no order system) fall back to
 * their current combat target, then to reinforcing a nearby allied crew in combat.
 * The stateless order/formation resolution lives in static methods here; the
 * ally-assist world scan carries per-goal mutable state, so it lives in the
 * {@link AllyAssist} holder each goal owns one of.
 */
public final class VehicleTargeting {

    private VehicleTargeting() {}

    // Ring around an allied crew an idle unit settles into to join its fight.
    private static final double ASSIST_RING_RADIUS = 16.0;
    private static final double ASSIST_RING_DEADBAND = 4.0;
    // How close a hull parks to an ordinary destination, on top of its own width. Deliberately
    // loose: closing to within a hull length of a combat approach is the last thing armor should
    // do. A formation slot needs the opposite and overrides it — see arrivalDistance.
    private static final double STOP_DISTANCE = 8.0;

    // Resolve where a mounted crew should head. Returns null when there is nowhere
    // to go (holding position, no target, nothing to reinforce). `assist` carries
    // the stateful ally scan and may be null to opt out of mutual support.
    public static BlockPos resolveDestination(AbstractUnit unit, VehicleEntity vehicle, AllyAssist assist) {
        if (!(unit instanceof PmcUnitEntity pmc)) {
            LivingEntity target = unit.getTarget();
            if (target != null) return target.blockPosition();
            // No fight of our own — reinforce a nearby allied crew that has one.
            return assist != null ? assist.assistTargetPos(unit, vehicle) : null;
        }

        // A patrol is a standing TDT order that outranks the SEM order queue: while it is set the
        // hull wanders its area. Combat still preempts it — the drive goal fights its live target
        // and only falls back to this destination when there is none — and dismount clears it.
        BlockPos patrol = PatrolSupport.currentWaypoint(pmc);
        if (patrol != null) return patrol;

        OrderType order = pmc.getOrder();

        switch (order) {
            case HOLD_POSITION:
            case CEASE_FIRE:
                // CEASE_FIRE holds ground too; firing is suppressed separately in
                // MixinVehicleFireCooldown so the crew simply sits and doesn't shoot.
                return null;

            case MOVE_TO_POSITION:
                Vec3 moveTarget = pmc.getMoveToTarget();
                return (moveTarget != null && !moveTarget.equals(Vec3.ZERO))
                        ? BlockPos.containing(moveTarget) : null;

            case ATTACK_THAT_TARGET:
                // Player designated the target — no freelancing off to help allies.
                return pmc.getTarget() != null ? pmc.getTarget().blockPosition() : null;

            case FREE_FIRE:
                if (pmc.getTarget() != null) {
                    return pmc.getTarget().blockPosition();
                }
                // Free-firing with nothing to shoot — reinforce an allied crew in combat.
                return assist != null ? assist.assistTargetPos(unit, vehicle) : null;

            case FOLLOW_COMMANDER:
                Player follows = commander(pmc);
                return follows != null ? follows.blockPosition() : null;

            case FORM_WEDGE:
            case FORM_COLUMN: {
                Player leader = commander(pmc);
                IFormationMember member = (IFormationMember) pmc;
                Direction axis = member.sewv$getFormationDirection();
                int slot = pmc.getFormationIndex();
                // No axis means this order did not come through our gate — it is a plain SEM
                // infantry formation that happens to have caught a mounted crew. There is no
                // hull geometry to drive to, so hold.
                if (leader == null || axis == null || slot < 0) return null;
                FormationShape shape = FormationShape.byId(member.sewv$getFormationShape());
                return VehicleFormation.slotPos(
                        unit.level(), leader.position(), axis, shape, slot, member.sewv$getFormationRowSize());
            }

            default:
                return null;
        }
    }

    /**
     * How close the hull must be to its resolved destination to count as arrived.
     *
     * <p>A formation slot needs a tight arrival that nothing else does. Slots sit
     * vehicleFormationSpacing apart, while the generic answer is a hull width plus
     * {@link #STOP_DISTANCE} — for a 4.62-wide T-90A that is 11.62 blocks, wider than the whole
     * formation, so every hull would read "arrived" from anywhere in it and the wedge would
     * collapse onto the point man.
     */
    public static double arrivalDistance(AbstractUnit unit, VehicleEntity vehicle) {
        if (unit instanceof PmcUnitEntity pmc) {
            OrderType order = pmc.getOrder();
            if (order == OrderType.FORM_WEDGE || order == OrderType.FORM_COLUMN) {
                return SewvConfig.VEHICLE_FORMATION_ARRIVE_RADIUS.get();
            }
        }
        return vehicle.getBbWidth() - 1.0 + STOP_DISTANCE;
    }

    /**
     * The heading a parked crew holds, or null when this order has no heading to hold (which is
     * every order but a formation — there is nothing else to face).
     */
    public static Vec3 formationForward(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return null;
        OrderType order = pmc.getOrder();
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return null;
        Direction axis = ((IFormationMember) pmc).sewv$getFormationDirection();
        return axis == null ? null : VehicleFormation.forward(axis);
    }

    private static Player commander(PmcUnitEntity pmc) {
        UUID ownerId = pmc.getOwnerUUID();
        return ownerId != null ? pmc.level().getPlayerByUUID(ownerId) : null;
    }

    // Point at `radius` straight out from the target through the vehicle — the ring
    // the hull should hold (standoff for armor, orbit for a gunship, break-contact
    // beyond it when retreating).
    public static BlockPos computeStandoffPoint(VehicleEntity vehicle, BlockPos targetPos, double radius) {
        return computeStandoffPoint(vehicle, targetPos, radius, 0.0);
    }

    // Same ring, but swung `bearingOffsetRad` around the target from where the hull
    // currently stands. Offset 0 is "hold this bearing, fix the range"; a non-zero
    // offset walks the hull around the ring, which is how a crew that can't get a
    // firing solution from here goes looking for ground it can shoot from.
    public static BlockPos computeStandoffPoint(VehicleEntity vehicle, BlockPos targetPos,
                                                double radius, double bearingOffsetRad) {
        double cx = targetPos.getX() + 0.5;
        double cz = targetPos.getZ() + 0.5;
        double dx = vehicle.getX() - cx;
        double dz = vehicle.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            // Practically on top of the target — flee along the current facing
            Vector3f forward = vehicle.getForwardDirection().normalize();
            dx = forward.x;
            dz = forward.z;
            len = 1.0;
        }
        if (bearingOffsetRad != 0.0) {
            Vec3 swung = rotateY(new Vec3(dx, 0.0, dz), bearingOffsetRad);
            dx = swung.x;
            dz = swung.z;
            // rotateY preserves length, so `len` still holds.
        }
        double scale = radius / len;
        return BlockPos.containing(cx + dx * scale, vehicle.getY(), cz + dz * scale);
    }

    // Rotate a horizontal (y=0) direction about the vertical axis. Shared: the whisker fans
    // swing candidate headings with it, and the ring math above swings bearings with it.
    public static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`. Shared so the
    // steering signs cannot drift apart between the ground and flight goals — the flight
    // goal's yaw stick is fed the negation of this, which is only correct while both goals
    // agree on the convention.
    public static double signedAngleTo(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    /**
     * Mutual support: an idle crew that notices an allied vehicle in combat drives
     * to it and settles inside a ring around the ally — close enough to bring its
     * own guns into the same fight, far enough to not park on the ally's tracks.
     * Holds the cached ally between world scans (the expensive part), rescanning
     * only on the target-scan cadence.
     */
    public static final class AllyAssist {
        private VehicleEntity assistAlly;
        private long lastAssistScanTime = Long.MIN_VALUE;

        // Drive-to point for reinforcing an ally, or null when there is nothing to
        // reinforce (or we're already inside the ally's ring — arrival, not failure).
        BlockPos assistTargetPos(AbstractUnit unit, VehicleEntity vehicle) {
            VehicleEntity ally = findAllyInCombat(unit, vehicle);
            if (ally == null) return null;

            double dx = ally.getX() - vehicle.getX();
            double dz = ally.getZ() - vehicle.getZ();
            double arrive = ASSIST_RING_RADIUS + ASSIST_RING_DEADBAND;
            if (dx * dx + dz * dz <= arrive * arrive) return null; // inside the ring — done

            return computeStandoffPoint(vehicle, ally.blockPosition(), ASSIST_RING_RADIUS);
        }

        // Nearest allied crewed vehicle in combat within the configured assist range.
        private VehicleEntity findAllyInCombat(AbstractUnit unit, VehicleEntity vehicle) {
            double range = SewvConfig.VEHICLE_ALLY_ASSIST_RANGE.get();
            if (range <= 0.0) return null; // mutual support disabled

            // The sentinel must be tested explicitly: now - Long.MIN_VALUE overflows
            // negative, which would make the throttle permanently "too soon" and the
            // scan would never run at all.
            long now = unit.level().getGameTime();
            if (this.lastAssistScanTime != Long.MIN_VALUE
                    && now - this.lastAssistScanTime < SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get()) {
                return validateAssistAlly(unit, vehicle, range);
            }
            this.lastAssistScanTime = now;

            // Same flat-cylinder shape as the target scan: full horizontal reach,
            // capped vertical extent, corners rounded off by the distance filter.
            double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
            AABB bounds = new AABB(
                    vehicle.getX() - range, vehicle.getY() - halfHeight, vehicle.getZ() - range,
                    vehicle.getX() + range, vehicle.getY() + halfHeight, vehicle.getZ() + range);

            VehicleEntity best = null;
            double bestDistSq = range * range;
            for (VehicleEntity ally : unit.level().getEntitiesOfClass(VehicleEntity.class, bounds,
                    v -> v != vehicle && !v.isWreck())) {
                if (!isAlliedCrewInCombat(unit, ally)) continue;
                double dx = ally.getX() - vehicle.getX();
                double dz = ally.getZ() - vehicle.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq <= bestDistSq) {
                    best = ally;
                    bestDistSq = distSq;
                }
            }
            this.assistAlly = best;
            return best;
        }

        // Between scans: keep the cached ally only while it still needs the help.
        private VehicleEntity validateAssistAlly(AbstractUnit unit, VehicleEntity vehicle, double range) {
            VehicleEntity ally = this.assistAlly;
            if (ally == null) return null;
            if (ally.isRemoved() || ally.isWreck() || !isAlliedCrewInCombat(unit, ally)
                    || vehicle.distanceToSqr(ally) > range * range * 2.25) { // >1.5x range — chase abandoned
                this.assistAlly = null;
                return null;
            }
            return ally;
        }

        public void clear() {
            this.assistAlly = null;
        }
    }

    private static boolean isAlliedCrewInCombat(AbstractUnit unit, VehicleEntity ally) {
        if (!(ally.getFirstPassenger() instanceof AbstractUnit driver)) return false;
        if (!isSameFaction(unit, driver)) return false;
        LivingEntity target = driver.getTarget();
        return target != null && target.isAlive();
    }

    // The hard friendly-fire gate: true when `target` is a unit of the same
    // faction. MixinAbstractUnit cancels any setTarget that fails this test, so a
    // stray splash-damage hit can never escalate into an intra-faction firefight.
    public static boolean isFriendly(AbstractUnit unit, LivingEntity target) {
        return target instanceof AbstractUnit other && isSameFaction(unit, other);
    }

    // Package-visible: the obstacle filters in DriveVehicleGoal (hulls) and
    // DriveHelicopterGoal (airframes) define "ally" with this same test, so assist
    // doctrine and collision doctrine can't diverge.
    static boolean isSameFaction(AbstractUnit unit, AbstractUnit other) {
        if (unit instanceof RUunitEntity) return other instanceof RUunitEntity;
        if (unit instanceof USunitEntity) return other instanceof USunitEntity;
        return unit instanceof PmcUnitEntity && other instanceof PmcUnitEntity;
    }
}
