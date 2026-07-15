package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.List;

/**
 * Autopilot for SuperbWarfare helicopters. Flight model, deliberately simple:
 *
 * <ul>
 * <li><b>Terrain-following cruise.</b> Every leg flies the configured cruise
 *     altitude (clamped 30-50) above the terrain actually below and ahead of the
 *     hull: the heightmap is sampled along the next stretch of the route and the
 *     collective holds the offset over the HIGHEST upcoming ground, so the
 *     aircraft climbs before a ridge and sinks with falling land. An absolute
 *     level anchored at the takeoff origin turned into treetop-skimming (and a
 *     wall of whisker deflections) the moment an order led into rising terrain.
 *     Cliffs and structures taller than the cruise offset remain the whiskers'
 *     job.</li>
 * <li><b>Whisker avoidance.</b> Every lateral leg probes actual blocks — and allied
 *     airframes, which no block probe can see — at hull height along the desired
 *     bearing, and fans out to alternate bearings when blocked (yaw avoidance, same
 *     pattern as DriveVehicleGoal's ground whiskers); probe reach grows with current
 *     ground speed so momentum can't outrun the lookahead; a fully-blocked forward
 *     cone answers with a climb (vertical avoidance).</li>
 * <li><b>Combat = aim platform, no revolution.</b> Close to the engage radius,
 *     descend to the attack altitude (heliAttackHeight above the TARGET — from
 *     cruise level the required depression would sit at/past the dive clamp),
 *     hold there, and pitch the nose down onto the target. The trigger is pulled
 *     by {@link VehicleWeapons#tryAiFireAssist} within the configured aim cone;
 *     SBW's own AI loop (hard 4° line) still fires whenever it happens to align.
 *     Aim-pitching happens EXCLUSIVELY in combat; everywhere else the hull flies
 *     gentle, bounded attitudes.</li>
 * <li><b>Orders outrank auto-acquired targets.</b> A PMC pilot under an explicit
 *     movement order (move-to, follow, formation, hold, cease-fire) keeps flying
 *     the order: a retaliation target must not hijack the hull into the combat
 *     profile — with hull-fixed weapons that means flying AT the target, i.e.
 *     the whole aircraft goes freelancing. The fire assist still takes any shot
 *     that happens to line up mid-leg. ATTACK_THAT_TARGET and FREE_FIRE hand the
 *     hull to the fight; autonomous RU/US crews (no order system) always
 *     fight.</li>
 * <li><b>FOLLOW_COMMANDER</b> parks the aircraft over the commander's X/Z at the
 *     cruise altitude above their ground (never closer than a fixed clearance
 *     over their head).</li>
 * <li><b>Landing (CTRL+L)</b> rides a glide slope toward the designated block's
 *     surface (top of its solid column), then inside the capture ring switches
 *     to hover mode + direct velocity command onto the pad (captureTick) and
 *     settles into the sticky LANDED state on ground contact near the pad.</li>
 * </ul>
 *
 * <p>Control plumbing (from SBW's {@code helicopterEngine}): {@code forwardInput}
 * is the collective (climb), {@code downInput} descends, {@code mouseMoveSpeedY}
 * pitches (positive = nose down), {@code mouseMoveSpeedX} yaws (positive = yaw
 * increases). Analog sticks decay ×0.95/tick and are raw mouse-delta scale (tens,
 * not ±1), so every input is re-asserted every tick at realistic magnitudes.
 */
public class DriveHelicopterGoal extends Goal {

    // Below this fraction of max health the engine takes over with a crash-spin —
    // nothing the pilot inputs matters, so we stop fighting it.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;

    // --- Transit sticks (gentle, bounded — non-combat flight never dives) ---
    private static final double YAW_STICK_PER_DEG = 0.5;
    private static final float MAX_YAW_STICK = 20.0F;      // ≈2.2°/tick yaw at saturation
    private static final double ALIGN_THRESHOLD_DEG = 35.0;
    private static final double PITCH_DEG_PER_SPEED_ERR = 40.0;
    private static final float MAX_ATTITUDE_DEG = 20.0F;   // transit tilt ceiling
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 15.0F;
    private static final double APPROACH_GAIN = 0.1;
    private static final double VEL_ERR_DEADBAND = 0.03;
    private static final double ERR_BEHIND_DEG = 100.0;

    // --- Collective (vertical) ---
    private static final double CLIMB_RATE_CAP = 0.22;
    private static final double DESCEND_RATE_CAP = 0.22;

    // --- Cruise altitude (terrain-relative) ---
    // The terrain offset is the config value hard-clamped to this band.
    private static final double MIN_FLIGHT_ALT = 30.0;
    private static final double MAX_FLIGHT_ALT = 50.0;
    // Never fly a leg below destination + this (e.g. stay above the followed player).
    private static final double MIN_OVER_DEST = 12.0;
    // Heightmap sampling for the terrain-following collective: step spacing and
    // how far ahead along the leg the highest ground is looked for. The lookahead
    // outranges the longest whisker probe, so ridge climbs start on the collective
    // before the whiskers ever have to veto the bearing.
    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    private static final double TERRAIN_LOOKAHEAD = 48.0;

    // --- Whiskers (block avoidance at hull height, cf. DriveVehicleGoal) ---
    private static final double[] WHISKER_OFFSETS_DEG = {0.0, 25.0, -25.0, 50.0, -50.0, 75.0, -75.0};
    // Probe reach = base + ~1.5s of current travel: a fixed 12-block line was
    // routinely outrun by cruise momentum (the hull can't shed speed in the
    // distance the probe cleared), so the fan looks further ahead the faster
    // the aircraft is actually moving (~34 blocks at default cruise speed).
    private static final double WHISKER_BASE_DISTANCE = 16.0;
    private static final double WHISKER_LOOKAHEAD_TICKS = 30.0;
    // Fully boxed in: pop up this far above the obstacle line and try again. The
    // avoidance floor decays ~1 block/s afterwards so surplus altitude is given
    // back gradually (and re-triggers cleanly if the obstacle is tall).
    private static final double AVOID_CLIMB_STEP = 4.0;
    private static final double AVOID_FLOOR_DECAY = 0.05;
    // Separation bubble grown around every allied airframe before the whiskers probe
    // it (on top of our own half-width, cf. the ground goal's obstacle inflation).
    // Point-sampling the hull centerline against a bare bounding box is fine for a
    // wall that isn't going anywhere; two aircraft close at their COMBINED speed, so
    // the bubble is what makes a bearing read as blocked while there is still room
    // to turn out of it rather than at the moment of contact.
    private static final double AIRCRAFT_CLEARANCE = 4.0;

    // --- Combat aim band ---
    private static final double ENGAGE_DEADBAND = 4.0;
    private static final double BREAK_RANGE = 14.0;
    private static final float MAX_COMBAT_DIVE_DEG = 60.0F;
    private static final float MAX_CLIMB_AIM_DEG = 15.0F;
    private static final double AIM_STICK_PER_DEG = 1.0;
    private static final float MAX_AIM_PITCH_STICK = 30.0F;
    private static final float MAX_AIM_YAW_STICK = 40.0F;

    // --- Arrival ---
    private static final double ARRIVE_RADIUS = 4.0;
    // Landing approach closes at half the transit gain so speed is shed early.
    private static final double LAND_APPROACH_GAIN = 0.05;
    // Approach glide slope: blocks of height above the pad per block of horizontal
    // distance out, clamped between the over-pad clearance and the cruise offset.
    private static final double LAND_GLIDE_RATIO = 0.5;
    // Capture phase (direct velocity command, see captureTick). Speeds sized so a
    // worst-case impact stays under SBW's 0.2 crash gate: |(0.15, -0.12+0.06)| ≈ 0.16.
    private static final double LAND_CAPTURE_RADIUS = 14.0;
    private static final double LAND_CAPTURE_EXIT_RADIUS = 20.0;
    private static final double LAND_DESCENT_RADIUS = 2.5;
    private static final double LAND_SETTLE_RADIUS = 6.5;
    private static final double CAPTURE_MAX_SPEED = 0.15;
    private static final double CAPTURE_GAIN = 0.15;
    private static final double CAPTURE_BLEND = 0.35;
    private static final double CAPTURE_ALT = 9.0;
    private static final double CAPTURE_VY_GAIN = 0.08;
    private static final double CAPTURE_MAX_SINK = 0.12;

    private static final float DECOY_HEALTH_FRACTION = 0.5F;
    private static final float PRESERVE_DECOY_CHANCE = 0.5F;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    private VehicleEntity vehicle;
    private double avoidFloorY = Double.NaN;
    private boolean landingCapture;

    private int weaponSwitchCooldown = 0;
    private long lastDecoyTick = Long.MIN_VALUE;
    private boolean deployDecoyThisEpisode = false;

    private VehicleEntity helicopterCacheVehicle;
    private boolean helicopterCacheValue;

    // Per-game-tick cache of nearby allied airframes, each box pre-inflated by our
    // half-width plus the separation bubble. The whisker fan probes up to 7 headings
    // a tick and every probe consults this list — only the first builds it. Unlike
    // the ground goal's equivalent the probe reach is not a fixed config value (it
    // grows with airspeed), so the reach the cache was built at is kept alongside it
    // and a longer probe within the same tick forces a rebuild.
    private long aircraftObstacleCacheTime = Long.MIN_VALUE;
    private double aircraftObstacleCacheReach = -1.0;
    private List<AABB> aircraftObstacles = List.of();

    // The chunk currently force-loaded on this aircraft's behalf, or null when the
    // option is off / nothing is held. Only ever one at a time — it hands off as the
    // hull crosses chunk boundaries (see updateChunkLoading).
    private ChunkPos forcedChunk;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) flies — same driver/commander model as the ground goal.
        if (v.getFirstPassenger() != this.unit) return false;
        this.vehicle = v;
        // Run whenever mounted in a helicopter, even with no destination: a helicopter
        // must be actively controlled to hold station, so "idle" means "hover", not "off".
        return isHelicopter();
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && isHelicopter();
    }

    // The flight model re-asserts analog stick inputs against their ×0.95/tick
    // decay and closes control loops against live velocity; vanilla only ticks
    // running goals every OTHER tick unless this is overridden.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        // A freshly boarded PMC helicopter sitting on the ground stays parked
        // (sticky LANDED) until an explicit takeoff order — without this, mounting
        // a parked hull auto-launched it to cruise altitude, making the takeoff
        // key ceremonial. ONLY player-owned crews park: RU/US crews take no player
        // flight orders and lift off immediately instead (see the normalization in
        // tick()). Spawned PMC crews are unaffected: TankSpawner issues TAKEOFF
        // before their first AI tick, so their command is never NONE here.
        if (this.vehicle != null && this.vehicle.onGround()
                && this.unit instanceof PmcUnitEntity
                && this.unit instanceof IHelicopterPilot pilot
                && pilot.sewv$getHeliCommand() == IHelicopterPilot.HELI_CMD_NONE) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        }
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            releaseInputs();
            // releaseInputs leaves the decoy latch alone (crash-spin flares must
            // survive its per-tick calls) — but a crew leaving the seat lets go.
            this.vehicle.setDecoyInputDown(false);
            // Hand the chunk back before we drop the vehicle reference the release
            // ticket is keyed to.
            releaseForcedChunk();
        }
        this.vehicle = null;
        this.avoidFloorY = Double.NaN;
        this.landingCapture = false;
        this.aircraftObstacles = List.of();
        this.aircraftObstacleCacheTime = Long.MIN_VALUE;
        this.aircraftObstacleCacheReach = -1.0;
        this.allyAssist.clear();
    }

    @Override
    public void tick() {
        // Independent of flight state: hold the airframe's chunk loaded (if enabled)
        // whether it is cruising, fighting, spiraling in, or parked.
        updateChunkLoading();

        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        // Before the crash guard on purpose: a burning airframe spiraling in keeps
        // popping flares all the way down.
        updateDecoy();

        // Sub-10% health: the engine flies it into the ground on its own. Let go.
        float max = this.vehicle.getMaxHealth();
        if (max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION) {
            releaseInputs();
            return;
        }
        // No power to the rotor — inputs do nothing anyway; don't pretend to fly.
        if (this.vehicle.getEnergy() <= 0) {
            releaseInputs();
            return;
        }

        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;

        // Hostile RU/US crews take no player flight orders and never idle parked:
        // any grounded resting state (spawn edge cases, world reload, a survived
        // crash-spin) resolves to an immediate takeoff.
        if (pilot != null && !(this.unit instanceof PmcUnitEntity)
                && this.vehicle.onGround()
                && (command == IHelicopterPilot.HELI_CMD_NONE
                    || command == IHelicopterPilot.HELI_CMD_LANDED)) {
            command = IHelicopterPilot.HELI_CMD_TAKEOFF;
            pilot.sewv$setHeliCommand(command);
        }

        if (command != IHelicopterPilot.HELI_CMD_LANDING) {
            this.landingCapture = false;
        }

        // LANDED is sticky: stay shut down on the ground — no hover, no order-driven
        // flying — until the player issues a new takeoff (L) or landing (CTRL+L).
        if (command == IHelicopterPilot.HELI_CMD_LANDED) {
            releaseInputs();
            this.vehicle.setHoverMode(false);
            return;
        }

        // LANDING overrides everything, including a queued takeoff.
        if (command == IHelicopterPilot.HELI_CMD_LANDING) {
            BlockPos pad = pilot.sewv$getHeliLandPos();
            if (pad != null) {
                doLanding(pilot, pad);
                return;
            }
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE); // nothing to land on — drop the order
        }

        // TAKEOFF: climb straight up to the terrain-relative cruise level over the
        // takeoff column, then clear the order and fall through to normal duty.
        // The heightmap under a vertically climbing hull is stable, so the climb
        // target doesn't chase its own altitude the way a getY() offset would.
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
            double climbTo = cruiseAltitudeHere();
            if (this.vehicle.getY() >= climbTo - SewvConfig.HELI_ALT_DEADBAND.get()) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            } else {
                climbVertically(climbTo);
                return;
            }
        }

        // Combat: no revolution — close to the engage ring and become an aim
        // platform. The ONLY place the nose is pitched onto something. Explicit
        // movement orders pin the flight path instead (see flightPinnedByOrder);
        // the guns stay opportunistic below.
        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null && !flightPinnedByOrder()) {
            combatTick(combatTarget);
            return;
        }

        // Order-driven movement (move-to, follow, formation, ally assist) or idle hover.
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);

        // A pinned flight path doesn't ground the guns: if the nose happens to
        // bear on the live target mid-leg, take the shot (canShoot still gates
        // ammo, CEASE_FIRE, LOS and smoke).
        if (combatTarget != null) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, combatTarget,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }

        if (dest == null) {
            holdHover(cruiseAltitudeHere());
            return;
        }
        double dx = dest.getX() + 0.5 - this.vehicle.getX();
        double dz = dest.getZ() + 0.5 - this.vehicle.getZ();
        if (dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            // Arrived — hold overhead: cruise level above the ground HERE, never
            // closer than the fixed clearance over the destination (a followed
            // commander's head included).
            holdHover(Math.max(cruiseAltitudeHere(), dest.getY() + MIN_OVER_DEST));
        } else {
            double px = dest.getX() + 0.5;
            double pz = dest.getZ() + 0.5;
            flyToward(px, pz,
                    Math.max(cruiseAltitudeToward(px, pz), dest.getY() + MIN_OVER_DEST));
        }
    }

    // True when the pilot's current SEM order explicitly owns the flight path.
    // HOLD_POSITION/CEASE_FIRE pin too: a crew ordered to hold parks and watches,
    // exactly like the ground goal (which doesn't maneuver at all without a
    // destination) — it doesn't get dragged across the map by a retaliation
    // target it happened to acquire.
    private boolean flightPinnedByOrder() {
        if (!(this.unit instanceof PmcUnitEntity pmc)) return false;
        OrderType order = pmc.getOrder();
        return order == OrderType.MOVE_TO_POSITION
                || order == OrderType.FOLLOW_COMMANDER
                || order == OrderType.FORM_WEDGE
                || order == OrderType.FORM_COLUMN
                || order == OrderType.HOLD_POSITION
                || order == OrderType.CEASE_FIRE;
    }

    // Combat: outside the ring close in; inside the break range back out; in the
    // band, hold position and pitch the nose onto the target so the guns bear
    // (SBW's AI fire loop shoots once the weapon is within 4° of the target).
    private void combatTick(LivingEntity target) {
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double engage = SewvConfig.HELI_ENGAGE_RADIUS.get();

        // Gunship weapon doctrine, deliberately DIFFERENT from the ground crews':
        // cycle to a random valid weapon slot on a fixed interval, regardless of
        // what the target is — rockets, cannon and MG all get their turn.
        if (this.weaponSwitchCooldown <= 0) {
            VehicleWeapons.selectRandomWeapon(
                    this.vehicle, this.vehicle.getSeatIndex(this.unit), this.unit.getRandom());
            this.weaponSwitchCooldown = SewvConfig.HELI_WEAPON_SWITCH_INTERVAL_TICKS.get();
        }

        if (horizDist > engage + ENGAGE_DEADBAND) {
            flyToward(target.getX(), target.getZ(),
                    Math.max(cruiseAltitudeToward(target.getX(), target.getZ()),
                            target.getY() + MIN_OVER_DEST));
            return;
        }
        if (horizDist < BREAK_RANGE) {
            // Aim drift carried us too close — open back out to the ring, staying
            // at the attack altitude so the guns come back to bear immediately.
            BlockPos out = VehicleTargeting.computeStandoffPoint(
                    this.vehicle, target.blockPosition(), engage);
            flyToward(out.getX() + 0.5, out.getZ() + 0.5, attackAltitude(target));
            return;
        }
        aimAtTarget(target, horizDist);
    }

    // The Y level held while inside the engage band: a configurable height above
    // the TARGET rather than the cruise flight level. From cruise altitude the
    // required nose depression at ring range is 45-70° — at or past the dive
    // clamp, fighting SBW's auto-level the whole way, so the fire cone almost
    // never lines up. From ~15 above the target it is a routine 25-45°.
    private double attackAltitude(LivingEntity target) {
        return target.getY() + SewvConfig.HELI_ATTACK_HEIGHT.get();
    }

    // Aim platform: two-axis mouse aim that puts the NOSE on the target entity —
    // yaw to its bearing and pitch to its depression angle SIMULTANEOUSLY, driven
    // through mouseInput like a player tracking with the mouse. No hover mode here:
    // its 0.2× pitch scaling plus auto-level meant the hull only ever aligned in
    // yaw while the nose stayed level — guns never bore on anything below. The
    // forward drift the aim tilt causes is accepted; the BREAK_RANGE backout in
    // combatTick opens the distance again, and the collective (fed every tick)
    // compensates the lift lost to the tilt.
    private void aimAtTarget(LivingEntity target, double horizDist) {
        applyCollective(withAvoidFloor(attackAltitude(target)));
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(false);

        Vec3 dir = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        if (dir.lengthSqr() > 1.0E-6) dir = dir.normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, dir));

        // Depression to the target's center (positive = below us = nose down, the
        // same sign as xRot). Tracked regardless of yaw error — both axes converge
        // together instead of pitch waiting for yaw.
        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(this.vehicle.getY() - targetCenterY, horizDist));
        float aimAttitude = (float) Mth.clamp(depressionDeg, -MAX_CLIMB_AIM_DEG, MAX_COMBAT_DIVE_DEG);
        float attitudeErr = aimAttitude - this.vehicle.getXRot();

        float mouseX = (float) Mth.clamp(-YAW_STICK_PER_DEG * 2.0 * yawErrDeg, -MAX_AIM_YAW_STICK, MAX_AIM_YAW_STICK);
        float mouseY = (float) Mth.clamp(attitudeErr * AIM_STICK_PER_DEG, -MAX_AIM_PITCH_STICK, MAX_AIM_PITCH_STICK);
        this.vehicle.mouseInput(mouseX, mouseY);

        // Pull the trigger ourselves once roughly on target: hull-fixed weapons
        // rarely hold SBW's native 4° line, so the assist fires within the wider
        // configured cone on the same RPM cadence (canShoot still gates ammo,
        // CEASE_FIRE, LOS and smoke; guided missiles steer out the residual error).
        VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
    }

    // Landing: glide-slope approach until the capture ring, then hover-mode
    // capture steered by direct velocity command (captureTick) — no pursuit
    // dynamics near the pad, so no orbiting. Ground contact near the pad
    // settles into sticky LANDED.
    private void doLanding(IHelicopterPilot pilot, BlockPos pad) {
        double surfaceY = touchdownY(pad);
        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean grounded = this.vehicle.onGround() || this.vehicle.getY() <= surfaceY + 0.35;

        if (grounded && dist <= LAND_SETTLE_RADIUS) {
            settleLanded(pilot);
            return;
        }

        if (this.landingCapture) {
            if (grounded || dist > LAND_CAPTURE_EXIT_RADIUS
                    || this.vehicle.horizontalCollision
                    || this.vehicle.getCollisionCoolDown() > 0) {
                this.landingCapture = false; // grounded short or bounced — go around
            } else {
                captureTick(surfaceY, dx, dz, dist);
                return;
            }
        } else if (!grounded && dist < LAND_CAPTURE_RADIUS
                && this.vehicle.getCollisionCoolDown() == 0
                && (dist < 1.0E-4 || headingClear(new Vec3(dx / dist, 0, dz / dist), dist))) {
            this.landingCapture = true;
            captureTick(surfaceY, dx, dz, dist);
            return;
        }

        double glideY = surfaceY + Mth.clamp(dist * LAND_GLIDE_RATIO, MIN_OVER_DEST, flightAltitude());
        double clearY = highestGroundToward(px, pz) + MIN_OVER_DEST;
        flyToward(px, pz, Math.max(glideY, clearY), LAND_APPROACH_GAIN);
    }

    // Terminal guidance by direct velocity command: SBW's engine integrates
    // deltaMovement, so a per-tick blended velocity aimed at the pad (decaying
    // with distance) converges monotonically — no attitude pursuit, no limit
    // cycle. Hover mode keeps the hull level; downInput pins collective power
    // at its floor so the auto-throttle can't fight the commanded sink.
    private void captureTick(double surfaceY, double dx, double dz, double dist) {
        releaseInputs();
        this.vehicle.setHoverMode(true);

        double speed = Math.min(CAPTURE_MAX_SPEED, dist * CAPTURE_GAIN);
        double desX = dist > 1.0E-4 ? dx / dist * speed : 0.0;
        double desZ = dist > 1.0E-4 ? dz / dist * speed : 0.0;

        double targetY = surfaceY + (dist < LAND_DESCENT_RADIUS ? 0.0 : CAPTURE_ALT);
        double desVy = Mth.clamp(
                (targetY - this.vehicle.getY()) * CAPTURE_VY_GAIN, -CAPTURE_MAX_SINK, 0.0);

        Vec3 v = this.vehicle.getDeltaMovement();
        double nvy = v.y;
        if (desVy < -0.01) {
            this.vehicle.setDownInputDown(true);
            nvy = Mth.lerp(CAPTURE_BLEND, v.y, desVy);
        }
        this.vehicle.setDeltaMovement(
                Mth.lerp(CAPTURE_BLEND, v.x, desX),
                nvy,
                Mth.lerp(CAPTURE_BLEND, v.z, desZ));
    }

    // Touchdown → sticky LANDED; the hull stays down until a new takeoff order
    // rather than immediately resuming FOLLOW/MOVE orders.
    private void settleLanded(IHelicopterPilot pilot) {
        releaseInputs();
        this.vehicle.setHoverMode(false);
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        pilot.sewv$setHeliLandPos(null);
        this.landingCapture = false;
        this.avoidFloorY = Double.NaN;
    }

    // Feet-level Y the hull can actually sit at on the ordered block's column:
    // walk up the contiguous solid stack above the pick (bounded), so designating
    // the face of a wall or hillside resolves to the surface on top of it.
    private double touchdownY(BlockPos pad) {
        Level level = this.unit.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(pad.getX(), pad.getY(), pad.getZ());
        for (int i = 0; i < 32; i++) {
            p.move(Direction.UP);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return p.getY();
            }
        }
        return pad.getY() + 1.0;
    }

    // The one lateral primitive: whisker-check the bearing, then fly velocity-error
    // guidance along the clear direction while the collective holds desiredY.
    // Desired velocity tapers with distance so the hull decelerates onto the point;
    // steering at the velocity ERROR (not the point) actively brakes sideways drift,
    // which is what prevents endless circling around a destination.
    private void flyToward(double steerX, double steerZ, double desiredY) {
        flyToward(steerX, steerZ, desiredY, APPROACH_GAIN);
    }

    private void flyToward(double steerX, double steerZ, double desiredY, double approachGain) {
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double dx = steerX - this.vehicle.getX();
        double dz = steerZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dirToDest = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : Vec3.ZERO;

        Vec3 vel = this.vehicle.getDeltaMovement();
        double groundSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // Whiskers: fly the nearest clear bearing to the desired one, probing as
        // far ahead as current momentum demands — but never past the steering
        // point itself (ground beyond a landing pad or a destination at the foot
        // of a wall must not read as "blocked"), with a small floor so a wall
        // right on the nose still registers. A fully blocked cone means terrain
        // taller than the flight level dead ahead — answer vertically: hold and
        // pop above it (the "pitch" whisker).
        double probe = Math.min(
                WHISKER_BASE_DISTANCE + groundSpeed * WHISKER_LOOKAHEAD_TICKS,
                Math.max(dist, 4.0));
        Vec3 travelDir = chooseClearBearing(dirToDest, probe);
        if (travelDir == null) {
            this.avoidFloorY = this.vehicle.getY() + AVOID_CLIMB_STEP;
            holdHover(this.avoidFloorY);
            return;
        }

        applyCollective(withAvoidFloor(desiredY));
        this.vehicle.setHoverMode(false); // full control authority while moving

        double desiredSpeed = Math.min(SewvConfig.HELI_CRUISE_SPEED.get(), dist * approachGain);
        double evx = travelDir.x * desiredSpeed - vel.x;
        double evz = travelDir.z * desiredSpeed - vel.z;
        double errMag = Math.sqrt(evx * evx + evz * evz);

        Vector3f forward = this.vehicle.getForwardDirection().normalize();

        // Flying the requested profile — keep the nose on the way ahead, level out.
        if (errMag < VEL_ERR_DEADBAND) {
            steerNose(forward, travelDir, 0.0F);
            return;
        }

        Vec3 errDir = new Vec3(evx / errMag, 0, evz / errMag);
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, errDir));
        float attitudeMag = (float) Mth.clamp(errMag * PITCH_DEG_PER_SPEED_ERR, 0.0, MAX_ATTITUDE_DEG);

        if (Math.abs(yawErrDeg) <= ERR_BEHIND_DEG) {
            // Error ahead: nose toward it, gentle pitch into it once roughly aligned.
            steerNose(forward, errDir,
                    Math.abs(yawErrDeg) < ALIGN_THRESHOLD_DEG ? attitudeMag : 0.0F);
        } else {
            // Error behind the hull (braking / killing sideways drift): keep the nose
            // where it is and pitch UP to thrust backward — no 180° pirouette.
            Vec3 back = new Vec3(-errDir.x, 0, -errDir.z);
            double backErrDeg = Math.toDegrees(getAngleBetween(forward, back));
            steerNose(forward, back,
                    Math.abs(backErrDeg) < ALIGN_THRESHOLD_DEG ? -attitudeMag : 0.0F);
        }
    }

    // Whisker fan (cf. DriveVehicleGoal.chooseClearBearing): prefer the goal bearing,
    // fan out to alternating flanks, null when the whole forward cone is blocked.
    private Vec3 chooseClearBearing(Vec3 desired, double probeDistance) {
        if (desired.lengthSqr() < 1.0E-8) return desired;
        for (double offDeg : WHISKER_OFFSETS_DEG) {
            Vec3 cand = rotateY(desired, Math.toRadians(offDeg));
            if (headingClear(cand, probeDistance)) return cand;
        }
        return null;
    }

    // True if flying `distance` blocks along `dir` keeps the hull slab (its height,
    // with a 1-block margin above and below) free of collidable blocks AND of allied
    // airframes. This is the airborne analogue of the ground goal's headingClear —
    // actual block probes, not heightmaps, so overhangs, arches and interiors are
    // judged correctly.
    private boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double sx = this.vehicle.getX();
        double sz = this.vehicle.getZ();
        int yBottom = Mth.floor(this.vehicle.getY()) - 1;
        int yTop = Mth.floor(this.vehicle.getY() + this.vehicle.getBbHeight()) + 1;
        double half = this.vehicle.getBbWidth() / 2.0;
        List<AABB> traffic = aircraftObstacles(distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (double d = half + 1.0; d <= half + distance; d += 1.0) {
            double sampleX = sx + dir.x * d;
            double sampleZ = sz + dir.z * d;
            if (isBlockedByAircraft(traffic, sampleX, sampleZ, yBottom, yTop)) return false;
            int px = Mth.floor(sampleX);
            int pz = Mth.floor(sampleZ);
            for (int y = yBottom; y <= yTop; y++) {
                if (!level.getBlockState(pos.set(px, y, pz)).getCollisionShape(level, pos).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    // Allied airframes are entities, so the block probes can't see them — this is the
    // whole reason helicopters flew through each other. Mirrors the ground goal's
    // vehicleObstacles(): allied hulls and wrecks are obstacles, enemy hulls are NOT
    // (the combat profile deliberately flies AT its target, so avoiding it would
    // fight the goal), and our own hull is excluded explicitly since the boxes are
    // inflated by our half-width and a self-match would veto every bearing.
    //
    // Restricted to helicopters on top of the ground goal's rule: a ground hull is
    // never a flight hazard at cruise, and while the vertical slab test below would
    // reject it anyway, keeping it out of the scan means the common case (nothing but
    // tanks below) doesn't pay for engine-type checks at all.
    private List<AABB> aircraftObstacles(double reach) {
        long now = this.unit.level().getGameTime();
        // Reach varies per tick with airspeed, and a landing probe (short) can precede
        // a cruise probe (long) inside one tick — rebuild when the box in hand is too
        // small rather than silently missing traffic near the far end of the fan.
        if (now != this.aircraftObstacleCacheTime || reach > this.aircraftObstacleCacheReach) {
            this.aircraftObstacleCacheTime = now;
            this.aircraftObstacleCacheReach = reach;
            double half = this.vehicle.getBbWidth() / 2.0;
            double horizontal = reach + half + AIRCRAFT_CLEARANCE + 1.0;
            AABB search = this.vehicle.getBoundingBox()
                    .inflate(horizontal, this.vehicle.getBbHeight() + AIRCRAFT_CLEARANCE + 1.0, horizontal);
            this.aircraftObstacles = this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                            v -> v != this.vehicle && isAircraftObstacle(v)).stream()
                    .map(v -> v.getBoundingBox()
                            .inflate(half + AIRCRAFT_CLEARANCE, AIRCRAFT_CLEARANCE, half + AIRCRAFT_CLEARANCE))
                    .toList();
        }
        return this.aircraftObstacles;
    }

    private boolean isAircraftObstacle(VehicleEntity other) {
        if (!(other.getEngineInfo() instanceof EngineInfo.Helicopter)) return false;
        // A wreck is still a hull hanging in the air on its way down.
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit pilot
                && VehicleTargeting.isSameFaction(this.unit, pilot);
    }

    // Vertical containment is what keeps this from being the ground goal's flat test:
    // an allied airframe holding station 40 blocks below shares our X/Z all day and
    // must not veto the bearing. Only traffic whose (already clearance-inflated) box
    // overlaps the hull slab we are about to fly through counts.
    private static boolean isBlockedByAircraft(List<AABB> obstacles, double x, double z,
                                               double slabBottom, double slabTop) {
        for (AABB box : obstacles) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ
                    && slabTop >= box.minY && slabBottom <= box.maxY) {
                return true;
            }
        }
        return false;
    }

    // Rotate a horizontal (y=0) direction about the vertical axis.
    private static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    // Hold a stationary hover at targetY: hover mode auto-levels and damps drift,
    // the collective trims the height, sticks stay centered.
    private void holdHover(double targetY) {
        applyCollective(withAvoidFloor(targetY));
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Pure vertical climb (takeoff): collective only, hover mode keeping it level
    // and drift-free so it goes straight up from the origin.
    private void climbVertically(double desiredY) {
        applyCollective(desiredY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Collective: climb toward desiredY, descend away from it, coast within the
    // deadband. Rate caps stop the bang-bang inputs from hunting up and down.
    // forwardInputDown is the collective on a helicopter, NOT translation.
    private void applyCollective(double desiredY) {
        double dy = desiredY - this.vehicle.getY();
        double deadband = SewvConfig.HELI_ALT_DEADBAND.get();
        double vy = this.vehicle.getDeltaMovement().y;
        boolean climb = dy > deadband && vy < CLIMB_RATE_CAP;
        boolean descend = dy < -deadband && vy > -DESCEND_RATE_CAP;
        this.vehicle.setForwardInputDown(climb);
        this.vehicle.setDownInputDown(descend);
    }

    // Inner loops shared by every profile: yaw stick proportional to the heading
    // error onto `aim`, pitch stick closed against the hull's actual xRot toward
    // the commanded attitude (positive = nose down). Yaw sign note: positive
    // mouseMoveSpeedX INCREASES yaw and getAngleBetween is signed the other way,
    // hence the negation — verified against SBW's helicopterEngine yaw update.
    private void steerNose(Vector3f forward, Vec3 aim, float targetAttitudeDeg) {
        if (aim.lengthSqr() > 1.0E-8) {
            double yawErrDeg = Math.toDegrees(getAngleBetween(forward, aim));
            this.vehicle.setMouseMoveSpeedX(
                    (float) Mth.clamp(-YAW_STICK_PER_DEG * yawErrDeg, -MAX_YAW_STICK, MAX_YAW_STICK));
        } else {
            this.vehicle.setMouseMoveSpeedX(0.0F);
        }
        float attitudeErr = targetAttitudeDeg - this.vehicle.getXRot();
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(attitudeErr * PITCH_STICK_PER_DEG, -MAX_PITCH_STICK, MAX_PITCH_STICK));
    }

    // Cosmetic self-preservation flares (cf. the ground goal's retreat smoke, which
    // this deliberately leaves untouched): from half health down, an airborne hull
    // holds the decoy input — the launcher's own ready/reload gating turns that
    // into a volley per reload. The coin flip is rolled once per episode (a gap in
    // consecutive low-health ticks = a fresh episode), so about half of damaged
    // airframes flare and half don't. Flight behavior is not altered here.
    private void updateDecoy() {
        float max = this.vehicle.getMaxHealth();
        boolean low = max > 0.0F && this.vehicle.getHealth() <= max * DECOY_HEALTH_FRACTION;
        if (!low || this.vehicle.onGround()) {
            this.vehicle.setDecoyInputDown(false); // unlatch once healed/repaired or parked
            return;
        }
        // Sentinel tested explicitly: now - Long.MIN_VALUE overflows negative and
        // would silently skip the roll for the first-ever episode.
        long now = this.unit.level().getGameTime();
        if (this.lastDecoyTick == Long.MIN_VALUE || now - this.lastDecoyTick > 1) {
            this.deployDecoyThisEpisode = this.unit.getRandom().nextFloat() < PRESERVE_DECOY_CHANCE;
        }
        this.lastDecoyTick = now;

        if (this.deployDecoyThisEpisode && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
        }
    }

    private void releaseInputs() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
    }

    // Terrain-relative cruise level over the hull's own column.
    private double cruiseAltitudeHere() {
        return surfaceBelow() + flightAltitude();
    }

    // Terrain-relative cruise level for a leg toward (toX, toZ): the configured
    // offset above the HIGHEST ground between here and there, so the collective
    // starts climbing before a ridge and gives the altitude back as the land
    // falls away — instead of holding an absolute level anchored at the takeoff
    // origin into terrain it knows nothing about.
    private double cruiseAltitudeToward(double toX, double toZ) {
        return highestGroundToward(toX, toZ) + flightAltitude();
    }

    // Highest heightmap ground between the hull's column and (toX, toZ), sampled
    // every few blocks out to the lookahead (capped at the destination).
    private int highestGroundToward(double toX, double toZ) {
        int highest = surfaceBelow();
        double dx = toX - this.vehicle.getX();
        double dz = toZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0E-4) {
            Level level = this.unit.level();
            double nx = dx / dist;
            double nz = dz / dist;
            double reach = Math.min(dist, TERRAIN_LOOKAHEAD);
            for (double d = TERRAIN_SAMPLE_STEP; d <= reach; d += TERRAIN_SAMPLE_STEP) {
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                        Mth.floor(this.vehicle.getX() + nx * d),
                        Mth.floor(this.vehicle.getZ() + nz * d));
                if (h > highest) highest = h;
            }
        }
        return highest;
    }

    // The active hold height including the whisker climb floor, which decays about
    // a block per second so surplus avoidance altitude is given back gently.
    private double withAvoidFloor(double desiredY) {
        if (Double.isNaN(this.avoidFloorY)) return desiredY;
        this.avoidFloorY -= AVOID_FLOOR_DECAY;
        if (this.avoidFloorY <= desiredY) {
            this.avoidFloorY = Double.NaN;
            return desiredY;
        }
        return this.avoidFloorY;
    }

    // Terrain-relative cruise offset: the configured cruise altitude, hard-clamped
    // to the 30-50 band the flight model is designed around.
    private double flightAltitude() {
        return Mth.clamp(SewvConfig.HELI_CRUISE_ALTITUDE.get(), MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }

    private int surfaceBelow() {
        return this.unit.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, this.vehicle.getBlockX(), this.vehicle.getBlockZ());
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`; same
    // convention as DriveVehicleGoal so the steering signs match across goals.
    private double getAngleBetween(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    // Optional force-loading (config-gated, default off): keep the single chunk the
    // airframe is currently over loaded and ticking so the aircraft keeps flying when
    // no player is nearby. Called every tick — the held chunk is only re-issued when
    // the hull crosses a chunk boundary, and is dropped immediately if the option is
    // switched off at runtime. Entity-owned tickets are self-cleaning: they are not
    // restored across a world reload without a validation callback (fine — this is a
    // live-flight aid, not persistent world state).
    //
    // TEMP: the System.out lines report the airframe name so force-loading can be
    // eyeballed in the server console. Remove once the behavior is verified.
    private void updateChunkLoading() {
        if (!SewvConfig.HELI_CHUNK_LOADING.get()) {
            releaseForcedChunk(); // option turned off — give any held chunk back
            return;
        }
        if (!(this.unit.level() instanceof ServerLevel serverLevel)) return;

        ChunkPos current = new ChunkPos(this.vehicle.blockPosition());
        if (current.equals(this.forcedChunk)) return; // still over the chunk we hold

        releaseForcedChunk(); // crossed a boundary — drop the previous chunk first
        ForgeChunkManager.forceChunk(serverLevel, TaczSewv.MODID, this.vehicle,
                current.x, current.z, true, true);
        this.forcedChunk = current;
        System.out.println("[SEWV chunkload] " + this.vehicle.getName().getString()
                + " force-loading chunk [" + current.x + ", " + current.z + "]");
    }

    private void releaseForcedChunk() {
        if (this.forcedChunk == null) return;
        if (this.unit.level() instanceof ServerLevel serverLevel) {
            ForgeChunkManager.forceChunk(serverLevel, TaczSewv.MODID, this.vehicle,
                    this.forcedChunk.x, this.forcedChunk.z, false, true);
            System.out.println("[SEWV chunkload] " + this.vehicle.getName().getString()
                    + " released chunk [" + this.forcedChunk.x + ", " + this.forcedChunk.z + "]");
        }
        this.forcedChunk = null;
    }

    private boolean isHelicopter() {
        if (this.vehicle != this.helicopterCacheVehicle) {
            this.helicopterCacheVehicle = this.vehicle;
            this.helicopterCacheValue = this.vehicle.getEngineInfo() instanceof EngineInfo.Helicopter;
        }
        return this.helicopterCacheValue;
    }
}
