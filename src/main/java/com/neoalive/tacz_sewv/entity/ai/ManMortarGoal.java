package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * Walks a unit to its assigned mortar and works it: lay the barrel, wait for it to come to
 * bear, load, fire, repeat.
 *
 * <p>A mortar has no seats, so the unit never mounts — it stands beside the tube and
 * "close enough to work it" is the whole arrival condition.
 *
 * <p>Any unit type can crew one. A PMC crew is assigned by a player order over the network
 * bridge; an RU/US crew is claimed onto its tube at spawn by
 * {@link com.neoalive.tacz_sewv.util.EmplacementSpawner}, since those units have no order
 * queue an order could arrive through.
 *
 * <p>The aimpoint is the crew's live target if it has one, and otherwise its
 * {@link FireMission} — that ordering is what lets an attacking battery shell a base 200
 * blocks away that it could never see, while still dropping the plan to answer anything that
 * actually walks into view. When a mission's deadline passes the crew leaves the tube for
 * good; see {@link #readFireMission}.
 */
public class ManMortarGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Aim tolerance. The mortar closes half its remaining error per tick, approaching
     * its demand asymptotically, so this can never be an equality test.
     */
    private static final float AIM_TOLERANCE_DEG = 1.0F;

    /** Re-lay once the target has wandered this far from the aimpoint. */
    private static final double RE_AIM_DISTANCE_SQ = 4.0 * 4.0;

    /** Slack on the arrival test once the path has run out. */
    private static final double NAV_STUCK_SLACK = 1.5;

    /** Goal ticks between repath attempts while the path keeps coming up empty. */
    private static final int REPATH_INTERVAL = 10;

    /**
     * Inside this, an unshootable target means the crew is being overrun rather than simply
     * out of reach. Comfortably clear of the mortar's ~27-block minimum, so the whole dead
     * zone counts, and far short of anything the tube could actually engage.
     */
    private static final double OVERRUN_DISTANCE = 40.0;

    private final AbstractUnit unit;
    private MortarEntity mortar;

    private int approachTicks;

    /**
     * Game times, not goal-tick counts, so the configured values mean the seconds they
     * claim to — goals tick every other game tick.
     */
    private long approachDeadline;
    private long nextShotTime;

    /** Where the barrel is laid, or null if it needs laying. */
    private BlockPos laidOn;

    /** The standing order to shell a place, re-read each tick; null for none. */
    private FireMission fireMission;

    /** Last reason logged, so debug logging reports changes rather than every tick. */
    private String lastHold = "";

    /** Chunks held loaded so the crew keeps working with no player nearby, or null. */
    private ChunkPos forcedUnitChunk;
    private ChunkPos forcedMortarChunk;

    public ManMortarGoal(AbstractUnit unit) {
        this.unit = unit;
        // MOVE+LOOK at priority 1 is what keeps a crew on its mortar: it outranks SEM's
        // SeekCover/MoveToAttackRange/CommanderOrder goals (MOVE, priority 2-3), which
        // would walk the unit off, and RangedGunAttackGoal (LOOK, priority 6), which
        // would have it work its rifle instead of the tube.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private IMortarCrew crew() {
        return (IMortarCrew) this.unit;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;

        int mortarId = crew().sewv$getMortarTargetId();
        if (mortarId == IMortarCrew.NO_MORTAR) return false;
        if (this.unit.getVehicle() != null) return false;

        Entity entity = this.unit.level().getEntity(mortarId);
        if (entity == null) return false; // unresolvable right now — keep the claim pending

        // Resolved but unusable: drop the claim, or the unit holds a dead id forever.
        if (!(entity instanceof MortarEntity m) || !m.isAlive()) {
            MortarSupport.releaseClaim(this.unit);
            return false;
        }

        this.mortar = m;
        if (readFireMission()) return false;
        return !beingOverrun(m);
    }

    @Override
    public boolean canContinueToUse() {
        if (crew().sewv$getMortarTargetId() == IMortarCrew.NO_MORTAR) return false;
        if (this.unit.getVehicle() != null) return false;
        if (this.mortar == null) return false;
        if (!this.mortar.isAlive()) return false;

        if (readFireMission()) return false;
        return !beingOverrun(this.mortar);
    }

    /**
     * Refreshes the cached fire mission, and stands the crew down for good if its deadline
     * has passed.
     *
     * @return true if the crew has just left the tube and the goal must not run
     */
    private boolean readFireMission() {
        this.fireMission = crew().sewv$getFireMission();
        if (this.fireMission == null) return false;
        if (!this.fireMission.isExpired(this.unit.level().getGameTime())) return false;

        // The barrage is over. Unlike beingOverrun — a lull the crew comes back from — an
        // expired mission is a stand-down: drop the order AND the tube, so the goal can never
        // restart and the unit reverts to ordinary infantry that has to be hunted down like
        // any other. Releasing the claim also frees the mortar for anyone else, and the
        // chunk ticket goes back with the goal's stop().
        crew().sewv$setFireMission(null);
        this.fireMission = null;
        MortarSupport.releaseClaim(this.unit);
        hold("fire mission complete — standing down off the tube");
        return true;
    }

    /**
     * True when the crew has a live target the tube physically cannot reach — i.e. someone
     * has closed inside the mortar's ~27-block minimum range.
     *
     * <p>Yielding here is what keeps the crew from being free kills. This goal holds MOVE
     * and LOOK at priority 1, which outranks SEM's SeekCover (2), MoveToAttackRange (3) and
     * RangedGunAttackGoal (6) — so without this a crew with an attacker at ten blocks would
     * stand at a tube that cannot depress far enough to answer, doing nothing at all, until
     * it died. Dropping the goal frees both flags and the unit fights with its rifle like
     * the infantryman it also is.
     *
     * <p>The claim is deliberately NOT released: this is a lull, not a stand-down. Once the
     * threat is dead or gone the goal starts again and the crew walks back to its tube.
     */
    private boolean beingOverrun(MortarEntity mortar) {
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;

        // Distance first, and it is load-bearing: solveAim answers null for BOTH ends of the
        // envelope, so without this a radio fire mission past the tube's ~769-block reach
        // would read as "overrun" and walk the crew off a mortar nothing is threatening.
        // Anything nearer than this that the tube still can't solve is inside the ~27-block
        // minimum by elimination — which is the only case that means "they are on top of us".
        if (this.unit.distanceToSqr(target) > OVERRUN_DISTANCE * OVERRUN_DISTANCE) return false;

        // No scatter: this asks whether the target is reachable at all, and a re-rolled
        // dispersion offset would make the answer flicker shot to shot.
        return MortarSupport.solveAim(mortar, target.position()) == null;
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.approachDeadline = deadlineFromNow();
        this.nextShotTime = 0L;
        this.lastHold = "";
        this.unit.getNavigation().moveTo(this.mortar, 1.0);
        hold("goal started, heading for the mortar");
    }

    @Override
    public void tick() {
        if (this.mortar == null) return;

        updateChunkLoading();
        this.unit.getLookControl().setLookAt(this.mortar, 30F, 30F);

        double useDistance = SewvConfig.MORTAR_USE_DISTANCE.get();
        double distSq = this.unit.distanceToSqr(this.mortar);

        if (distSq > useDistance * useDistance && !pathExhaustedNearby(distSq, useDistance)) {
            hold(String.format("walking to the mortar (%.0f blocks away, needs %.1f)",
                    Math.sqrt(distSq), useDistance));
            approach();
            return;
        }

        this.unit.getNavigation().stop();
        this.approachTicks = 0;
        this.approachDeadline = deadlineFromNow(); // the clock only bounds walking
        crewMortar();
    }

    private void approach() {
        this.approachTicks++;

        // Can't get there — walled off, across water, or the mortar moved. Give it back.
        if (this.unit.level().getGameTime() > this.approachDeadline) {
            MortarSupport.releaseClaim(this.unit);
            return;
        }

        // Throttled: an unreachable mortar leaves navigation "done" every tick, which
        // would otherwise force a full repath every tick until the deadline.
        if (this.unit.getNavigation().isDone() && this.approachTicks % REPATH_INTERVAL == 0) {
            this.unit.getNavigation().moveTo(this.mortar, 1.0);
        }
    }

    private void crewMortar() {
        // A live target outranks the standing fire mission: a plan to shell a grid is what
        // you work when nothing is in front of you, not while something is.
        LivingEntity target = this.unit.getTarget();
        boolean onTarget = target != null && target.isAlive();
        if (!onTarget && this.fireMission == null) {
            this.laidOn = null;
            hold("no target and no fire mission");
            return;
        }
        Vec3 aimCentre = onTarget ? target.position() : Vec3.atCenterOf(this.fireMission.pos());

        // Tube busy: mid-launch. Superb Warfare persists the tube inventory but not its
        // FIRE_TIME countdown, so after an interrupted save a shell can otherwise be
        // left in a permanently "loaded" tube with a zero countdown.
        if (this.mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 0) {
            hold("reloading");
            return;
        }
        boolean staleLoadedShell = !this.mortar.getItems().get(MortarSupport.TUBE_SLOT).isEmpty();

        BlockPos aimPos = BlockPos.containing(aimCentre);
        if (this.laidOn != null && this.laidOn.distSqr(aimPos) > RE_AIM_DISTANCE_SQ) {
            this.laidOn = null; // it has moved on — lay again
        }

        if (this.laidOn == null) {
            // Scatter is rolled here, once per shot, so the barrel has a fixed angle to
            // settle onto instead of being nudged every tick.
            Vec3 impact = MortarSupport.scatter(aimCentre, this.unit.getRandom());
            float[] aim = MortarSupport.solveAim(this.mortar, impact);
            if (aim == null) {
                // Inside minimum range or beyond maximum: hold rather than spend a shell
                // that can't land. canContinueToUse hands the crew back to its rifle when
                // this is a live target rather than leaving it frozen at a dead tube.
                hold(String.format("aimpoint at %.1f blocks is outside the mortar's envelope",
                        Math.sqrt(this.mortar.distanceToSqr(aimCentre))));
                return;
            }

            MortarSupport.aimAt(this.mortar, aim[0], aim[1]);
            this.laidOn = aimPos;
            hold(String.format("laying on %s at %.1f blocks (yaw %.1f, pitch %.1f), barrel slewing",
                    onTarget ? target.getName().getString() : "fire mission " + this.fireMission.pos().toShortString(),
                    Math.sqrt(this.mortar.distanceToSqr(aimCentre)), aim[0], aim[1]));
            return; // let the barrel start slewing before testing it
        }

        if (this.unit.level().getGameTime() < this.nextShotTime) {
            hold("waiting out the fire cooldown");
            return;
        }
        if (!MortarSupport.aimSettled(this.mortar, AIM_TOLERANCE_DEG)) {
            hold("barrel still slewing onto the aimpoint");
            return;
        }

        if (staleLoadedShell) {
            fireRecoveredShell();
        } else {
            fire();
        }
    }

    private void fire() {
        ItemStack shell = MortarSupport.takeShell(this.unit);
        if (shell.isEmpty()) {
            hold("no mortar shells in this unit's inventory");
            return;
        }

        // Load through getItems() rather than setItem(): setItem routes through
        // setChanged(), which auto-fires a non-INTELLIGENT mortar with a null shooter and
        // loses kill attribution. Firing it ourselves credits the unit.
        this.mortar.getItems().set(MortarSupport.TUBE_SLOT, shell.copyWithCount(1));
        this.mortar.vehicleShoot(this.unit, MortarSupport.WEAPON);
        this.unit.swing(InteractionHand.MAIN_HAND);

        // vehicleShoot is void and bails silently on several conditions, so confirm it
        // took rather than assume: FIRE_TIME only leaves 0 when the shot was accepted.
        boolean accepted = this.mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 0;
        hold(accepted
                ? "FIRING"
                : "vehicleShoot REFUSED the shot (tube loaded=" + !this.mortar.getItems()
                        .get(MortarSupport.TUBE_SLOT).isEmpty() + ", shell=" + shell + ")");

        this.nextShotTime = this.unit.level().getGameTime() + SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS.get();
        this.laidOn = null;
    }

    /**
     * Restarts the launch sequence for a shell that survived a save but whose transient
     * {@link MortarEntity#FIRE_TIME} did not. The barrel is re-laid first, so this is not
     * a blind shot at the target's position when the save occurred.
     */
    private void fireRecoveredShell() {
        this.mortar.vehicleShoot(this.unit, MortarSupport.WEAPON);
        this.unit.swing(InteractionHand.MAIN_HAND);

        boolean accepted = this.mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 0;
        hold(accepted
                ? "recovered a shell left loaded by an interrupted launch"
                : "could not recover a shell left loaded by an interrupted launch");

        this.nextShotTime = this.unit.level().getGameTime() + SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS.get();
        this.laidOn = null;
    }

    /** Names the gate the crew is stuck on, once per change, when mortarDebugLogging is on. */
    private void hold(String reason) {
        if (!SewvConfig.MORTAR_DEBUG_LOGGING.get()) return;
        if (reason.equals(this.lastHold)) return;
        this.lastHold = reason;
        LOGGER.info("[mortar] unit {} at mortar {}: {}",
                this.unit.getId(), this.mortar.blockPosition().toShortString(), reason);
    }

    /**
     * Holds the crew's and the mortar's chunks loaded so a fire mission keeps being worked
     * with no player anywhere near. A mortar shoots ~770 blocks; without this it simply
     * stops existing well before its own shells land, and the radio would be pointless.
     *
     * <p>Bootstraps itself: the first tick happens while the player is still standing
     * there giving the order, so the ticket is taken while the chunk is loaded, and from
     * then on the ticket is what keeps the crew ticking to renew it.
     *
     * <p>Both chunks, not just the mortar's: the AI lives on the crew, which stands beside
     * the tube and can be over a chunk boundary from it. Holding only the mortar would let
     * the crew freeze next to a perfectly well-loaded mortar.
     */
    private void updateChunkLoading() {
        if (!SewvConfig.MORTAR_CHUNK_LOADING.get()) {
            releaseForcedChunks(); // switched off at runtime — hand the chunks back
            return;
        }
        if (!(this.unit.level() instanceof ServerLevel serverLevel)) return;

        this.forcedUnitChunk = holdChunk(serverLevel, this.unit, this.forcedUnitChunk);
        this.forcedMortarChunk = holdChunk(serverLevel, this.mortar, this.forcedMortarChunk);
    }

    /** Re-issues the ticket only when the owner has crossed into a different chunk. */
    private ChunkPos holdChunk(ServerLevel level, Entity owner, ChunkPos held) {
        ChunkPos want = new ChunkPos(owner.blockPosition());
        if (want.equals(held)) return held;

        if (held != null) {
            ForgeChunkManager.forceChunk(level, TaczSewv.MODID, owner, held.x, held.z, false, true);
        }
        ForgeChunkManager.forceChunk(level, TaczSewv.MODID, owner, want.x, want.z, true, true);
        return want;
    }

    private void releaseForcedChunks() {
        if (this.forcedUnitChunk == null && this.forcedMortarChunk == null) return;

        if (this.unit.level() instanceof ServerLevel serverLevel) {
            if (this.forcedUnitChunk != null) {
                ForgeChunkManager.forceChunk(serverLevel, TaczSewv.MODID, this.unit,
                        this.forcedUnitChunk.x, this.forcedUnitChunk.z, false, true);
            }
            if (this.forcedMortarChunk != null && this.mortar != null) {
                ForgeChunkManager.forceChunk(serverLevel, TaczSewv.MODID, this.mortar,
                        this.forcedMortarChunk.x, this.forcedMortarChunk.z, false, true);
            }
        }
        this.forcedUnitChunk = null;
        this.forcedMortarChunk = null;
    }

    /** Navigation can finish just short of the mortar; don't stall a block out. */
    private boolean pathExhaustedNearby(double distSq, double useDistance) {
        double slack = useDistance + NAV_STUCK_SLACK;
        return this.unit.getNavigation().isDone() && distSq <= slack * slack;
    }

    private long deadlineFromNow() {
        return this.unit.level().getGameTime() + SewvConfig.MORTAR_APPROACH_TIMEOUT_TICKS.get();
    }

    @Override
    public void stop() {
        // Don't release the claim: stop() fires on any interruption and the unit still
        // owns its mortar. It's released by the dismount key, a bail-out, an unusable
        // mortar, or the approach timeout.
        //
        // laidOn deliberately survives too. stop() fires whenever a higher-priority goal
        // preempts, and clearing the lay would re-roll the aim on every restart — a goal
        // that gets preempted regularly would then re-aim forever and never reach the
        // shot. The stale-lay check in crewMortar handles a target that has moved.
        this.unit.getNavigation().stop();
        this.approachTicks = 0;
        // Chunks, unlike the claim, ARE handed back here. Nothing at priority 0 claims
        // MOVE or LOOK for an unmounted unit, so in practice this only fires when the
        // order really has ended — and a chunk released and re-taken on the next tick
        // doesn't have time to unload anyway, whereas never releasing would leak it.
        releaseForcedChunks();
        hold("goal stopped (preempted, or the order ended)");
    }
}
