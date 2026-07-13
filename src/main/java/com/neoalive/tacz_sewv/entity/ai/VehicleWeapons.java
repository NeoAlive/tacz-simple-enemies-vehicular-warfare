package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/**
 * Weapon doctrine for AI crews. Ground crews ({@link DriveVehicleGoal}) classify
 * the target and pick the slot for it — round-robin cannon/special against armor,
 * range-split MG/cannon against infantry. Flight crews
 * ({@link DriveHelicopterGoal}) cycle a random valid slot on a timer instead.
 * Slot conventions follow SBW's data files — 0 = cannon, 1 = MG, 2 = special
 * (TOW / heavy anti-vehicle ordnance). The switch cooldown stays in each goal
 * (per-crew state); this utility is stateless.
 */
public final class VehicleWeapons {

    public static final int WEAPON_CANNON = 0;
    public static final int WEAPON_MG = 1;
    public static final int WEAPON_SPECIAL = 2; // TOW / heavy anti-vehicle ordnance
    private static final int WEAPON_COUNT = 3;

    // FACTION_UNIT = SEM's RU/US faction infantry. (An actual PmcUnitEntity target
    // deliberately falls through to MONSTER — identical doctrine either way.)
    public enum TargetCategory { VEHICLE, MONSTER, FACTION_UNIT }

    private VehicleWeapons() {}

    /**
     * True when {@code mob} is crewing an SBW vehicle in a role that fires a VEHICLE
     * weapon — the driver (seat 0, who works the hull's main armament) or any gunner
     * whose seat has weapons assigned. A pure passenger seat (no weapons) returns
     * false, so those units are excluded and keep their own behaviour.
     *
     * <p>Used to suppress a crew member's hand-held gun goal: without this a mounted
     * unit with a rifle in hand and a target in range fires the rifle instead of (or
     * alongside) the vehicle's guns.
     */
    public static boolean controlsVehicleWeapon(Mob mob) {
        if (!(mob.getVehicle() instanceof VehicleEntity vehicle)) return false;
        if (vehicle.getFirstPassenger() == mob) return true; // driver — always busy driving
        SeatInfo seat = vehicle.getSeat(mob);
        return seat != null && !seat.weapons().isEmpty();     // gunner; passenger → false
    }

    public static TargetCategory classifyTarget(LivingEntity target) {
        // A VehicleEntity isn't a LivingEntity, so it can never be the target itself
        // the AI targets the crew riding inside; armor makes the MG useless against them.
        if (target.getVehicle() instanceof VehicleEntity) return TargetCategory.VEHICLE;
        if (target instanceof RUunitEntity || target instanceof USunitEntity) return TargetCategory.FACTION_UNIT;
        return TargetCategory.MONSTER; // vanilla hostiles + fallback default
    }

    // Ground-crew doctrine. Armored targets are engaged purely BY TARGET TYPE:
    // the special slot (TOW / heavy AT ordnance) is selected exactly while it is
    // READY TO FIRE — loaded, off reload, not overheated — and the cannon covers
    // its 6-13 s reload gaps. That is the cannon/special alternation that actually
    // works: a fixed-timer round-robin flipped the turret between the two firing
    // solutions every few ticks (visible pitch twitch) while the missile was
    // rarely both selected and loaded at a fire instant. Hulls without a special
    // slot just stay on the cannon. Infantry keeps the range split: MG in close,
    // cannon at range, and the special is never burned on soft targets.
    //
    // Every pick is bounded by the weapons the seat actually has — setWeaponIndex()
    // doesn't bounds-check, and an invalid index silently disarms the seat.
    public static void selectWeaponForTarget(VehicleEntity vehicle, int seatIndex,
                                             TargetCategory category, boolean tooFar) {
        if (seatIndex < 0) return;
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int weaponCount = seat == null ? 0 : seat.weapons().size();
        if (weaponCount == 0) return; // weaponless seat — nothing to select

        if (category == TargetCategory.VEHICLE) {
            boolean useSpecial = weaponCount > WEAPON_SPECIAL && specialReady(vehicle, seatIndex);
            vehicle.setWeaponIndex(seatIndex, useSpecial ? WEAPON_SPECIAL : WEAPON_CANNON);
            return;
        }

        // MONSTER / FACTION_UNIT: range-based MG/cannon split, special excluded.
        double[] weight = new double[WEAPON_COUNT];
        weight[WEAPON_SPECIAL] = Double.NEGATIVE_INFINITY;
        weight[tooFar ? WEAPON_CANNON : WEAPON_MG] = 1.0;
        if (weaponCount <= WEAPON_MG) {
            weight[WEAPON_MG] = Double.NEGATIVE_INFINITY;
        }
        vehicle.setWeaponIndex(seatIndex, argmax(weight));
    }

    // True while the seat's special slot could fire right now: loaded, not
    // reloading/charging, not overheated (GunData.canShoot mirrors exactly what
    // SBW's own fire path checks). The ammo probe uses the vehicle's ammo
    // supplier like SBW does, falling back to the hull itself.
    private static boolean specialReady(VehicleEntity vehicle, int seatIndex) {
        try {
            GunData special = vehicle.getGunData(seatIndex, WEAPON_SPECIAL);
            if (special == null) return false;
            Entity supplier = vehicle.getAmmoSupplier();
            return special.canShoot(supplier != null ? supplier : vehicle);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * AI fire assist: fire the seat's selected weapon at {@code target} through the
     * same public path SBW's own AI trigger uses, but within {@code coneDeg} of the
     * straight muzzle-target line instead of SBW's hard-coded 4°. That 4° gate is
     * measured against the barrel's actual direction, and the turret auto-aim
     * points at the BALLISTIC solution — a velocity-3 missile lofts ~6°+ above the
     * line at standoff range, so it can never pass natively; a helicopter's
     * hull-fixed weapons rarely hold a 4° line either. Fires on the same
     * {@code tickCount % ceil(1200/rpm)} instants as SBW's loop, so a same-instant
     * duplicate from the native path is absorbed by the per-vehicle AI fire
     * cooldown in MixinVehicleFireCooldown; canShoot() carries every hold-fire
     * gate (ammo/reload plus this mod's CEASE_FIRE, cooldown, LOS and smoke).
     *
     * @return true if a shot was fired
     */
    public static boolean tryAiFireAssist(VehicleEntity vehicle, AbstractUnit unit,
                                          LivingEntity target, double coneDeg) {
        try {
            int rpm = Math.max(1, vehicle.vehicleWeaponRpm(unit));
            int interval = Math.max(1, (int) Math.ceil(1200.0F / rpm));
            if (vehicle.tickCount % interval != 0) return false;
            if (!vehicle.canShoot(unit)) return false;

            Vec3 shootDir = vehicle.getShootDirectionForHud(unit, 1.0F);
            Vec3 toTarget = target.getBoundingBox().getCenter()
                    .subtract(vehicle.getShootPos(unit, 1.0F));
            if (shootDir.lengthSqr() < 1.0E-6 || toTarget.lengthSqr() < 1.0E-6) return false;

            double cos = shootDir.normalize().dot(toTarget.normalize());
            double angleDeg = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
            if (angleDeg >= coneDeg) return false;

            vehicle.vehicleShoot(unit, target.getUUID(), null);
            return true;
        } catch (Exception e) {
            return false; // missing seat/weapon data — never let the assist crash the AI tick
        }
    }

    /**
     * Gunship doctrine ({@link DriveHelicopterGoal}): pick a RANDOM slot among the
     * weapons the seat actually has, regardless of the target's type. Bounded by
     * the seat's real weapon count for the same reason as above — setWeaponIndex()
     * doesn't bounds-check and an invalid index silently disarms the seat.
     */
    public static void selectRandomWeapon(VehicleEntity vehicle, int seatIndex, RandomSource random) {
        if (seatIndex < 0) return;
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int weaponCount = seat == null ? 0 : seat.weapons().size();
        if (weaponCount <= 0) return;
        vehicle.setWeaponIndex(seatIndex, random.nextInt(weaponCount));
    }

    private static int argmax(double[] weight) {
        int best = 0;
        for (int i = 1; i < weight.length; i++) {
            if (weight[i] > weight[best]) best = i;
        }
        return best;
    }
}
