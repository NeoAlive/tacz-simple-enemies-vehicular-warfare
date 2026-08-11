package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.util.WarnOnce;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Everything a fixed-wing crew does with its weapons: what it carries, what it picks, where it has
 * to point, and whether it may pull the trigger yet.
 *
 * <p>This class exists because of one number. The old goal fired inside a hard-coded 45 degree cone
 * on the reasoning that splash damage would cover the error. It does not: a miss lands roughly
 * {@code range x tan(angle)} away from the aim point, so at 45 degrees the miss distance is the
 * range itself — a shot taken 60 blocks out could legitimately land 60 blocks wide and still count
 * as "fired within the cone". That is the reported "fires 50 blocks from the target", and no amount
 * of splash covers it. SBW's own mob gate is 4 degrees for exactly this reason.
 *
 * <p>So the doctrine is inverted: hold fire until the nose is genuinely on the aim point, and make
 * the aim point right. Right means the <b>predicted intercept</b> — solved in {@link PlaneNav} —
 * using the projectile's real velocity and gravity read from SBW's own gun data, not a guessed
 * constant. Two details from that data are load-bearing:
 *
 * <ul>
 * <li>{@code GunProp.GRAVITY} defaults to 0.05, which is a lie for every guided missile: a missile
 *     that steers itself has no ballistic drop to compensate, and lofting the nose for one is how
 *     the turret path already learned to miss. Missiles are aimed flat, at the target, and their
 *     own guidance does the rest.</li>
 * <li>{@code GunProp.VELOCITY} is 0 on placeholder slots that addon packs ship. A zero-velocity
 *     weapon is unfirable, and selecting one silently disarms the aircraft for the whole pass.</li>
 * </ul>
 *
 * <p>Selection is deterministic. The old weighted random meant an identical situation produced a
 * different weapon each pass, which makes a reported miss impossible to reproduce and gave tanks
 * the occasional cannon strafe for no reason.
 */
public final class PlaneWeapons {

    private static final List<String> FALLBACK_MISSILE_CLUES =
            List.of("missile", "agm", "kh_", "atgm", "maverick");
    private static final List<String> FALLBACK_BOMB_CLUES = List.of("bomb");
    private static final List<String> FALLBACK_ROCKET_CLUES = List.of("rocket", "hydra");

    /** Fallback ballistic gravity when the datapack does not say — SBW's own schema default. */
    private static final double DEFAULT_GRAVITY = 0.05;
    /** Fallback projectile speed (blocks/tick) when gun data is unreadable. */
    private static final double DEFAULT_VELOCITY = 10.0;
    /** A bomb is released when its predicted impact is this close to the aim point. */
    private static final double BOMB_HIT_TOLERANCE = 3.0;
    /** The bomb sim gives up after this long; a climbing aircraft never brings one down. */
    private static final int BOMB_SIM_MAX_TICKS = 200;
    /** Bombs are only released with the nose roughly along the flight path, never in a hard turn. */
    private static final double BOMB_MAX_TRACK_ERROR_DEG = 15.0;

    /** What a slot is, which decides how it is aimed and how heavy a target it is worth using. */
    public enum Kind {
        /** Light forward gun — the fallback for anything unrecognised, so nothing is unusable. */
        CANNON(1),
        ROCKET(2),
        MISSILE(3),
        BOMB(3);

        private final int tier;

        Kind(int tier) {
            this.tier = tier;
        }

        public int tier() {
            return this.tier;
        }

        public boolean guided() {
            return this == MISSILE;
        }
    }

    private record Weapon(int slot, Kind kind, String name) {}

    private final VehicleEntity vehicle;
    private final AbstractUnit unit;
    private final List<Weapon> weapons = new ArrayList<>();
    private boolean scanned;

    private Weapon selected;
    private boolean droppedThisRun;

    public PlaneWeapons(VehicleEntity vehicle, AbstractUnit unit) {
        this.vehicle = vehicle;
        this.unit = unit;
    }

    /** A different hull carries different weapons — forget everything. */
    public void reset() {
        this.scanned = false;
        this.weapons.clear();
        this.selected = null;
        this.droppedThisRun = false;
    }

    /** New attack run: one payload per pass, and re-pick for whatever we are attacking now. */
    public void beginRun(LivingEntity target) {
        this.droppedThisRun = false;
        select(targetTier(target));
    }

    /**
     * Make sure something is chosen before a shot of opportunity outside an attack run. Without
     * this the transit path aims and gates against a null selection, which reads as "cannon" and
     * quietly never fires the thing the aircraft is actually carrying.
     */
    public void ensureSelected(LivingEntity target) {
        if (this.selected == null) select(targetTier(target));
    }

    public boolean hasBombSelected() {
        return this.selected != null && this.selected.kind() == Kind.BOMB;
    }

    @Nullable
    public Kind selectedKind() {
        return this.selected == null ? null : this.selected.kind();
    }

    /**
     * Classify the seat's weapons once, by name clue. SBW hulls do not order their weapons
     * consistently and carry no type metadata worth trusting, so this is the same clue approach the
     * IFV test uses — with placeholder slots dropped outright, since a zero-velocity slot cannot
     * fire and picking one disarms the pass.
     */
    private void scan() {
        this.scanned = true;
        this.weapons.clear();
        Level level = this.vehicle.level();
        try {
            int seat = this.vehicle.getSeatIndex(this.unit);
            var info = this.vehicle.getSeat(seat);
            int count = info == null ? 0 : info.weapons().size();
            for (int w = 0; w < count; w++) {
                if (!VehicleWeapons.isRealWeapon(this.vehicle, seat, w)) continue;
                String raw = this.vehicle.getGunName(seat, w);
                String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
                Kind kind;
                if (matchesAny(name, cues(level, CueKind.PLANE_MISSILE, FALLBACK_MISSILE_CLUES))) {
                    kind = Kind.MISSILE;
                } else if (matchesAny(name, cues(level, CueKind.PLANE_BOMB, FALLBACK_BOMB_CLUES))) {
                    kind = Kind.BOMB;
                } else if (matchesAny(name, cues(level, CueKind.PLANE_ROCKET, FALLBACK_ROCKET_CLUES))) {
                    kind = Kind.ROCKET;
                } else {
                    kind = Kind.CANNON;
                }
                this.weapons.add(new Weapon(w, kind, raw));
            }
        } catch (Exception e) {
            WarnOnce.warn(SewvDiag.LOG, "plane-weapons:" + this.vehicle.getId(),
                    "Failed to classify plane weapons for "
                            + this.vehicle.getType().getDescriptionId(), e);
        }
    }

    /**
     * Heaviest weapon that suits the target and is actually loaded. Deterministic: same situation,
     * same choice, so a reported miss can be reproduced. Loaded-ness matters because an expended
     * bomb rack would otherwise be re-selected every pass and the aircraft would fly attack after
     * attack without firing anything.
     */
    private void select(int tierCap) {
        if (!this.scanned) scan();
        Weapon best = null;
        Weapon bestUnready = null;
        for (Weapon w : this.weapons) {
            if (w.kind().tier() > tierCap) continue;
            if (ready(w)) {
                if (best == null || w.kind().tier() > best.kind().tier()) best = w;
            } else if (bestUnready == null || w.kind().tier() > bestUnready.kind().tier()) {
                bestUnready = w;
            }
        }
        // Nothing loaded in the allowed tiers: fall back to the lightest thing we have rather than
        // leaving the seat pointed at whatever index it happened to hold.
        if (best == null) {
            for (Weapon w : this.weapons) {
                if (!ready(w)) continue;
                if (best == null || w.kind().tier() < best.kind().tier()) best = w;
            }
        }
        this.selected = best != null ? best : bestUnready;
    }

    private boolean ready(Weapon w) {
        try {
            GunData gun = VehicleWeapons.gunData(this.vehicle, this.vehicle.getSeatIndex(this.unit),
                    w.slot());
            if (gun == null) return false;
            Entity supplier = this.vehicle.getAmmoSupplier();
            return gun.canShoot(supplier != null ? supplier : this.vehicle);
        } catch (Exception e) {
            // Unreadable gun data must never take a weapon out of service — firing at an empty
            // rack costs a pass; refusing to fire a loaded one costs the whole sortie.
            return true;
        }
    }

    /** Vehicles draw the heavy tier, faction infantry the medium, everything else the gun. */
    private static int targetTier(LivingEntity target) {
        return switch (VehicleWeapons.classifyTarget(target)) {
            case VEHICLE -> Kind.MISSILE.tier();
            case FACTION_UNIT -> Kind.ROCKET.tier();
            default -> Kind.CANNON.tier();
        };
    }

    /** Point the seat at the chosen slot. Must be re-asserted: SBW does not remember for us. */
    public void arm() {
        if (this.selected == null) return;
        try {
            this.vehicle.setWeaponIndex(this.vehicle.getSeatIndex(this.unit), this.selected.slot());
        } catch (Exception ignored) {
            // setWeaponIndex does not bounds-check; a hull that changed under us must not crash.
        }
    }

    /**
     * Where the nose has to point for the shot to arrive. A guided missile is aimed straight at the
     * target — it steers itself, and lofting it for a drop it does not have is how the turret path
     * already learned to shoot over things. Everything else gets the full intercept solution.
     */
    public Vec3 aimPoint(LivingEntity target) {
        Vec3 targetCentre = target.getBoundingBox().getCenter();
        if (this.selected == null) return targetCentre;
        if (this.selected.kind().guided() || this.selected.kind() == Kind.BOMB) {
            return targetCentre;
        }
        Vec3 muzzle = shootPos();
        return PlaneNav.interceptPoint(muzzle, targetCentre, target.getDeltaMovement(),
                projectileVelocity(), projectileGravity());
    }

    /**
     * Fire if the nose is genuinely on the aim point. The cone is a config knob rather than a
     * constant because it is the single number that decides whether this AI hits anything, and the
     * NPC floor that every ground crew gets is deliberately <b>not</b> applied — a 35 degree floor
     * on a fixed-wing gun is the bug this class exists to remove.
     *
     * @return true if a shot went out
     */
    public boolean fire(LivingEntity target, Vec3 aimPoint) {
        double cone = this.selected != null && this.selected.kind().guided()
                ? SewvConfig.PLANE_MISSILE_CONE_DEG.get()
                : SewvConfig.PLANE_GUN_CONE_DEG.get();
        VehicleWeapons.FireGate gate = VehicleWeapons.tryAiFireAssistResult(
                this.vehicle, this.unit, target, aimPoint, cone, false);
        if (gate == VehicleWeapons.FireGate.FIRED) {
            SewvDiag.plane("FIRED kind={} cone={}",
                    this.selected == null ? "none" : this.selected.kind(), cone);
        } else {
            // Throttled: the refusals are one per tick for the whole run, and drowning the log in
            // them is how the one line that says the shot went out gets lost.
            SewvDiag.planeThrottled(this.vehicle.level().getGameTime(),
                    "holding fire kind={} cone={} gate={}",
                    this.selected == null ? "none" : this.selected.kind(), cone, gate);
        }
        return gate == VehicleWeapons.FireGate.FIRED;
    }

    /**
     * Release a bomb when one dropped this instant would land on the target. Continuous prediction
     * rather than a fixed release range, so it self-adjusts across level, diving and low passes.
     *
     * <p>The simulation starts at the actual bomb bay ({@code getShootPos}) and uses the weapon's
     * own velocity and gravity from gun data. SBW launches a delta-movement weapon at
     * {@code |velocity| * VELOCITY} along the aircraft's velocity vector, so the initial velocity
     * is simply the hull's own scaled by that factor.
     *
     * @return true when a bomb was released this tick
     */
    public boolean releaseBombIfOnTarget(LivingEntity target, Vec3 forwardFlat) {
        if (this.droppedThisRun || this.selected == null || this.selected.kind() != Kind.BOMB) {
            return false;
        }
        // A bomb thrown out of a hard turn inherits a sideways velocity the prediction models but
        // the aircraft cannot hold; wait for the wings to settle rather than lob one off the beam.
        Vec3 vel = this.vehicle.getDeltaMovement();
        Vec3 track = new Vec3(vel.x, 0.0, vel.z);
        if (track.lengthSqr() > 1.0E-6
                && PlaneNav.headingErrorDeg(forwardFlat, track) > BOMB_MAX_TRACK_ERROR_DEG) {
            return false;
        }
        if (!bombWouldHit(target)) return false;
        try {
            this.vehicle.vehicleShoot(this.unit, this.selected.name());
        } catch (Exception e) {
            return false;
        }
        this.droppedThisRun = true;
        return true;
    }

    private boolean bombWouldHit(LivingEntity target) {
        Vec3 pos = shootPos();
        Vec3 vel = this.vehicle.getDeltaMovement().scale(projectileVelocity(1.0));
        double gravity = projectileGravity(0.06);
        double impactY = target.getY();
        double tx = target.getX();
        double tz = target.getZ();
        for (int t = 0; t < BOMB_SIM_MAX_TICKS; t++) {
            pos = pos.add(vel);
            vel = new Vec3(vel.x, vel.y - gravity, vel.z);
            if (pos.y <= impactY) {
                double dx = pos.x - tx;
                double dz = pos.z - tz;
                return dx * dx + dz * dz <= BOMB_HIT_TOLERANCE * BOMB_HIT_TOLERANCE;
            }
        }
        return false; // never comes down in the window — hold the bomb
    }

    private Vec3 shootPos() {
        try {
            return this.vehicle.getShootPos(this.unit, 1.0F);
        } catch (Exception e) {
            return this.vehicle.position();
        }
    }

    private double projectileVelocity() {
        return projectileVelocity(DEFAULT_VELOCITY);
    }

    private double projectileVelocity(double fallback) {
        Double v = prop(GunProp.VELOCITY);
        return v == null || v <= 0.0 ? fallback : v;
    }

    private double projectileGravity() {
        // A guided missile has no drop to lead: MissileProjectile.getGravity() returns 0 whatever
        // the datapack says, and compensating for a drop that never happens aims high.
        if (this.selected != null && this.selected.kind().guided()) return 0.0;
        return projectileGravity(DEFAULT_GRAVITY);
    }

    private double projectileGravity(double fallback) {
        Double g = prop(GunProp.GRAVITY);
        return g == null ? fallback : g;
    }

    @Nullable
    private Double prop(GunProp<?, Double> prop) {
        if (this.selected == null) return null;
        try {
            GunData gun = VehicleWeapons.gunData(this.vehicle, this.vehicle.getSeatIndex(this.unit),
                    this.selected.slot());
            return gun == null ? null : gun.get(prop);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<? extends String> cues(Level level, CueKind kind, List<String> fallback) {
        try {
            return WorldVehicleClasses.get(level).listCues(kind);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean matchesAny(String name, List<? extends String> clues) {
        if (name.isEmpty()) return false;
        for (String clue : clues) {
            if (clue != null && !clue.isBlank() && name.contains(clue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
