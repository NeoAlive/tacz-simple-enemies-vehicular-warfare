package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo;
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

import java.util.List;
import java.util.Locale;

/**
 * Weapon doctrine for AI crews. Ground crews ({@link DriveVehicleGoal}) classify
 * the target and pick the slot for it — special/cannon against armor, range-split
 * MG/cannon against infantry. Flight crews ({@link DriveHelicopterGoal}) cycle a
 * random valid slot on a timer instead.
 *
 * <p>The doctrine reasons in terms of weapon <em>roles</em> (CANNON / MG / SPECIAL),
 * not physical slot indices. Each physical slot in a seat is classified at
 * selection time from its {@link GunData} — shell type, ammo class, projectile id
 * and weapon key — so a vehicle that lists its weapons in a non-standard order (a
 * modded hull with {@code ["MachineGun","Cannon"]}, say) is still driven correctly.
 * The {@code WEAPON_*} constants below are role ids that double as indices into the
 * role→slot map; they are NOT assumptions about a slot's position. A slot that
 * can't be classified falls back to the CANNON role (a usable direct-fire primary).
 *
 * <p>The switch cooldown stays in each goal (per-crew state); this utility is stateless.
 */
public final class VehicleWeapons {

    // Role ids. These also index the role→slot map returned by resolveRoleSlots().
    public static final int WEAPON_CANNON = 0;
    public static final int WEAPON_MG = 1;
    public static final int WEAPON_SPECIAL = 2; // TOW / heavy anti-vehicle ordnance
    private static final int WEAPON_COUNT = 3;
    private static final int UNCLASSIFIED = -1;

    // Substring hints for role classification, matched against the lowercased
    // projectile id and weapon key. SPECIAL covers guided / launched ordnance that
    // needs the anti-armor preference and the wider-cone fire assist; direct-fire
    // beams/lasers deliberately fall through to the CANNON-role fallback instead.
    private static final String[] SPECIAL_HINTS = {
            "missile", "rocket", "torpedo", "bomb", "agm", "kh_", "guide", "mortar",
            "seek", "swarm", "launcher", "fim", "tow"
    };

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

    // Ground-crew doctrine. The seat's physical slots are first mapped to roles
    // (resolveRoleSlots), then the pick is made by role so weapon ORDER on the hull
    // doesn't matter. Armored targets are engaged purely BY TARGET TYPE: the special
    // slot (TOW / heavy AT ordnance) is selected exactly while it is READY TO FIRE —
    // loaded, off reload, not overheated — and the cannon covers its 6-13 s reload
    // gaps. That is the cannon/special alternation that actually works: a fixed-timer
    // round-robin flipped the turret between the two firing solutions every few ticks
    // (visible pitch twitch) while the missile was rarely both selected and loaded at
    // a fire instant. Hulls without a special slot just stay on the cannon. Infantry
    // keeps the range split: MG in close, cannon at range, and the special is never
    // burned on soft targets.
    //
    // Every pick is bounded by the weapons the seat actually has — setWeaponIndex()
    // doesn't bounds-check, and an invalid index silently disarms the seat.
    public static void selectWeaponForTarget(VehicleEntity vehicle, int seatIndex,
                                             TargetCategory category, boolean tooFar) {
        if (seatIndex < 0) return;
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int weaponCount = seat == null ? 0 : seat.weapons().size();
        if (weaponCount == 0) return; // weaponless seat — nothing to select

        int[] slot = resolveRoleSlots(vehicle, seatIndex, weaponCount);
        int cannon = slot[WEAPON_CANNON];
        int mg = slot[WEAPON_MG];
        int special = slot[WEAPON_SPECIAL];

        if (category == TargetCategory.VEHICLE) {
            if (special >= 0 && specialReady(vehicle, seatIndex, special)) {
                vehicle.setWeaponIndex(seatIndex, special);
            } else {
                vehicle.setWeaponIndex(seatIndex, cannon >= 0 ? cannon : 0);
            }
            return;
        }

        // MONSTER / FACTION_UNIT: range-based MG/cannon split, special excluded.
        int chosen;
        if (tooFar) {
            chosen = cannon >= 0 ? cannon : (mg >= 0 ? mg : 0);
        } else {
            chosen = mg >= 0 ? mg : (cannon >= 0 ? cannon : 0);
        }
        vehicle.setWeaponIndex(seatIndex, chosen);
    }

    // Classify each physical slot in the seat and return a role→slot map (values are
    // physical weapon indices, or -1 if the seat has no weapon for that role). The
    // first physical slot matching a role wins. A slot that can't be classified is
    // held aside and, if no true cannon exists, promoted to the CANNON role so the
    // crew still has a usable direct-fire primary on exotic/modded hulls.
    private static int[] resolveRoleSlots(VehicleEntity vehicle, int seatIndex, int weaponCount) {
        int[] roleToSlot = new int[WEAPON_COUNT];
        for (int r = 0; r < WEAPON_COUNT; r++) roleToSlot[r] = -1;
        int unclassified = -1;
        for (int w = 0; w < weaponCount; w++) {
            int role = classifySlot(vehicle, seatIndex, w);
            if (role != UNCLASSIFIED) {
                if (roleToSlot[role] < 0) roleToSlot[role] = w;
            } else if (unclassified < 0) {
                unclassified = w;
            }
        }
        if (roleToSlot[WEAPON_CANNON] < 0 && unclassified >= 0) {
            roleToSlot[WEAPON_CANNON] = unclassified;
        }
        return roleToSlot;
    }

    // Classify one physical weapon slot into a role, reading the actual GunData so
    // custom weapon orderings (and most modded weapons that still populate SBW's gun
    // schema) resolve correctly. Signal order — SPECIAL (guided/launched) first so a
    // missile is never mistaken for a shell, then CANNON (shell types / cannon
    // projectiles), then MG (small-arms ammo / plain bullet). Returns UNCLASSIFIED
    // for anything else. Defensive like specialReady(): getGunData may be null and
    // this must never crash the AI tick.
    private static int classifySlot(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData gun = vehicle.getGunData(seatIndex, weaponIndex);
            String name = lower(vehicle.getGunName(seatIndex, weaponIndex));
            String shell = "";
            String projectile = "";
            Ammo ammo = null;
            if (gun != null) {
                shell = lower(gun.get(GunProp.SHELL_TYPE));
                ProjectileInfo pi = gun.get(GunProp.PROJECTILE);
                if (pi != null) projectile = lower(pi.getId());
                List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                if (consumers != null) {
                    for (AmmoConsumer c : consumers) {
                        if (c != null && c.getPlayerAmmoType() != null) {
                            ammo = c.getPlayerAmmoType();
                            break;
                        }
                    }
                }
            }

            // SPECIAL: guided / launched ordnance.
            if (matchesAny(projectile, SPECIAL_HINTS) || matchesAny(name, SPECIAL_HINTS)) {
                return WEAPON_SPECIAL;
            }
            // CANNON: a real shell type (AP/HE/GS), a cannon-shell projectile, or a
            // cannon-named slot.
            if ((!shell.isEmpty() && !shell.equals("default"))
                    || projectile.contains("shell")
                    || name.contains("cannon")) {
                return WEAPON_CANNON;
            }
            // MG: small-arms ammo class, the plain bullet projectile, or an MG-named
            // slot. Cannon/special are already handled above, so leftover heavy ammo
            // here is a heavy machine gun.
            if (ammo == Ammo.RIFLE || ammo == Ammo.HANDGUN || ammo == Ammo.SHOTGUN
                    || ammo == Ammo.SNIPER || ammo == Ammo.HEAVY
                    || projectile.equals("superbwarfare:projectile")
                    || name.contains("machinegun") || name.contains("mg")) {
                return WEAPON_MG;
            }
            return UNCLASSIFIED;
        } catch (Exception e) {
            return UNCLASSIFIED;
        }
    }

    // True while the given slot could fire right now: loaded, not reloading/charging,
    // not overheated (GunData.canShoot mirrors exactly what SBW's own fire path
    // checks). The ammo probe uses the vehicle's ammo supplier like SBW does, falling
    // back to the hull itself.
    private static boolean specialReady(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData special = vehicle.getGunData(seatIndex, weaponIndex);
            if (special == null) return false;
            Entity supplier = vehicle.getAmmoSupplier();
            return special.canShoot(supplier != null ? supplier : vehicle);
        } catch (Exception e) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(String haystack, String[] needles) {
        if (haystack.isEmpty()) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
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
}
