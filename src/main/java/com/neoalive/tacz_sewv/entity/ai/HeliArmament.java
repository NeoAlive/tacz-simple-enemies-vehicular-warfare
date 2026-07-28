package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo;
import com.atsuishio.superbwarfare.data.gun.SeekWeaponInfo;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pilot-seat armament doctrine for helicopter combat: which weapons the driver can fire at
 * <em>ground</em> targets, vs air-only lock missiles the picker must never select for that job.
 *
 * <p>SBW has no dedicated air/ground flag. Air-only is proxied primarily by
 * {@code SeekWeaponInfo.minTargetHeight > 0} (player seek height floor), with ammo-id /
 * projectile-id fallbacks for addon packs that omit the seek block.
 */
public final class HeliArmament {

    public enum Kind {
        /** Pilot seat, real gun, usable against ground (rockets, AG missiles, nose cannon). */
        GROUND_USABLE,
        /** Pilot seat, real gun, AA lock proxy — never pick vs ground. */
        AIR_ONLY,
        /** Zero-velocity / missing gun data — skip. */
        PLACEHOLDER,
        /** Real gun on a non-driver seat (e.g. mi_28 cannon) — pilot cannot fire it. */
        UNREACHABLE
    }

    /**
     * Pure slot signals for headless self-checks (no world / GunData).
     *
     * @param minTargetHeight {@code SeekWeaponInfo.MinTargetHeight}, or 0 when absent
     */
    public record Signals(String projectileId, String ammoId, double minTargetHeight, boolean hasSeekInfo) {}

    /** One classified pilot-seat candidate for {@link #pickFromCandidates}. */
    public record Candidate(int slot, Kind kind, boolean guided, boolean ready) {}

    private HeliArmament() {}

    /**
     * Classify a weapon from readable signals. Does not know seat ownership —
     * {@link #classifyPilotSlot} applies {@link Kind#UNREACHABLE} for non-driver seats.
     */
    public static Kind classifySignals(Signals s, boolean realWeapon) {
        if (!realWeapon) return Kind.PLACEHOLDER;
        if (isAirOnly(s)) return Kind.AIR_ONLY;
        return Kind.GROUND_USABLE;
    }

    public static boolean isGuidedProjectile(@Nullable String projectileId) {
        // Id-only path — headless-safe and matches stock SBW projectiles.
        return VehicleMissileAim.modeOfProjectileId(projectileId) != null;
    }

    /**
     * Pick a pilot-seat ground weapon for {@code target}.
     * Armor → prefer ready guided AG; soft → prefer ready unguided; never AIR_ONLY.
     *
     * @return physical slot index, or -1 when nothing ground-usable exists
     */
    public static int pickGroundWeapon(VehicleEntity vehicle, int seatIndex, LivingEntity target) {
        List<Candidate> cands = scanPilotCandidates(vehicle, seatIndex);
        boolean armor = target.getVehicle() instanceof VehicleEntity;
        return pickFromCandidates(cands, armor);
    }

    /**
     * Pure picker used by the self-check and by {@link #pickGroundWeapon}.
     * Among {@link Kind#GROUND_USABLE} only.
     */
    public static int pickFromCandidates(List<Candidate> cands, boolean armorTarget) {
        List<Candidate> ground = new ArrayList<>();
        for (Candidate c : cands) {
            if (c.kind == Kind.GROUND_USABLE) ground.add(c);
        }
        if (ground.isEmpty()) return -1;

        if (armorTarget) {
            int guided = firstReady(ground, true);
            if (guided >= 0) return guided;
            // Latch guided AG even while reloading — do NOT fall through to ready rockets
            // against armor (that was the live mi_28 vs-tank divergence from the self-check).
            for (Candidate c : ground) {
                if (c.guided) return c.slot;
            }
            int unguided = firstReady(ground, false);
            if (unguided >= 0) return unguided;
        } else {
            int unguided = firstReady(ground, false);
            if (unguided >= 0) return unguided;
            for (Candidate c : ground) {
                if (!c.guided) return c.slot;
            }
            int guided = firstReady(ground, true);
            if (guided >= 0) return guided;
        }
        return ground.get(0).slot;
    }

    public static Kind classifyPilotSlot(VehicleEntity vehicle, int driverSeat, int seatIndex, int weaponIndex) {
        if (seatIndex != driverSeat) {
            if (!VehicleWeapons.isRealWeapon(vehicle, seatIndex, weaponIndex)) return Kind.PLACEHOLDER;
            return Kind.UNREACHABLE;
        }
        if (!VehicleWeapons.isRealWeapon(vehicle, seatIndex, weaponIndex)) return Kind.PLACEHOLDER;
        return classifySignals(readSignals(vehicle, seatIndex, weaponIndex), true);
    }

    private static int firstReady(List<Candidate> ground, boolean wantGuided) {
        for (Candidate c : ground) {
            if (c.guided == wantGuided && c.ready) return c.slot;
        }
        return -1;
    }

    private static List<Candidate> scanPilotCandidates(VehicleEntity vehicle, int seatIndex) {
        List<Candidate> out = new ArrayList<>();
        SeatInfo seat = vehicle.getSeat(seatIndex);
        int n = seat == null ? 0 : seat.weapons().size();
        for (int w = 0; w < n; w++) {
            boolean real = VehicleWeapons.isRealWeapon(vehicle, seatIndex, w);
            Signals sig = readSignals(vehicle, seatIndex, w);
            Kind kind = classifySignals(sig, real);
            if (kind == Kind.PLACEHOLDER) continue;
            boolean guided = kind == Kind.GROUND_USABLE && isGuidedProjectile(sig.projectileId);
            boolean ready = kind != Kind.AIR_ONLY && slotReady(vehicle, seatIndex, w);
            out.add(new Candidate(w, kind, guided, ready));
        }
        return out;
    }

    static Signals readSignals(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData gun = vehicle.getGunData(seatIndex, weaponIndex);
            if (gun == null) return new Signals("", "", 0.0, false);
            String projectile = "";
            ProjectileInfo pi = gun.get(GunProp.PROJECTILE);
            if (pi != null && pi.getId() != null) projectile = pi.getId();
            String ammoId = firstAmmoId(gun);
            SeekWeaponInfo seek = gun.get(GunProp.SEEK_WEAPON_INFO);
            double minH = 0.0;
            boolean hasSeek = seek != null;
            if (seek != null) minH = seek.getMinTargetHeight();
            return new Signals(projectile, ammoId, minH, hasSeek);
        } catch (Exception e) {
            return new Signals("", "", 0.0, false);
        }
    }

    private static String firstAmmoId(GunData gun) {
        try {
            List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
            if (consumers == null) return "";
            for (AmmoConsumer c : consumers) {
                if (c == null) continue;
                String id = c.getAmmo();
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static boolean isAirOnly(Signals s) {
        if (s.hasSeekInfo && s.minTargetHeight > 0.0) return true;
        String ammo = lower(s.ammoId);
        if (ammo.contains("anti_air")) return true;
        String proj = lower(s.projectileId);
        return proj.contains("ru_9m336") || proj.contains("igla");
    }

    private static boolean slotReady(VehicleEntity vehicle, int seatIndex, int weaponIndex) {
        try {
            GunData gun = vehicle.getGunData(seatIndex, weaponIndex);
            if (gun == null) return false;
            Entity supplier = vehicle.getAmmoSupplier();
            return gun.canShoot(supplier != null ? supplier : vehicle);
        } catch (Exception e) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
