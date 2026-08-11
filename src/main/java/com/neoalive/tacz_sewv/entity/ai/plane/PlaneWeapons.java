package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
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
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.util.WarnOnce;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Everything a fixed-wing crew does with its weapons: what it carries, what it picks, where it has
 * to point, and whether it may pull the trigger yet.
 *
 * <p>Two decisions in here account for whether this AI hits anything.
 *
 * <p><b>Where it points.</b> A miss lands about {@code range x tan(error)} from the aim point, so
 * an angular fire gate is a distance gate in disguise and one fixed angle cannot be right at both
 * ends of an engagement. The old 45 degree cone meant a shot taken 60 blocks out could land 60
 * blocks wide and still count as "on target" — the reported miss. Replacing it with a small fixed
 * cone then produced the opposite failure, an aircraft that never fired at all, because no airframe
 * holds six degrees through a dive. So the gate is derived instead: the weapon's own
 * {@code ExplosionRadius} says how close counts as a hit, the range says what angle that is, and
 * {@link PlaneNav#fireConeDeg} turns the two into a tolerance that tightens with distance and opens
 * up in close. The config values are only the ceiling and the floor on that.
 *
 * <p><b>What it points.</b> The error is measured between SBW's live {@code shoot direction} and
 * the line from the <em>muzzle</em> to the aim point, never between the hull's nose and the target.
 * Guns sit forward of the hull origin and canted off its axis (the A-10's is 1.7 degrees below the
 * nose), so hull-origin geometry carries a fixed error into every shot at every range — a large
 * share of a few-degree budget, always in the same direction.
 *
 * <p>Two facts from SBW's gun data are load-bearing in the aiming:
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

    /** Fallback ballistic gravity when the datapack does not say — SBW's own schema default. */
    private static final double DEFAULT_GRAVITY = 0.05;
    /** Fallback projectile speed (blocks/tick) when gun data is unreadable. */
    private static final double DEFAULT_VELOCITY = 10.0;
    /** Tightest a release window is ever allowed to be, for a bomb whose datapack declares no blast. */
    private static final double BOMB_HIT_TOLERANCE_MIN = 3.0;
    /** The bomb sim gives up after this long; a climbing aircraft never brings one down. */
    private static final int BOMB_SIM_MAX_TICKS = 200;
    /** Bombs are only released with the nose roughly along the flight path, never in a hard turn. */
    private static final double BOMB_MAX_TRACK_ERROR_DEG = 15.0;
    /**
     * Accuracy demanded of a weapon whose datapack declares no blast at all — a plain kinetic gun.
     * Roughly a vehicle's own width, so "on target" means the burst is on the hull.
     */
    private static final double DEFAULT_LETHAL_RADIUS = 3.0;

    /**
     * What a slot is: how it is aimed, how it is flown, and how much room it needs.
     *
     * <p>The tier is the whole of the ordnance doctrine. It orders the stores by how much room they
     * need to be used — a gun can be fired from anywhere, a rocket wants a little standoff, a bomb
     * or a guided missile wants real distance — and both the target-class rule and AUTO's range
     * rule express themselves as a cap on it. Higher is heavier, so the crew's preference is simply
     * "the heaviest thing the cap allows".
     */
    public enum Kind {
        /** Gun of any calibre, AP shells included — the fallback, so nothing is ever unusable. */
        CANNON(1),
        ROCKET(2),
        BOMB(3),
        /** Fire-and-forget missile: aimed flat, no lead, and it steers itself in. */
        GUIDED(3);

        private final int tier;

        Kind(int tier) {
            this.tier = tier;
        }

        public int tier() {
            return this.tier;
        }

        public boolean guided() {
            return this == GUIDED;
        }

        /** Free-fall or self-guiding stores are released from level flight, never out of a dive. */
        public boolean levelDelivery() {
            return this == BOMB || this == GUIDED;
        }
    }

    private record Weapon(int slot, Kind kind, String name) {}

    private final VehicleEntity vehicle;
    private final AbstractUnit unit;
    private final List<Weapon> weapons = new ArrayList<>();
    private boolean scanned;

    private Weapon selected;
    private PlaneAttackMode mode = PlaneAttackMode.AUTO;

    // Carpet stick state, reset per attack run.
    private int bombsDropped;
    private long nextBombTick = Long.MIN_VALUE;

    public PlaneWeapons(VehicleEntity vehicle, AbstractUnit unit) {
        this.vehicle = vehicle;
        this.unit = unit;
    }

    /** A different hull carries different weapons — forget everything. */
    public void reset() {
        this.scanned = false;
        this.weapons.clear();
        this.selected = null;
        this.bombsDropped = 0;
        this.nextBombTick = Long.MIN_VALUE;
    }

    /** The ordnance the last radio mission asked for. Re-read each tick; it is a standing order. */
    public void setMode(PlaneAttackMode mode) {
        this.mode = mode == null ? PlaneAttackMode.AUTO : mode;
    }

    /** New attack run: fresh stick, and re-pick for what we are attacking and how far out it is. */
    public void beginRun(LivingEntity target, double range) {
        this.bombsDropped = 0;
        this.nextBombTick = Long.MIN_VALUE;
        select(target, range);
    }

    /**
     * Make sure something is chosen before a shot of opportunity outside an attack run. Without
     * this the transit path aims and gates against a null selection, which reads as "cannon" and
     * quietly never fires the thing the aircraft is actually carrying.
     */
    public void ensureSelected(LivingEntity target, double range) {
        if (this.selected == null) select(target, range);
    }

    public boolean hasBombSelected() {
        return this.selected != null && this.selected.kind() == Kind.BOMB;
    }

    /** True when the run must be flown level rather than as a dive at the target. */
    public boolean levelDelivery() {
        return this.selected != null && this.selected.kind().levelDelivery();
    }

    @Nullable
    public Kind selectedKind() {
        return this.selected == null ? null : this.selected.kind();
    }

    /** Whether the whole stick has gone; the run has nothing left to do once it has. */
    public boolean stickComplete() {
        return this.bombsDropped >= SewvConfig.PLANE_BOMB_STICK.get();
    }

    // --- Classification -------------------------------------------------------------------------

    /**
     * Classify the seat's weapons once, against the {@code /sewv pool misc} cue lists.
     *
     * <p>Each slot is matched on its <b>name and its ammo item id together</b>. Neither alone is
     * enough: SBW names the A-10's slots {@code Cannon}/{@code Rocket}/{@code Bomb}/{@code Missile},
     * which is readable, but the Ju-87 calls its gun {@code MachineGun} and an addon may name a
     * slot anything at all — while the ammo id is real datapack metadata that says what the round
     * is ({@code small_shell_ap}, {@code small_rocket}, {@code medium_aerial_bomb},
     * {@code large_anti_ground_missile}). Placeholder slots are dropped outright, since a
     * zero-velocity slot cannot fire and picking one disarms the pass.
     *
     * <p>Order matters and is guided → bomb → rocket → cannon, so a longer clue can carve a special
     * case out of a broader one, and anything unrecognised falls through to the gun rather than
     * becoming unusable.
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
                String signature = signature(raw, ammoId(seat, w));
                Kind kind = classify(level, signature);
                this.weapons.add(new Weapon(w, kind, raw));
                SewvDiag.plane("slot {} '{}' [{}] -> {}", w, raw, signature, kind);
            }
        } catch (Exception e) {
            WarnOnce.warn(SewvDiag.LOG, "plane-weapons:" + this.vehicle.getId(),
                    "Failed to classify plane weapons for "
                            + this.vehicle.getType().getDescriptionId(), e);
        }
    }

    private static Kind classify(Level level, String signature) {
        if (matchesAny(signature, cues(level, CueKind.PLANE_MISSILE))) return Kind.GUIDED;
        if (matchesAny(signature, cues(level, CueKind.PLANE_BOMB))) return Kind.BOMB;
        if (matchesAny(signature, cues(level, CueKind.PLANE_ROCKET))) return Kind.ROCKET;
        return Kind.CANNON; // includes every PLANE_CANNON cue and anything unrecognised
    }

    /** Name and ammo id joined, lower-cased, so one {@code contains} test covers both. */
    private static String signature(@Nullable String name, @Nullable String ammo) {
        String a = name == null ? "" : name;
        String b = ammo == null ? "" : ammo;
        return (a + "|" + b).toLowerCase(Locale.ROOT);
    }

    /** The ammo the slot is currently set to consume, e.g. {@code superbwarfare:small_shell_ap}. */
    @Nullable
    private String ammoId(int seat, int slot) {
        try {
            GunData gun = VehicleWeapons.gunData(this.vehicle, seat, slot);
            if (gun == null) return null;
            AmmoConsumer consumer = gun.selectedAmmoConsumer();
            return consumer == null ? null : consumer.getAmmo();
        } catch (Exception e) {
            return null; // the name alone still classifies most hulls
        }
    }

    // --- Selection ------------------------------------------------------------------------------

    /**
     * Pick the heaviest loaded store the caps allow. Two caps apply and the tighter wins: what the
     * player asked for on the radio, and what the target and range justify.
     */
    private void select(LivingEntity target, double range) {
        if (!this.scanned) scan();

        Weapon best = null;
        Weapon bestUnready = null;
        for (Weapon w : this.weapons) {
            if (!permitted(w.kind(), target, range)) continue;
            if (ready(w)) {
                if (best == null || w.kind().tier() > best.kind().tier()) best = w;
            } else if (bestUnready == null || w.kind().tier() > bestUnready.kind().tier()) {
                bestUnready = w;
            }
        }
        // Nothing loaded within the permitted set: take the lightest loaded thing aboard instead.
        // A crew ordered to bomb that has expended its bombs still has a gun, and an aircraft that
        // refuses to shoot because the requested rack is empty is worse than one that improvises.
        if (best == null) {
            for (Weapon w : this.weapons) {
                if (!ready(w)) continue;
                if (best == null || w.kind().tier() < best.kind().tier()) best = w;
            }
        }
        this.selected = best != null ? best : bestUnready;
        if (this.selected == null) {
            SewvDiag.planeThrottled(this.vehicle.level().getGameTime(),
                    "no usable weapon: {} slots classified, mode={}", this.weapons.size(), this.mode);
        }
    }

    /** Is this store allowed right now, by the radio order and by the target and range? */
    private boolean permitted(Kind kind, LivingEntity target, double range) {
        return switch (this.mode) {
            // "Cannon or rockets depending on availability" — a strafing pass either way.
            case CANNON -> kind == Kind.CANNON || kind == Kind.ROCKET;
            case BOMB -> kind == Kind.BOMB;
            case GUIDED -> kind == Kind.GUIDED;
            case AUTO -> kind.tier() <= Math.min(targetTier(target), tierForRange(range));
        };
    }

    /**
     * AUTO's range rule: <b>the closer the target, the fewer stores are eligible.</b>
     *
     * <p>Ordnance is excluded by how much room it needs to work, not by how much damage it does. A
     * bomb has to be released far enough out to fall onto the target and a guided missile needs
     * flight time to steer, so both are struck off as the aircraft closes; a rocket needs a little
     * standoff; a gun works right up to the merge and is therefore the last thing left. That is why
     * the exclusion order runs AP shells, then rockets, then bombs or guided — the gun is the floor
     * every crew keeps.
     */
    public static int tierForRange(double range) {
        if (range < SewvConfig.PLANE_AUTO_ROCKET_RANGE.get()) return Kind.CANNON.tier();
        if (range < SewvConfig.PLANE_AUTO_HEAVY_RANGE.get()) return Kind.ROCKET.tier();
        return Kind.BOMB.tier();
    }

    /** Vehicles justify the heavy tier, faction infantry the medium, everything else the gun. */
    private static int targetTier(@Nullable LivingEntity target) {
        if (target == null) return Kind.BOMB.tier();
        return switch (VehicleWeapons.classifyTarget(target)) {
            case VEHICLE -> Kind.GUIDED.tier();
            case FACTION_UNIT -> Kind.ROCKET.tier();
            default -> Kind.CANNON.tier();
        };
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

    /** Point the seat at the chosen slot. Must be re-asserted: SBW does not remember for us. */
    public void arm() {
        if (this.selected == null) return;
        try {
            this.vehicle.setWeaponIndex(this.vehicle.getSeatIndex(this.unit), this.selected.slot());
        } catch (Exception ignored) {
            // setWeaponIndex does not bounds-check; a hull that changed under us must not crash.
        }
    }

    // --- Aiming ---------------------------------------------------------------------------------

    /**
     * Where the gun has to point for the shot to arrive. A guided missile is aimed straight at the
     * target — it steers itself, and lofting it for a drop it does not have is how the turret path
     * already learned to shoot over things. Everything else gets the full intercept solution.
     */
    public Vec3 aimPoint(LivingEntity target) {
        Vec3 targetCentre = target.getBoundingBox().getCenter();
        if (this.selected == null) return targetCentre;
        if (this.selected.kind().levelDelivery()) return targetCentre;
        Vec3 muzzle = shootPos();
        return PlaneNav.interceptPoint(muzzle, targetCentre, target.getDeltaMovement(),
                projectileVelocity(), projectileGravity());
    }

    /** SBW's live firing direction for the selected slot — the thing that has to be on target. */
    public Vec3 gunLine() {
        try {
            Vec3 dir = this.vehicle.getShootDirectionForHud(this.unit, 1.0F);
            if (dir != null && dir.lengthSqr() > 1.0E-8) return dir;
        } catch (Exception ignored) {
            // fall through to the hull axis
        }
        return this.vehicle.getViewVector(1.0F);
    }

    /** Muzzle to aim point: the vector the gun line has to be driven onto. */
    public Vec3 toAim(Vec3 aimPoint) {
        return aimPoint.subtract(shootPos());
    }

    // --- Firing ---------------------------------------------------------------------------------

    /**
     * Fire if the shot would land on the target.
     *
     * <p>"Would land on" is the whole gate and it is computed, not configured: the angle within
     * which a shot stays inside the weapon's own blast radius at the current range. A cannon burst
     * at 90 blocks has to be held to about two degrees; a bomb with a twenty-block radius at thirty
     * blocks can go at twenty. One rule, every weapon, and the config values only bound it.
     *
     * <p>The NPC cone floor every ground crew gets is deliberately <b>not</b> applied: 35 degrees
     * on a fixed-wing gun is the bug this class exists to remove.
     *
     * @return true if a shot went out
     */
    public boolean fire(LivingEntity target, Vec3 aimPoint) {
        Vec3 toAim = toAim(aimPoint);
        double range = toAim.length();
        double cone = fireConeDeg(range);
        VehicleWeapons.FireGate gate = VehicleWeapons.tryAiFireAssistResult(
                this.vehicle, this.unit, target, aimPoint, cone, false);
        if (gate == VehicleWeapons.FireGate.FIRED) {
            SewvDiag.plane("FIRED {} range={} cone={}", kindName(),
                    String.format("%.0f", range), String.format("%.1f", cone));
        } else {
            // Throttled, and it names the measured error: "holding fire ... gate=CONE err=14" is
            // the difference between knowing the autopilot cannot point and guessing at it.
            SewvDiag.planeThrottled(this.vehicle.level().getGameTime(),
                    "holding fire {} gate={} err={} cone={} range={}", kindName(), gate,
                    String.format("%.1f", PlaneNav.gunErrorDeg(gunLine(), toAim)),
                    String.format("%.1f", cone), String.format("%.0f", range));
        }
        return gate == VehicleWeapons.FireGate.FIRED;
    }

    /** The angular tolerance this weapon earns at this range. Public so the goal can log it. */
    public double fireConeDeg(double range) {
        double ceiling = this.selected != null && this.selected.kind().guided()
                ? SewvConfig.PLANE_MISSILE_CONE_DEG.get()
                : SewvConfig.PLANE_GUN_CONE_DEG.get();
        return PlaneNav.fireConeDeg(lethalRadius(), range,
                SewvConfig.PLANE_MIN_CONE_DEG.get(), ceiling);
    }

    /** How close a shot has to land: the weapon's own blast, or a hull's width for a plain gun. */
    private double lethalRadius() {
        Double r = prop(GunProp.EXPLOSION_RADIUS);
        return r == null || r <= 0.0 ? DEFAULT_LETHAL_RADIUS : Math.max(r, DEFAULT_LETHAL_RADIUS);
    }

    private String kindName() {
        return this.selected == null ? "none" : this.selected.kind().name();
    }

    /**
     * Release the next bomb of a carpet stick when one dropped this instant would land on the aim
     * point. Continuous prediction rather than a fixed release range, so it self-adjusts across
     * level, diving and low passes.
     *
     * <p>The stick is the difference between an airstrike and a single lucky hit. Releasing one
     * bomb requires the prediction to be exactly right at exactly one instant, which no aircraft
     * flying a real approach manages; releasing several at a fixed interval lays them along the
     * ground track through the aim point, so an imperfect release still walks ordnance over the
     * target. The interval is in ticks rather than blocks because the spacing that matters is the
     * one the aircraft's own speed produces.
     *
     * <p>The simulation starts at the actual bomb bay ({@code getShootPos}) and uses the weapon's
     * own velocity and gravity from gun data. SBW launches a delta-movement weapon at
     * {@code |velocity| * VELOCITY} along the aircraft's velocity vector, so the initial velocity
     * is simply the hull's own scaled by that factor.
     *
     * @return true when a bomb was released this tick
     */
    public boolean releaseBombIfOnTarget(LivingEntity target, Vec3 forwardFlat) {
        if (this.selected == null || this.selected.kind() != Kind.BOMB) return false;
        if (stickComplete()) return false;

        long now = this.vehicle.level().getGameTime();
        boolean stickStarted = this.bombsDropped > 0;
        if (stickStarted) {
            // Once the first one is away the rest go on the clock: re-predicting for each would
            // stack them all on the aim point, which is a single crater, not a carpet.
            if (now < this.nextBombTick) return false;
        } else {
            // A bomb thrown out of a hard turn inherits a sideways velocity the prediction models
            // but the aircraft cannot hold; wait for the wings to settle rather than lob one off
            // the beam.
            Vec3 vel = this.vehicle.getDeltaMovement();
            Vec3 track = new Vec3(vel.x, 0.0, vel.z);
            if (track.lengthSqr() > 1.0E-6
                    && PlaneNav.headingErrorDeg(forwardFlat, track) > BOMB_MAX_TRACK_ERROR_DEG) {
                return false;
            }
            if (!bombWouldHit(target)) return false;
        }

        try {
            this.vehicle.vehicleShoot(this.unit, this.selected.name());
        } catch (Exception e) {
            return false;
        }
        this.bombsDropped++;
        // Floored at the global AI fire cooldown, which gates vehicleShoot itself: a stick spaced
        // tighter than that would have its intervening releases silently cancelled upstream while
        // this counter still ran, so a "three-bomb" carpet would quietly drop one.
        this.nextBombTick = now + Math.max(SewvConfig.PLANE_BOMB_STICK_INTERVAL.get(),
                SewvConfig.AI_FIRE_COOLDOWN_TICKS.get());
        SewvDiag.plane("bomb {} of {} away",
                this.bombsDropped, SewvConfig.PLANE_BOMB_STICK.get());
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
                double tol = bombHitTolerance();
                return dx * dx + dz * dz <= tol * tol;
            }
        }
        return false; // never comes down in the window — hold the bomb
    }

    /**
     * How near the predicted impact has to be before the bomb goes, scaled off the weapon's own
     * blast exactly as the gun cone is.
     *
     * <p>It was a flat three blocks, and against a Mk 82's 22-block blast that is not precision,
     * it is a release window measured in single ticks: the predicted impact walks forward by a
     * whole tick of travel every tick — one to three blocks — so a three-block gate is a coin
     * flip on whether any tick ever lands inside it, and the aircraft simply overflies with the
     * bay shut. Half the lethal radius is a window several ticks wide instead.
     *
     * <p>Widening it also gives the stick the right shape for free. The predicted impact starts
     * short and walks through the target as the aircraft closes, so the first bomb now goes while
     * it is still falling short and the rest of the stick walks up through the aim point — which
     * is what a stick is for, rather than three craters in one hole.
     */
    private double bombHitTolerance() {
        return Math.max(BOMB_HIT_TOLERANCE_MIN, lethalRadius() * 0.5);
    }

    /**
     * Distance from the target at which the selected bomb would have to be let go, so the goal can
     * start the run outside it. Zero when no bomb is selected, or when the aircraft is too slow
     * for the question to mean anything.
     *
     * @param releaseHeight blocks between the bomb bay and the target's own altitude
     */
    public double bombReleaseRange(double releaseHeight) {
        if (!hasBombSelected()) return 0.0;
        double speed = this.vehicle.getDeltaMovement().horizontalDistance() * projectileVelocity(1.0);
        return PlaneNav.ballisticLead(releaseHeight, speed, projectileGravity(0.06));
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

    private static List<? extends String> cues(Level level, CueKind kind) {
        try {
            return WorldVehicleClasses.get(level).listCues(kind);
        } catch (Throwable ignored) {
            return WorldVehicleClasses.builtInCues(kind);
        }
    }

    private static boolean matchesAny(String signature, List<? extends String> clues) {
        if (signature.isEmpty()) return false;
        for (String clue : clues) {
            if (clue != null && !clue.isBlank()
                    && signature.contains(clue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
