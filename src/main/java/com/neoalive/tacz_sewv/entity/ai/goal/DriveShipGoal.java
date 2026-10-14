package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.ShipHelm;
import com.neoalive.tacz_sewv.entity.ai.core.StalemateBreaker;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.support.DecoyEpisode;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.WaterSupport;
import com.neoalive.tacz_sewv.invasion.CaptureOrderSupport;
import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Drives a ship. Separate from {@link DriveVehicleGoal} because a hull that floats handles nothing
 * like one that rolls: it steers only while making way (SBW scales yaw change by current speed),
 * it cannot pivot in place, it dies outright if it beaches, and every destination it is given has
 * to be pulled back onto water first.
 *
 * <p>The manoeuvre that replaces a tank's turn-in-place is <b>back and fill</b>: when the bearing
 * is behind the beam or the bow is fouled, reverse with the rudder over, which swings the bow
 * around, then drive out of it.
 *
 * <p>The steering itself lives in {@link ShipHelm} (shared with the Limitless Vehicles boat goal);
 * this goal decides where the hull goes and works its weapons and smoke.
 */
public class DriveShipGoal extends Goal {

    private static final int WEAPON_SWITCH_COOLDOWN_TICKS = 5;

    /** Engagement band for boat guns — close enough to shoot, far enough not to ram. */
    private static final double TOO_CLOSE = 12.0;
    private static final double TOO_FAR = 30.0;
    private static final double VEHICLE_RING = 36.0;
    private static final double RING_DEADBAND = 5.0;

    private static final float PRESERVE_HEALTH_FRACTION = 0.25F;
    private static final double PRESERVE_RETREAT_MARGIN = 10.0;
    private static final float PRESERVE_SMOKE_CHANCE = 0.5F;

    /** Re-projecting a destination onto water is a spiral scan; only redo it when it moves. */
    private static final double REPROJECT_DRIFT_SQ = 25.0;

    private final AbstractUnit unit;
    private final HullFacts hull = new HullFacts();
    private final ShipHelm helm;
    private final StalemateBreaker breaker;
    private final DecoyEpisode smoke = new DecoyEpisode();
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    private VehicleEntity vehicle;

    private BlockPos rawDestination;
    private BlockPos waterDestination;

    private int weaponSwitchCooldown;
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;

    public DriveShipGoal(AbstractUnit unit) {
        this.unit = unit;
        this.helm = new ShipHelm(unit);
        this.breaker = new StalemateBreaker(unit);
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        if (v.getFirstPassenger() != this.unit) return false;
        this.hull.attach(v);
        if (!this.hull.isShip()) return false;
        this.vehicle = v;
        this.helm.attach(v);
        this.breaker.attach(v);
        return getTargetPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && getTargetPos() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            this.helm.stopEngines();
            this.vehicle.setDecoyInputDown(false);
        }
        this.vehicle = null;
        this.rawDestination = null;
        this.waterDestination = null;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
        this.allyAssist.clear();
        this.helm.clear();
        this.breaker.clear();
    }

    @Override
    public void tick() {
        HudNotify.watchPmcVehicle(this.unit, this.vehicle);
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        this.helm.tickTimers();

        LivingEntity target = this.unit.getTarget();
        if (target == null || !isLowHealth() || !this.smoke.isDeploying()) {
            this.vehicle.setDecoyInputDown(false);
        }

        BlockPos dest = getTargetPos();
        if (dest == null) {
            this.helm.stopEngines();
            return;
        }

        if (target != null) {
            // MOVE (and area/capture holds) keep the ordered waypoint; only hurt crews break off.
            if (VehicleTargeting.holdsOrderedMove(this.unit)
                    || PatrolSupport.holdsCourseThroughContact(this.unit)
                    || CaptureOrderSupport.holdsCourseThroughContact(this.unit)) {
                if (!isLowHealth()) {
                    selectWeaponForTarget(target);
                    if (this.selectedRole == VehicleWeapons.WEAPON_SPECIAL) {
                        VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                                EasyMode.aiFireAssistConeDeg());
                    }
                    // Fall through to transit / park on dest below.
                } else {
                    fightTick(target, dest);
                    return;
                }
            } else {
                // dest is already the target's position pulled onto water (resolveDestination returns
                // the target for every order that fights), so combat reuses it rather than re-running
                // the projection scan every tick.
                fightTick(target, dest);
                return;
            }
        }

        double dx = dest.getX() + 0.5 - this.vehicle.getX();
        double dz = dest.getZ() + 0.5 - this.vehicle.getZ();
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.vehicle);
        if (dx * dx + dz * dz > arrive * arrive) {
            this.helm.navigateTo(dest);
        } else {
            this.helm.stopEngines();
            this.helm.clearRecovery();
        }
    }

    /**
     * Standoff band, retreat when hurt, weapons on the whole time. Range is measured to the
     * TARGET but steering goes to {@code combatPos} (the same point pulled onto water) — a target
     * ashore is unreachable, so the hull closes on the range that matters and holds the water off
     * it rather than aiming at a spot it would run aground on.
     */
    private void fightTick(LivingEntity target, BlockPos combatPos) {
        BlockPos targetPos = target.blockPosition();
        double dist = Math.sqrt(this.vehicle.distanceToSqr(
                targetPos.getX() + 0.5, this.vehicle.getY(), targetPos.getZ() + 0.5));

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        boolean armour = category == TargetCategory.VEHICLE;
        double ring = armour ? VEHICLE_RING : TOO_FAR;

        selectWeaponForTarget(target);
        if (this.selectedRole == VehicleWeapons.WEAPON_SPECIAL) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    EasyMode.aiFireAssistConeDeg());
        }

        if (isLowHealth()) {
            preserveRetreat(target, ring);
            return;
        }

        BlockPos orbit = this.breaker.update(target, targetPos, ring);
        if (orbit != null) {
            BlockPos wet = waterNear(orbit);
            this.helm.navigateTo(wet != null ? wet : combatPos);
            return;
        }

        if (dist > ring + RING_DEADBAND) {
            this.helm.navigateTo(combatPos);
        } else if (dist < (armour ? ring - RING_DEADBAND : TOO_CLOSE)) {
            this.helm.openDistance(targetPos, ring);
        } else {
            // On station: hold way on so the turret has a stable platform, but stop closing.
            this.helm.stopEngines();
            this.helm.clearRecovery();
        }
    }

    private void preserveRetreat(LivingEntity threat, double ring) {
        if (this.smoke.roll(this.unit.level().getGameTime(), this.unit.getRandom(), PRESERVE_SMOKE_CHANCE)
                && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
        }
        BlockPos threatPos = threat.blockPosition();
        double dist = Math.sqrt(this.vehicle.distanceToSqr(
                threatPos.getX() + 0.5, this.vehicle.getY(), threatPos.getZ() + 0.5));
        if (dist > ring + PRESERVE_RETREAT_MARGIN) {
            this.helm.stopEngines();
            this.helm.clearRecovery();
            return;
        }
        this.helm.openDistance(threatPos, ring + PRESERVE_RETREAT_MARGIN);
    }

    private boolean isLowHealth() {
        float max = this.vehicle.getMaxHealth();
        return max > 0.0F && this.vehicle.getHealth() < max * PRESERVE_HEALTH_FRACTION;
    }

    private void selectWeaponForTarget(LivingEntity target) {
        int seat = this.vehicle.getSeatIndex(this.unit);
        if (seat < 0 || this.weaponSwitchCooldown > 0) return;
        this.selectedRole = VehicleWeapons.selectWeaponForTarget(
                this.vehicle, seat, target, this.unit).role;
        this.weaponSwitchCooldown = WEAPON_SWITCH_COOLDOWN_TICKS;
    }

    /**
     * The order destination, pulled onto navigable water. Orders point at people and places on
     * land — a followed commander standing on a beach, a patrol waypoint inland — and steering a
     * hull at one of those is how it runs aground. Cached: the projection is a spiral scan.
     */
    private BlockPos getTargetPos() {
        BlockPos raw = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (raw == null) {
            this.rawDestination = null;
            this.waterDestination = null;
            return null;
        }
        if (this.rawDestination == null || this.rawDestination.distSqr(raw) > REPROJECT_DRIFT_SQ) {
            this.rawDestination = raw;
            this.waterDestination = WaterSupport.projectToWater(this.unit.level(), raw);
        }
        return this.waterDestination;
    }

    private BlockPos waterNear(BlockPos pos) {
        return WaterSupport.projectToWater(this.unit.level(), pos);
    }
}
