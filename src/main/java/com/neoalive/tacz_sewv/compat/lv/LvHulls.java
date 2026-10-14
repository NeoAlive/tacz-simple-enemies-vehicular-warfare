package com.neoalive.tacz_sewv.compat.lv;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;
import org.ywzj.vehicle.api.YwzjVehicleAPI;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.VesselVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullProfile;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Everything that needs an LV type to answer, behind {@link LvCompat}. Never reached unless LV is
 * loaded.
 *
 * <p>Two LV facts shape the seat helpers. {@code ControlUnit.operatorId} defaults to 0, not -1, so
 * on a hull nobody has driven {@code getDriver()} can resolve whatever entity has id 0 — the driver
 * is therefore read off {@code seats.get(0).passengerId}. And an LV hull's {@code getBbWidth()} is
 * its entity type's, while its real box is built from OBBs, so the size comes from the main OBB.
 */
final class LvHulls {

    /** Profiles never change for a hull's life (its data is fixed at construction). */
    private static final Map<Entity, HullProfile> PROFILES = new WeakHashMap<>();

    enum Kind { WHEELED, TRACKED, VESSEL }

    private LvHulls() {}

    static boolean isHull(Entity e) {
        return e instanceof AbstractVehicle;
    }

    @Nullable
    static Kind kindOf(Entity e) {
        if (e instanceof TrackedVehicle) return Kind.TRACKED;
        if (e instanceof WheeledVehicle) return Kind.WHEELED;
        if (e instanceof VesselVehicle) return Kind.VESSEL;
        return null; // rotary / fixed wing / NoneVehicle mounts: not ours to crew
    }

    static boolean isDestroyed(Entity e) {
        return e instanceof AbstractVehicle v && v.isDestroyed();
    }

    static boolean isCrewable(Entity e) {
        return e instanceof AbstractVehicle v && kindOf(v) != null && v.isAlive() && !v.isDestroyed()
                && v.seats != null && !v.seats.isEmpty();
    }

    /** "Written off": knocked out, or below {@code fraction} of max health. {@code setDestroyed()}
     * refills health for the wreck phase, so the health number alone would read a wreck as whole. */
    static boolean writtenOff(AbstractVehicle v, float fraction) {
        if (v.isDestroyed()) return true;
        float max = v.getMaxHealth();
        return max > 0.0F && v.getHealth() <= max * fraction;
    }

    static int seatIndexOf(AbstractVehicle v, Entity passenger) {
        if (v.seats == null) return -1;
        for (AbstractVehicle.Seat seat : v.seats) {
            if (seat.passengerId != null && seat.passengerId == passenger.getId()) return seat.seatIndex;
        }
        return -1;
    }

    /** The unit in seat 0, verified to still be aboard. Never {@code getDriver()} — see the class doc. */
    @Nullable
    static Entity seat0Occupant(AbstractVehicle v) {
        if (v.seats == null || v.seats.isEmpty()) return null;
        Integer id = v.seats.get(0).passengerId;
        if (id == null || id < 0) return null;
        Entity e = v.level().getEntity(id);
        return e != null && e.getVehicle() == v ? e : null;
    }

    static boolean hasFreeSeat(AbstractVehicle v) {
        if (v.seats == null) return false;
        for (AbstractVehicle.Seat seat : v.seats) {
            if (seat.passengerId == null || seat.passengerId == -1) return true;
        }
        return false;
    }

    /** First free seat other than the driver's, or -1. */
    static int firstFreeNonDriverSeat(AbstractVehicle v) {
        if (v.seats == null) return -1;
        for (AbstractVehicle.Seat seat : v.seats) {
            if (seat.seatIndex != 0 && (seat.passengerId == null || seat.passengerId == -1)) return seat.seatIndex;
        }
        return -1;
    }

    static boolean controlsWeapon(Mob mob) {
        if (!(mob.getVehicle() instanceof AbstractVehicle v)) return false;
        int seat = seatIndexOf(v, mob);
        if (seat == 0) return true; // the driver is always busy with the hull
        return v.getOwnOperatorUnit(mob) instanceof WeaponUnit;
    }

    @Nullable
    static WeaponUnit weaponUnitOf(AbstractVehicle v, LivingEntity passenger) {
        PartUnit<?> unit = v.getOwnOperatorUnit(passenger);
        return unit instanceof WeaponUnit wu ? wu : null;
    }

    @Nullable
    static HullProfile profileOf(Entity e) {
        if (!(e instanceof AbstractVehicle v)) return null;
        Kind kind = kindOf(v);
        if (kind == null) return null;
        HullProfile cached = PROFILES.get(v);
        if (cached != null) return cached;
        if (v.getMainCubeOBB() == null) return null; // data not initialised yet — ask again next tick
        Vector3f ext = v.getMainCubeOBB().obb().extents();
        double width = Math.max(1.0, ext.x * 2.0);
        double height = Math.max(1.0, ext.y * 2.0);
        boolean vessel = kind == Kind.VESSEL;
        HullProfile p = new HullProfile(kind == Kind.TRACKED, kind == Kind.WHEELED, vessel,
                vessel || canWade(v), Math.max(1.0F, v.maxUpStep()), width, height, Double.NaN);
        PROFILES.put(v, p);
        return p;
    }

    /** {@code canWade} is protected on the hull; the same value is public on its data. */
    private static boolean canWade(AbstractVehicle v) {
        try {
            return YwzjVehicleAPI.getVehicleDataManager().getVehicleData(v.getVehicleId())
                    .map(BaseVehicleData::canWade).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean anyIn(net.minecraft.world.level.Level level, AABB box) {
        return !level.getEntitiesOfClass(AbstractVehicle.class, box).isEmpty();
    }

    static boolean boardable(Entity e) {
        return e instanceof AbstractVehicle v && isCrewable(v) && hasFreeSeat(v);
    }

    static boolean alliedHullInCorridor(AbstractUnit shooter, Entity self, AABB corridor,
                                        Vec3 from, Vec3 to, double margin) {
        for (AbstractVehicle v : self.level().getEntitiesOfClass(AbstractVehicle.class, corridor,
                h -> h != self && h.isAlive() && !h.isDestroyed())) {
            if (!VehicleTargeting.isAlliedVehicle(shooter, v)) continue;
            if (v.getBoundingBox().inflate(margin).clip(from, to).isPresent()) return true;
        }
        return false;
    }

    /**
     * The same doctrine as {@code SeekAbandonedVehicleGoal.isAbandoned} for an LV hull: crewable,
     * completely empty, healthy enough not to bail straight back out of, and never a hull a player
     * has driven unless stealing is allowed. LV has no last-driver field, so that last test reads
     * {@link LvEvents#TAG_PLAYER_DRIVEN}, which this mod writes when a player takes seat 0.
     */
    @Nullable
    static Entity findAbandoned(AbstractUnit unit, double radius) {
        float minHealth = SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION.get().floatValue();
        boolean steals = SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES.get();
        return unit.level().getEntitiesOfClass(AbstractVehicle.class, unit.getBoundingBox().inflate(radius),
                        v -> isCrewable(v)
                                && v.getPassengers().isEmpty()
                                && !writtenOff(v, minHealth)
                                && (steals || !v.getPersistentData().getBoolean(LvEvents.TAG_PLAYER_DRIVEN)))
                .stream()
                .min(Comparator.comparingDouble(unit::distanceToSqr))
                .orElse(null);
    }
}
