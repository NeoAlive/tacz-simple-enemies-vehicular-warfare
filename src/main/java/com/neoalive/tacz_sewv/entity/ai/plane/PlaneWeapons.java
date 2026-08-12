package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.ProjectileCalculator;
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
    /** How much looser the cheap prefilter is than the real gate, so it can only ever agree early. */
    private static final double BOMB_PREFILTER_SLACK = 2.0;
    /** The bomb sim gives up after this long; a climbing aircraft never brings one down. */
    private static final int BOMB_SIM_MAX_TICKS = 200;
    /** Bombs are only released with the nose roughly along the flight path, never in a hard turn. */
    private static final double BOMB_MAX_TRACK_ERROR_DEG = 15.0;
    /**
     * Shortest spacing a stick is ever laid at, whatever the config says. Two bombs let go within
     * a few ticks of each other land close enough to detonate as one blast — a chain of craters on
     * top of one another rather than ordnance walked across a target, which is both the wrong
     * effect and a poor thing to watch. Half a second is the point at which the spacing reads as
     * separate impacts at every speed an SBW airframe flies.
     */
    private static final int MIN_BOMB_SPACING_TICKS = 10;
    /**
     * And never off a hard climb or dive. A bomb inherits the aircraft's velocity vector outright
     * ({@code Directions: "DeltaMovement"}), so the flight path angle <em>is</em> the launch angle.
     * This is a bound on how far the geometry may be from the profile the run was planned as, not
     * a demand for level flight — see {@link #BOMB_PROFILE_BAND}.
     */
    private static final double BOMB_MAX_PATH_ANGLE_DEG = 20.0;
    /**
     * Widest the release cone is ever allowed to open, in degrees. It exists so that a store with
     * an enormous blast cannot be pickled from directly overhead at any angle at all; the derived
     * angle is under it everywhere a bomb is actually used.
     */
    private static final double BOMB_MAX_CONE_DEG = 30.0;
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

    /** Grid-mark run (radio POSITION): same stick reset, target-less selection (prefers bombs). */
    public void beginRunMark(double range) {
        this.bombsDropped = 0;
        this.nextBombTick = Long.MIN_VALUE;
        select(null, range);
    }

    /**
     * Make sure something is chosen before a shot of opportunity outside an attack run. Without
     * this the transit path aims and gates against a null selection, which reads as "cannon" and
     * quietly never fires the thing the aircraft is actually carrying.
     */
    public void ensureSelected(LivingEntity target, double range) {
        if (this.selected == null) select(target, range);
    }

    public void ensureSelectedMark(double range) {
        if (this.selected == null) select(null, range);
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
        // A level-delivery store is not aimed by pointing, so there is nothing to lead here: a
        // missile steers itself, and a bomb's lead is a function of its time of fall rather than
        // of the aircraft's attitude, so it is solved at the release instead (bombAimPoint).
        if (this.selected.kind().levelDelivery()) return targetCentre;
        Vec3 muzzle = shootPos();
        return PlaneNav.interceptPoint(muzzle, targetCentre, target.getDeltaMovement(),
                projectileVelocity(), projectileGravity());
    }

    /**
     * The line the round will actually leave on — the thing that has to be put on target.
     *
     * <p><b>{@code getShootVec}, not {@code getShootDirectionForHud}.</b> The two are different
     * datapack fields and a hull may declare them apart: the A-10's cannon fires along
     * {@code [0,-0.02,1]} and draws its HUD pip along {@code [0,-0.03,1]}, so aiming the HUD line
     * put every round 0.57 degrees high — a block at a hundred, on every shot, forever. The HUD
     * value is worse than useless for a bomb, where it is the string {@code "Bomb"} and resolves
     * through {@code bombHitPos}, which is client-only and answers {@code Vec3.ZERO} on a server:
     * the resulting "gun line" points at the world origin.
     */
    public Vec3 gunLine() {
        try {
            Vec3 dir = this.vehicle.getShootVec(this.unit, 1.0F);
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
        // Hand the gate the same line the steering loop is driving, so "on target" means the same
        // thing in both places and both mean the barrel rather than the HUD pip.
        VehicleWeapons.FireGate gate = VehicleWeapons.tryAiFireAssistResult(
                this.vehicle, this.unit, target, aimPoint, cone, false, gunLine());
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
    public boolean releaseBombIfOnTarget(LivingEntity target, Vec3 forwardFlat, double runAltitude) {
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
                // Logged like the rest: a gate that refuses silently is a gate nobody can find.
                logSight(now, target, track, this.vehicle.getY() - runAltitude, "skidding");
                return false;
            }
            // Reported, never vetoed. How far the aircraft is off its briefed altitude is the one
            // number that explains a bad bombing pass, so it goes in the log — but a version of
            // this that REFUSED on it deadlocked: a run entered from above bomb altitude descends
            // through the whole approach, so the band was open only after the release point had
            // gone by, and the aircraft flew over having never once been allowed to drop. The
            // descent itself is harmless; the prediction integrates the actual velocity vector, so
            // it follows a descent exactly. What is worth refusing is a violent attitude, and the
            // path-angle bound below already is that.
            double profileErr = this.vehicle.getY() - runAltitude;
            if (Math.abs(PlaneNav.pitchOfDeg(vel)) > BOMB_MAX_PATH_ANGLE_DEG) {
                logSight(now, target, track, profileErr, "manoeuvring");
                return false;
            }
            BombSight sight = bombSight(target, track);
            if (!sight.release()) {
                logSight(now, sight, profileErr, sight.reason());
                return false;
            }
            logSight(now, sight, profileErr, "release");
        }

        try {
            this.vehicle.vehicleShoot(this.unit, this.selected.name());
        } catch (Exception e) {
            return false;
        }
        this.bombsDropped++;
        this.nextBombTick = now + stickSpacing();
        SewvDiag.plane("bomb {} of {} away",
                this.bombsDropped, SewvConfig.PLANE_BOMB_STICK.get());
        return true;
    }

    /**
     * Ticks between two bombs of a stick.
     *
     * <p>Floored twice, for two unrelated reasons. The global AI fire cooldown gates
     * {@code vehicleShoot} itself, so a stick spaced tighter than that would have its intervening
     * releases silently cancelled upstream while this counter still ran — a "three-bomb" carpet
     * that quietly drops one. {@link #MIN_BOMB_SPACING_TICKS} is the separation the impacts need
     * to read as a carpet rather than one overlapping blast.
     */
    private static int stickSpacing() {
        return Math.max(MIN_BOMB_SPACING_TICKS,
                Math.max(SewvConfig.PLANE_BOMB_STICK_INTERVAL.get(),
                        SewvConfig.AI_FIRE_COOLDOWN_TICKS.get()));
    }

    /** Grid-mark release: same stick / sight against a fixed aimpoint. */
    public boolean releaseBombIfOnTarget(Vec3 groundAim, Vec3 forwardFlat, double runAltitude) {
        if (this.selected == null || this.selected.kind() != Kind.BOMB) return false;
        if (stickComplete()) return false;

        long now = this.vehicle.level().getGameTime();
        boolean stickStarted = this.bombsDropped > 0;
        if (stickStarted) {
            if (now < this.nextBombTick) return false;
        } else {
            Vec3 vel = this.vehicle.getDeltaMovement();
            Vec3 track = new Vec3(vel.x, 0.0, vel.z);
            if (track.lengthSqr() > 1.0E-6
                    && PlaneNav.headingErrorDeg(forwardFlat, track) > BOMB_MAX_TRACK_ERROR_DEG) {
                return false;
            }
            if (Math.abs(PlaneNav.pitchOfDeg(vel)) > BOMB_MAX_PATH_ANGLE_DEG) {
                return false;
            }
            BombSight sight = bombSight(groundAim, track);
            if (!sight.release()) return false;
        }

        try {
            this.vehicle.vehicleShoot(this.unit, this.selected.name());
        } catch (Exception e) {
            return false;
        }
        this.bombsDropped++;
        this.nextBombTick = now + stickSpacing();
        SewvDiag.plane("bomb {} of {} away (mark)",
                this.bombsDropped, SewvConfig.PLANE_BOMB_STICK.get());
        return true;
    }

    /**
     * The bomb sight: how far the predicted impact is from the aim point, resolved along and
     * across the ground track, against the window each is allowed.
     *
     * <p><b>Split, rather than one radius.</b> The two errors are different problems with
     * different fixes and only one of them is survivable. Along-track error is a matter of
     * <em>timing</em> — the predicted impact walks forward roughly a tick of travel each tick, so
     * it sweeps through the window on its own and the release simply has to happen while it is
     * inside. Cross-track error is a matter of <em>where the aircraft is</em>, and it does not
     * change with time at all: a ground track a few blocks to one side is a track that never
     * satisfies any window on any tick, so the aircraft flies the entire run with the bay shut and
     * then overflies. A single circular tolerance conflates the two and reports the second as if
     * it were bad luck with the first.
     *
     * <p>Measured against the target's <b>hitbox</b>, not its position: a bomb that lands on the
     * back of a hull has hit it, and against a large vehicle the difference is comparable to the
     * whole window. The extents are the AABB's support along each axis, so it stays exact for a
     * run flown at any bearing.
     *
     * <p>Answered twice, cheap then authoritative. {@link PlaneNav#freefallImpact} is exact for the
     * ordinary case and costs a few dozen additions; SBW's own {@code ProjectileCalculator} is the
     * answer that actually counts — same physics by construction, and it clips against terrain, so
     * a ridge between the aircraft and the target is a bomb held rather than a hillside cratered —
     * but it steps at a twentieth of a tick and raycasts every step, which is several hundred clips
     * for one fall. Running that every tick of every run to be told "not yet" would pay the full
     * price for an answer already known. So the cheap one gates, at a deliberately loose tolerance
     * so it can only ever agree early, and the precise one decides.
     *
     * <p>The cheap pass earns its keep twice over: it is also what supplies the <b>time of fall</b>
     * the aim point has to be led by, which the precise one does not report.
     */
    public record BombSight(double cross, double along, double crossLimit, double alongLimit,
                            boolean solved) {

        /** The ground track passes over the target — the half that no amount of waiting fixes. */
        public boolean aligned() {
            return this.solved && Math.abs(this.cross) <= this.crossLimit;
        }

        /** The store would land level with the target along the track — the timing half. */
        public boolean onTop() {
            return this.solved && Math.abs(this.along) <= this.alongLimit;
        }

        public boolean release() {
            return aligned() && onTop();
        }

        /** What is holding the bomb, for the log. */
        public String reason() {
            if (!this.solved) return "no-solution";
            if (!aligned()) return "off-track";
            return onTop() ? "release" : "not-yet";
        }
    }

    /** Where the bomb would land relative to where it has to, decomposed on the ground track. */
    public BombSight bombSight(LivingEntity target, Vec3 track) {
        double radius = bombWindow(target);
        double hx = (target.getBoundingBox().maxX - target.getBoundingBox().minX) / 2.0;
        double hz = (target.getBoundingBox().maxZ - target.getBoundingBox().minZ) / 2.0;
        PlaneNav.Impact rough = roughImpact(target);
        Vec3 aim = rough == null ? target.position() : bombAimPoint(target, rough.ticks());
        return bombSight(aim, track, radius, hx, hz, target.getY(), rough);
    }

    /** Grid-mark sight: fixed aim, no hitbox padding. */
    public BombSight bombSight(Vec3 aim, Vec3 track) {
        double range = Math.max(this.vehicle.position().distanceTo(aim), 1.0);
        double floor = SewvConfig.PLANE_BOMB_SIGHT_RADIUS.get();
        double coneDeg = PlaneNav.fireConeDeg(lethalRadius(), range,
                Math.toDegrees(Math.atan(floor / range)), BOMB_MAX_CONE_DEG);
        double radius = range * Math.tan(Math.toRadians(coneDeg));
        PlaneNav.Impact rough = roughImpactAt(aim.y);
        return bombSight(aim, track, radius, 0.0, 0.0, aim.y, rough);
    }

    private BombSight bombSight(Vec3 aim, Vec3 track, double radius, double hx, double hz,
                                double groundY, @Nullable PlaneNav.Impact rough) {
        Vec3 axis = new Vec3(track.x, 0.0, track.z);
        axis = axis.lengthSqr() > 1.0E-6 ? axis.normalize() : new Vec3(0.0, 0.0, 1.0);
        double alongLimit = radius + PlaneNav.boxExtent(axis.x, axis.z, hx, hz);
        double crossLimit = radius + PlaneNav.boxExtent(axis.z, axis.x, hx, hz);

        if (rough == null) {
            rough = roughImpactAt(groundY);
        }
        if (rough == null) {
            return new BombSight(0.0, 0.0, crossLimit, alongLimit, false);
        }

        BombSight cheap = resolve(rough.x() - aim.x, rough.z() - aim.z, axis,
                crossLimit * BOMB_PREFILTER_SLACK, alongLimit * BOMB_PREFILTER_SLACK);
        if (!cheap.release()) {
            return new BombSight(cheap.cross(), cheap.along(), crossLimit, alongLimit, true);
        }

        Vec3 impact;
        try {
            impact = ProjectileCalculator.calculatePreciseImpactPoint(
                    this.vehicle.level(), shootPos(), this.vehicle.getDeltaMovement(),
                    this.vehicle.getDeltaMovement().length() * projectileVelocity(1.0),
                    -projectileGravity(0.06));
        } catch (Throwable e) {
            return new BombSight(0.0, 0.0, crossLimit, alongLimit, true);
        }
        return resolve(impact.x - aim.x, impact.z - aim.z, axis, crossLimit, alongLimit);
    }

    /**
     * The one line that makes a bombing run diagnosable, and the reason the sight is split.
     *
     * <p>"The plane flew over and did not drop" has several causes that look identical from the
     * ground and are fixed in completely different places. This names which one it was: a large
     * steady {@code cross} is the ground track, so the run-in geometry is what is wrong; an
     * {@code along} that never reaches zero is the release point falling outside the run; a
     * standing {@code prof} is the aircraft never arriving at its bomb altitude.
     */
    private void logSight(long now, BombSight sight, double profileErr, String reason) {
        // Guarded rather than left to the channel: this sits on a per-tick path, and Java builds
        // the arguments before the callee gets to decide it does not want them.
        if (!SewvDiag.planeVerbose()) return;
        SewvDiag.planeThrottled(now, "bomb sight {} cross={}/{} along={}/{} prof={}", reason,
                String.format("%.1f", sight.cross()), String.format("%.1f", sight.crossLimit()),
                String.format("%.1f", sight.along()), String.format("%.1f", sight.alongLimit()),
                String.format("%.1f", profileErr));
    }

    /** Same line for the gates that refuse before a sight is worth computing. */
    private void logSight(long now, LivingEntity target, Vec3 track, double profileErr,
                          String reason) {
        if (!SewvDiag.planeVerbose()) return;
        logSight(now, bombSight(target, track), profileErr, reason);
    }

    /**
     * How far out the predicted impact may be, as a <b>cone off the blast radius</b> rather than a
     * fixed distance — the bomb's version of what {@link #fireConeDeg} already does for the gun.
     *
     * <p>A flat radius is the wrong shape for an area weapon and it was too tight by a factor of
     * three. The question a release has to answer is not "will this land on the target" — nothing
     * flying a real approach against a mover ever promises that — it is "will the target be inside
     * the blast", and a Mk 82 carries a 22-block one. Judging that store by an 8-block circle
     * throws away two thirds of the weapon and turns a release the crew would call a hit into an
     * overfly, which is the reported behaviour: the aircraft flies a perfectly good run and never
     * drops. The blast is also exactly the margin that absorbs what an AI-flown pass cannot help —
     * a few blocks of track error, a target that drove on during the fall.
     *
     * <p>Expressed as an angle at the aircraft, so it scales with range the way an aiming error
     * does and reads in the same units as every other gate here, then converted back to the ground
     * radius the sight is actually measured in. The config floor keeps a store with no declared
     * blast releasable; the cap keeps a huge one from being pickled from overhead.
     */
    private double bombWindow(LivingEntity target) {
        double range = Math.max(this.vehicle.position().distanceTo(target.position()), 1.0);
        double floor = SewvConfig.PLANE_BOMB_SIGHT_RADIUS.get();
        double coneDeg = PlaneNav.fireConeDeg(lethalRadius(), range,
                Math.toDegrees(Math.atan(floor / range)), BOMB_MAX_CONE_DEG);
        return range * Math.tan(Math.toRadians(coneDeg));
    }

    private static BombSight resolve(double dx, double dz, Vec3 axis, double crossLimit,
                                     double alongLimit) {
        return new BombSight(PlaneNav.crossTrack(dx, dz, axis), PlaneNav.alongTrack(dx, dz, axis),
                crossLimit, alongLimit, true);
    }

    /**
     * The point on the ground a bombing run has to be flown over — the target led by its own travel
     * over the store's time of fall, using the fall the aircraft would get if it released now.
     *
     * <p>Public because the <b>ground track and the release gate must agree on it</b>. The run is
     * steered to put this point under the aircraft and the release is judged against how near the
     * predicted impact comes to it; two separately-derived aim points would have the autopilot
     * flying over one place while the bomb bay waited for another.
     *
     * <p>Falls back to the target itself when no fall solution exists (a release off a climb, which
     * has no impact within the window). That is the right fallback for <em>steering</em> — fly over
     * it anyway — and costs nothing, because the release has its own independent refusal for it.
     */
    public Vec3 bombGroundAim(LivingEntity target) {
        PlaneNav.Impact rough = roughImpact(target);
        return rough == null ? target.position() : bombAimPoint(target, rough.ticks());
    }

    /** Static grid mark — no lead; the ground is not moving. */
    public Vec3 bombGroundAim(Vec3 mark) {
        return mark;
    }

    /** The cheap fall solution both the aim point and the release prefilter are built on. */
    @Nullable
    private PlaneNav.Impact roughImpact(LivingEntity target) {
        return roughImpactAt(target.getY());
    }

    @Nullable
    private PlaneNav.Impact roughImpactAt(double groundY) {
        Vec3 launchVel = this.vehicle.getDeltaMovement().scale(projectileVelocity(1.0));
        return PlaneNav.freefallImpact(shootPos(), launchVel, projectileGravity(0.06),
                groundY, BOMB_SIM_MAX_TICKS);
    }

    /**
     * Where the bomb has to be thrown for it to arrive on the target: the target's position
     * advanced by its own travel over the store's time of fall.
     *
     * <p>This is the one thing a bombing run needs that a gun does not have to think about, and it
     * was missing. A gun's round arrives in a tick or two, so {@link #aimPoint} solving an
     * intercept for it is close to a formality; a bomb falls for the better part of a minute. From
     * the 80-block run altitude that is ~52 ticks, over which a tank rolling at 0.15 blocks/tick
     * travels eight blocks and a fast one considerably more — against a release window a third of
     * the blast radius wide. Comparing the predicted impact to where the target stood at release
     * therefore misses every mover by roughly its own speed times the fall, and the error grows
     * with release altitude, which is why raising the operating scale is what exposed it.
     *
     * <p>The velocity is read off the target's <b>root vehicle</b>. The targets worth bombing are
     * mostly crews, and a passenger's own {@code getDeltaMovement} is near zero while the hull
     * carrying it does the moving — leading by the passenger's velocity would be leading by
     * nothing at all.
     */
    private static Vec3 bombAimPoint(LivingEntity target, int fallTicks) {
        Vec3 vel = target.getRootVehicle().getDeltaMovement();
        return PlaneNav.leadPoint(target.position(), vel, fallTicks);
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
