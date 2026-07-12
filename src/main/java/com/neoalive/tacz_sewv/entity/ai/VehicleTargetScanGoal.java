package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Target acquisition for mounted crews: a flat cylinder scan around the VEHICLE
 * (configurable radius and height) instead of the vanilla follow-range scan SEM
 * units come with. The vanilla box is capped by the unit's follow-range attribute
 * and only reaches ±4 blocks vertically — both hopeless at vehicle engagement
 * ranges, where the standoff ring alone sits at 40 blocks.
 *
 * The cylinder is the deliberate shape: full horizontal reach where ground
 * targets actually are, without paying to scan a 96-block-tall box of sky and
 * caves. Runs at priority 2 with the TARGET flag, so while a mounted crew holds
 * a target this goal owns targeting over SEM's short-range scans, but SEM's
 * HurtByTargetGoal (priority 1) still preempts for retaliation.
 */
public class VehicleTargetScanGoal extends Goal {

    // Acquired targets are only dropped past 1.5x the scan bounds, so a target
    // maneuvering along the cylinder's edge doesn't flicker in and out of lock.
    private static final double DROP_MULT = 1.5;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;
    private LivingEntity pendingTarget;
    private int scanCooldown;

    public VehicleTargetScanGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return false;
        if (!ordersAllowAutoTargets()) return false;

        LivingEntity current = this.unit.getTarget();
        if (current != null && current.isAlive()) return false; // already engaged — don't retarget every scan

        // Throttle: the AABB query + sort is the expensive part, so it only runs
        // every N ticks; the cheap mounted/target checks above still run per tick.
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();

        this.vehicle = v;
        this.pendingTarget = scanCylinder(v);
        return this.pendingTarget != null;
    }

    @Override
    public void start() {
        this.unit.setTarget(this.pendingTarget);
        this.pendingTarget = null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.vehicle == null || this.unit.getVehicle() != this.vehicle || this.vehicle.isWreck()) return false;
        if (!ordersAllowAutoTargets()) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive() || !isValidTarget(this.vehicle, target)) return false;

        double dropRadius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get() * DROP_MULT;
        double dropHalfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0 * DROP_MULT;
        return horizontalDistSq(this.vehicle, target) <= dropRadius * dropRadius
                && Math.abs(target.getY() - this.vehicle.getY()) <= dropHalfHeight;
    }

    @Override
    public void stop() {
        // Vanilla TargetGoal contract: releasing the TARGET flag clears the target,
        // so whichever target goal runs next (retaliation, order-driven) starts clean.
        this.unit.setTarget(null);
        this.vehicle = null;
        this.pendingTarget = null;
    }

    // Nearest valid enemy inside the cylinder: AABB query for the bounding box,
    // then the horizontal-distance filter rounds the corners off into a cylinder.
    // LOS raycasts (when enabled) only run down the sorted list until the first
    // visible candidate, not against everything found.
    private LivingEntity scanCylinder(VehicleEntity v) {
        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        double radiusSq = radius * radius;

        AABB bounds = new AABB(
                v.getX() - radius, v.getY() - halfHeight, v.getZ() - radius,
                v.getX() + radius, v.getY() + halfHeight, v.getZ() + radius);

        List<LivingEntity> candidates = this.unit.level().getEntitiesOfClass(LivingEntity.class, bounds, e -> {
            if (!isValidTarget(v, e)) return false;
            double distSq = horizontalDistSq(v, e);
            // Skip anything already inside the vehicle's dead zone — otherwise this
            // scan and VehicleMinRangeGoal would trade the same hugger back and forth.
            return distSq <= radiusSq && distSq >= VehicleMinRangeGoal.MIN_ENGAGE_DISTANCE_SQ;
        });

        candidates.sort(Comparator.comparingDouble(e -> horizontalDistSq(v, e)));

        boolean needLineOfSight = SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get();
        for (LivingEntity candidate : candidates) {
            if (!needLineOfSight || this.unit.getSensing().hasLineOfSight(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // Mirrors SEM's faction doctrine (from its vanilla target goals): RU/US fight
    // players, the opposing factions and hostile mobs (plus iron golems); PMC crews
    // fight RU/US and hostile mobs but never auto-target players or other PMCs.
    // AbstractUnit extends Monster, so the Enemy check covers opposing units too —
    // each branch just has to carve its own faction back out.
    private boolean isValidTarget(VehicleEntity v, LivingEntity e) {
        if (e == this.unit || !e.isAlive() || !e.isAttackable()) return false;
        if (e.getVehicle() == v) return false; // riding our own hull — crewmate or min-range hugger

        if (this.unit instanceof PmcUnitEntity) {
            return e instanceof Enemy && !(e instanceof PmcUnitEntity);
        }
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator();
        if (e instanceof IronGolem) return true;
        if (this.unit instanceof RUunitEntity) return e instanceof Enemy && !(e instanceof RUunitEntity);
        if (this.unit instanceof USunitEntity) return e instanceof Enemy && !(e instanceof USunitEntity);
        return false;
    }

    // PMC crews obey the SEM order queue: CEASE_FIRE must not pick fights, and
    // ATTACK_THAT_TARGET leaves targeting to SEM's specific-target goal. RU/US
    // crews have no orders — they always fight.
    private boolean ordersAllowAutoTargets() {
        if (!(this.unit instanceof PmcUnitEntity pmc)) return true;
        OrderType order = pmc.getOrder();
        return order != OrderType.CEASE_FIRE && order != OrderType.ATTACK_THAT_TARGET;
    }

    private static double horizontalDistSq(VehicleEntity v, LivingEntity e) {
        double dx = e.getX() - v.getX();
        double dz = e.getZ() - v.getZ();
        return dx * dx + dz * dz;
    }
}
