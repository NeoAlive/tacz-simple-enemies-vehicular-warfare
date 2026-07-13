package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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

    // Formation spacing between successive slots, in blocks.
    private static final double FORMATION_SPACING = 5.0;
    // Ring around an allied crew an idle unit settles into to join its fight.
    private static final double ASSIST_RING_RADIUS = 16.0;
    private static final double ASSIST_RING_DEADBAND = 4.0;

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
            case FORM_COLUMN:
                Player leader = commander(pmc);
                return leader != null
                        ? formationTarget(leader, order, pmc.getFormationIndex()) : null;

            default:
                return null;
        }
    }

    private static Player commander(PmcUnitEntity pmc) {
        UUID ownerId = pmc.getOwnerUUID();
        return ownerId != null ? pmc.level().getPlayerByUUID(ownerId) : null;
    }

    // Drive-to point for a formation slot behind the commander. COLUMN files units
    // directly astern; WEDGE fans them out to alternating flanks stepping back each
    // rank. Slot is derived from the unit's SEM-assigned formation index.
    private static BlockPos formationTarget(Player owner, OrderType order, int index) {
        float yawRad = owner.getYRot() * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yawRad);
        double forwardZ = Mth.cos(yawRad);
        double rightX = Mth.cos(yawRad);
        double rightZ = Mth.sin(yawRad);

        double back;
        double side;
        if (order == OrderType.FORM_COLUMN) {
            back = (index + 1) * FORMATION_SPACING;
            side = 0.0;
        } else { // FORM_WEDGE
            int rank = (index / 2) + 1;
            int sign = (index % 2 == 0) ? -1 : 1;
            back = rank * FORMATION_SPACING;
            side = sign * rank * FORMATION_SPACING;
        }

        double x = owner.getX() - forwardX * back + rightX * side;
        double z = owner.getZ() - forwardZ * back + rightZ * side;
        return BlockPos.containing(x, owner.getY(), z);
    }

    // Point at `radius` straight out from the target through the vehicle — the ring
    // the hull should hold (standoff for armor, orbit for a gunship, break-contact
    // beyond it when retreating).
    public static BlockPos computeStandoffPoint(VehicleEntity vehicle, BlockPos targetPos, double radius) {
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
        double scale = radius / len;
        return BlockPos.containing(cx + dx * scale, vehicle.getY(), cz + dz * scale);
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

    // Package-visible: DriveVehicleGoal's vehicle-obstacle filter defines "ally"
    // with this same test, so assist doctrine and collision doctrine can't diverge.
    static boolean isSameFaction(AbstractUnit unit, AbstractUnit other) {
        if (unit instanceof RUunitEntity) return other instanceof RUunitEntity;
        if (unit instanceof USunitEntity) return other instanceof USunitEntity;
        return unit instanceof PmcUnitEntity && other instanceof PmcUnitEntity;
    }
}
