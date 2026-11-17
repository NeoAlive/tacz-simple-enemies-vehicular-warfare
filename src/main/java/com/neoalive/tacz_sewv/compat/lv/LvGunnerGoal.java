package com.neoalive.tacz_sewv.compat.lv;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleAerialBomb;
import org.ywzj.vehicle.vehicle.weapon.VehicleCannon;
import org.ywzj.vehicle.vehicle.weapon.VehicleDecoyFlare;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.support.FormationSubjects;

/**
 * Lays and fires the weapon station of whichever LV seat this unit sits in — the turret on seat 0
 * included, since an LV tank's driver seat is often the main gun.
 *
 * <p>SBW aims and fires a mob's turret inside its own tick; LV does nothing of the sort for a mob,
 * and its player path ({@code AbstractVehicleWeapon.doClientShoot}) is client-only. So this does
 * what that path does, server-side: lay the station with {@link WeaponUnit#aim} (server-safe; the
 * aim angles are synced), build the aim contexts for the station's firing mode with the impact
 * point filled in, lock homing missiles onto the target, and call {@link WeaponUnit#shoot} —
 * which is exactly where LV's own fire packet ends up, events and client effects included.
 *
 * <p>It fires only on a laid barrel, a clear line, and no friendly in the corridor, and never
 * faster than the crew AI cooldown. LV's own cooldown is wall-clock milliseconds, so ours sits in
 * front of it in game time.
 */
final class LvGunnerGoal extends Goal {

    /** How close the station must be to its laid bearing before it fires. LV's own auto-turret uses 5. */
    private static final float LAID_DEG = 5.0F;
    /** Weapons at or above this calibre (mm) count as anti-armour. */
    private static final float HEAVY_CALIBER = 20.0F;
    /** With no calibre in the data, a round this hard counts as anti-armour. */
    private static final float HEAVY_DAMAGE = 40.0F;

    private final AbstractUnit unit;

    private AbstractVehicle hull;
    private WeaponUnit station;
    private long nextShotTime = Long.MIN_VALUE;

    LvGunnerGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!LvCompat.aiEnabled()) return false;
        if (!(this.unit.getVehicle() instanceof AbstractVehicle v) || !LvHulls.isCrewable(v)) return false;
        WeaponUnit wu = LvHulls.weaponUnitOf(v, this.unit);
        if (wu == null || wu.getIndexedWeapons().isEmpty()) return false;
        if (target() == null) return false;
        this.hull = v;
        this.station = wu;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hull != null
                && this.unit.getVehicle() == this.hull
                && LvHulls.isCrewable(this.hull)
                && LvHulls.weaponUnitOf(this.hull, this.unit) == this.station
                && target() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // the station is laid every tick, as a player's mouse would
    }

    @Override
    public void stop() {
        this.hull = null;
        this.station = null;
    }

    @Nullable
    private LivingEntity target() {
        LivingEntity t = LvOrders.liveTarget(this.unit);
        return t != null && VehicleTargeting.isValidHostileTarget(this.unit, t) ? t : null;
    }

    @Override
    public void tick() {
        LivingEntity target = target();
        if (target == null) return;
        for (AbstractVehicleWeapon<?> w : this.station.getIndexedWeapons()) {
            LvSupply.issueAmmo(this.hull, this.station.proxyWeapon(w));
        }
        TargetCategory category = VehicleWeapons.classifyTarget(target);
        int index = pickWeapon(category);
        AbstractVehicleWeapon<?> weapon = index < 0 ? null : this.station.proxyWeapon(this.station.getIndexedWeapons().get(index));

        Vec3 aimPoint = aimPoint(target, weapon);
        this.station.aim(aimPoint);

        if (weapon == null || !mayFire()) return;
        WeaponUnit firing = weapon.getWeaponUnit();
        if (!laid(firing)) return;
        List<AimContext> contexts = contexts(firing, aimPoint);
        if (contexts.isEmpty()) return;
        if (!clearShot(contexts.get(0).from, aimPoint)) return;

        if (weapon instanceof VehicleMissile missile
                && missile.getData().getGuidance() == VehicleMissileWeaponData.Guidance.HOMING) {
            firing.getRootParentWeaponUnit().setLockedEntity(lockTarget(target));
        }
        this.station.shoot(index, contexts, this.unit);
        this.nextShotTime = this.unit.level().getGameTime() + EasyMode.aiFireCooldownTicks(this.unit);
    }

    /** The best ready weapon on the station for this kind of target, as an index into its list, or -1. */
    private int pickWeapon(TargetCategory category) {
        List<AbstractVehicleWeapon<?>> weapons = this.station.getIndexedWeapons();
        boolean armour = category == TargetCategory.VEHICLE;
        int best = -1;
        float bestScore = 0.0F;
        for (int i = 0; i < weapons.size(); i++) {
            AbstractVehicleWeapon<?> w = this.station.proxyWeapon(weapons.get(i));
            float score = score(w, armour);
            if (score <= 0.0F || !w.hasAmmo() || w.isReloading()) continue;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static float score(AbstractVehicleWeapon<?> w, boolean armour) {
        if (w instanceof VehicleAerialBomb || w instanceof VehicleDecoyFlare) return 0.0F;
        if (w instanceof VehicleCannon cannon) {
            if (cannon.getData().isArtillery()) return 0.0F; // indirect fire, not a direct-lay gun
            boolean heavy = heavy(w);
            return armour ? (heavy ? 2.0F : 0.5F) : (heavy ? 2.0F : 3.0F);
        }
        if (w instanceof VehicleMissile) return armour ? 3.0F : 0.5F;
        if (w instanceof VehicleRocket) return armour ? 2.5F : 1.0F;
        if (w instanceof VehicleGrenade) return armour ? 1.0F : 2.0F;
        return 1.0F; // grouped / agent stations: no better information
    }

    private static boolean heavy(AbstractVehicleWeapon<?> w) {
        Float caliber = w.getData().getCaliber();
        return caliber != null ? caliber >= HEAVY_CALIBER : w.getData().getDamage() >= HEAVY_DAMAGE;
    }

    /** Centre of what the target is riding (the hull is what gets hit), led for a ballistic weapon. */
    private Vec3 aimPoint(LivingEntity target, @Nullable AbstractVehicleWeapon<?> weapon) {
        Entity body = target.getVehicle() != null ? target.getVehicle() : target;
        Vec3 centre = body.getBoundingBox().getCenter();
        if (weapon == null || weapon instanceof VehicleMissile) return centre; // a missile steers itself
        float speed = weapon.getData().getVelocity();
        if (speed <= 0.0F) return centre;
        double flightTicks = this.station.worldPivotPosition().distanceTo(centre) / speed;
        return centre.add(body.getDeltaMovement().scale(flightTicks));
    }

    private static Entity lockTarget(LivingEntity target) {
        return target.getVehicle() != null ? target.getVehicle() : target;
    }

    private boolean mayFire() {
        if (this.unit.level().getGameTime() < this.nextShotTime) return false;
        return !(this.unit instanceof PmcUnitEntity pmc && FormationSubjects.ceaseFire(pmc));
    }

    private static boolean laid(WeaponUnit unit) {
        return Math.abs(Mth.wrapDegrees(unit.getXRot() - unit.getXAimRot())) < LAID_DEG
                && Math.abs(Mth.wrapDegrees(unit.getYRot() - unit.getYAimRot())) < LAID_DEG;
    }

    /** The station's aim contexts for its firing mode, exactly as LV's client shoot builds them. */
    private List<AimContext> contexts(WeaponUnit firing, Vec3 aimPoint) {
        List<AimContext> contexts;
        WeaponUnitData.FiringMode mode = firing.getFiringMode();
        if (mode == WeaponUnitData.FiringMode.RIPPLE) {
            contexts = new ArrayList<>(List.of(firing.aimContext()));
        } else if (mode == WeaponUnitData.FiringMode.SALVO || mode == WeaponUnitData.FiringMode.FULL_SALVO) {
            contexts = new ArrayList<>(firing.aimContexts()); // consumeAmmo may trim it, so it must be mutable
        } else {
            return List.of();
        }
        Vec3 drift = this.hull.getDeltaMovement();
        for (AimContext context : contexts) {
            context.from = context.from.add(drift);
            context.position = aimPoint;
        }
        return contexts;
    }

    /** Terrain between the muzzle and the aim point, and no friendly hull or unit in the corridor. */
    private boolean clearShot(Vec3 from, Vec3 to) {
        HitResult hit = com.neoalive.tacz_sewv.util.LoadedClip.clip(this.unit.level(), new ClipContext(from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this.unit));
        if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(to) > 4.0) return false;
        return !VehicleTargeting.alliedInLineOfFire(this.unit, this.hull, from, to);
    }
}
