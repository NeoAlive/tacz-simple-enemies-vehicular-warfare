package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.mojang.logging.LogUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IDelayedFire;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.Type63Support;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;
import com.neoalive.tacz_sewv.util.ChunkTicket;

/**
 * Walks a unit to its assigned Type-63 MLRS and works it: lay the launcher, load tubes,
 * ripple-fire rockets, repeat.
 *
 * <p>Same stand-beside claim shape as {@link ManMortarGoal}; see that class for fire-mission
 * and overrun behaviour.
 */
public class ManType63Goal extends Goal {

    private static final int APPROACH_TIMEOUT_TICKS = 300;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float AIM_TOLERANCE_DEG = 2.0F;

    /** Re-lay once the aimpoint has moved this far (block pos is too coarse for movers). */
    private static final double RE_AIM_DISTANCE_SQ = 8.0 * 8.0;
    private static final double NAV_STUCK_SLACK = 1.5;
    private static final int REPATH_INTERVAL = 10;
    private static final double OVERRUN_DISTANCE = 40.0;

    private final AbstractUnit unit;
    private Type63Entity launcher;

    private int approachTicks;
    private long approachDeadline;
    private long nextShotTime;
    private Vec3 laidOn;
    private FireMission fireMission;
    private String lastHold = "";

    private final ChunkTicket unitChunk = new ChunkTicket();
    private final ChunkTicket launcherChunk = new ChunkTicket();

    public ManType63Goal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private IMortarCrew crew() {
        return (IMortarCrew) this.unit;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;

        int launcherId = crew().sewv$getMortarTargetId();
        if (launcherId == IMortarCrew.NO_MORTAR) return false;
        if (this.unit.getVehicle() != null) return false;

        Entity entity = this.unit.level().getEntity(launcherId);
        if (entity == null) return false;

        // Mortar shares IMortarCrew; ManMortarGoal owns that claim — do not release it here.
        if (entity instanceof MortarEntity) return false;

        if (!(entity instanceof Type63Entity t) || !t.isAlive()) {
            Type63Support.releaseClaim(this.unit);
            return false;
        }

        this.launcher = t;
        if (readFireMission()) return false;
        return !beingOverrun(t);
    }

    @Override
    public boolean canContinueToUse() {
        if (crew().sewv$getMortarTargetId() == IMortarCrew.NO_MORTAR) return false;
        if (this.unit.getVehicle() != null) return false;
        if (this.launcher == null) return false;
        if (!this.launcher.isAlive()) return false;

        if (readFireMission()) return false;
        return !beingOverrun(this.launcher);
    }

    private boolean readFireMission() {
        this.fireMission = crew().sewv$getFireMission();
        if (this.fireMission == null) return false;
        if (!this.fireMission.isExpired(this.unit.level().getGameTime())) return false;

        crew().sewv$setFireMission(null);
        this.fireMission = null;
        Type63Support.releaseClaim(this.unit);
        hold("fire mission complete — standing down off the launcher");
        return true;
    }

    private boolean beingOverrun(Type63Entity launcher) {
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (this.unit.distanceToSqr(target) > OVERRUN_DISTANCE * OVERRUN_DISTANCE) return false;
        return Type63Support.solveAim(launcher, target.position()) == null;
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.approachDeadline = deadlineFromNow();
        this.nextShotTime = 0L;
        this.lastHold = "";
        this.unit.getNavigation().moveTo(this.launcher, 1.0);
        hold("goal started, heading for the Type-63");
    }

    @Override
    public void tick() {
        if (this.launcher == null) return;

        updateChunkLoading();
        this.unit.getLookControl().setLookAt(this.launcher, 30F, 30F);

        double useDistance = SewvConfig.MORTAR_USE_DISTANCE.get();
        double distSq = this.unit.distanceToSqr(this.launcher);

        if (distSq > useDistance * useDistance && !pathExhaustedNearby(distSq, useDistance)) {
            hold("walking to the launcher (%.0f blocks away, needs %.1f)",
                    Math.sqrt(distSq), useDistance);
            UnitHolster.setManningMortar(this.unit, false);
            approach();
            return;
        }

        this.unit.getNavigation().stop();
        this.approachTicks = 0;
        this.approachDeadline = deadlineFromNow();
        UnitHolster.setManningMortar(this.unit, true);
        crewLauncher();
    }

    private void approach() {
        this.approachTicks++;
        if (this.unit.level().getGameTime() > this.approachDeadline) {
            Type63Support.releaseClaim(this.unit);
            return;
        }
        if (this.unit.getNavigation().isDone() && this.approachTicks % REPATH_INTERVAL == 0) {
            this.unit.getNavigation().moveTo(this.launcher, 1.0);
        }
    }

    private void crewLauncher() {
        Type63Support.stabilizeClaimed(this.launcher);

        LivingEntity target = this.unit.getTarget();
        boolean onTarget = target != null && target.isAlive();
        if (!onTarget && this.fireMission == null) {
            this.laidOn = null;
            hold("no target and no fire mission");
            return;
        }
        Vec3 aimCentre = onTarget ? target.position() : Vec3.atCenterOf(this.fireMission.pos());

        if (Type63Support.onFireCooldown(this.launcher)) {
            hold("launcher on fire cooldown");
            return;
        }

        if (this.laidOn != null && this.laidOn.distanceToSqr(aimCentre) > RE_AIM_DISTANCE_SQ) {
            this.laidOn = null;
        }

        if (this.laidOn == null) {
            Vec3 impact = Type63Support.scatter(aimCentre, this.unit.getRandom());
            float[] aim = Type63Support.solveAim(this.launcher, impact);
            if (aim == null) {
                hold("aimpoint at %.1f blocks is outside the launcher's envelope",
                        Math.sqrt(this.launcher.distanceToSqr(aimCentre)));
                return;
            }
            Type63Support.aimAt(this.launcher, aim[0], aim[1]);
            this.laidOn = impact;
            hold("laying on %s at %.1f blocks (yaw %.1f, pitch %.1f)",
                    onTarget ? target.getName().getString() : "fire mission " + this.fireMission.pos().toShortString(),
                    Math.sqrt(this.launcher.distanceToSqr(aimCentre)), aim[0], aim[1]);
            return;
        }

        Type63Support.loadAllEmptyTubes(this.launcher, this.unit);

        if (this.unit.level().getGameTime() < this.nextShotTime) {
            hold("waiting out the fire cooldown");
            return;
        }
        if (!Type63Support.aimSettled(this.launcher, AIM_TOLERANCE_DEG)) {
            hold("launcher still slewing onto the aimpoint");
            return;
        }
        if (this.unit instanceof IDelayedFire delayed
                && delayed.sewv$hasActiveFireDelay(this.unit.level().getGameTime())) {
            hold("holding for coordinated fire delay");
            return;
        }
        if (VehicleTargeting.friendlyNearPoint(
                this.unit, aimCentre, SewvConfig.FRIENDLY_FIRE_MORTAR_RADIUS.get())) {
            hold("holding fire — player or friendly PMC too close to aimpoint");
            return;
        }

        fireRipple();
    }

    private void fireRipple() {
        int tube = Type63Support.nextLoadedTube(this.launcher);
        if (tube < 0) {
            int loaded = Type63Support.loadAllEmptyTubes(this.launcher, this.unit);
            if (loaded == 0) {
                hold("no medium rockets in this unit's inventory");
                return;
            }
            tube = Type63Support.nextLoadedTube(this.launcher);
            if (tube < 0) return;
        }

        boolean fired = Type63Support.fireTube(this.launcher, null, tube);
        if (fired) {
            this.unit.swing(InteractionHand.MAIN_HAND);
        }
        hold(fired ? "FIRING tube " + tube : "shoot refused on tube " + tube);

        if (fired) {
            this.nextShotTime = this.unit.level().getGameTime() + SewvConfig.TYPE63_FIRE_COOLDOWN_TICKS.get();
            this.laidOn = null;
        }
    }

    private void hold(String reason) {
        if (!ClientConfig.flag(ClientConfig.MORTAR_DEBUG_LOGGING)) return;
        if (reason.equals(this.lastHold)) return;
        this.lastHold = reason;
        LOGGER.info("[type63] unit {} at launcher {}: {}",
                this.unit.getId(), this.launcher.blockPosition().toShortString(), reason);
    }

    private void hold(String fmt, Object... args) {
        if (!ClientConfig.flag(ClientConfig.MORTAR_DEBUG_LOGGING)) return;
        hold(String.format(fmt, args));
    }

    private void updateChunkLoading() {
        if (SewvConfig.MORTAR_CHUNK_LOADING.get()) {
            this.unitChunk.follow(this.unit);
            this.launcherChunk.follow(this.launcher);
        } else {
            releaseForcedChunks();
        }
    }

    private void releaseForcedChunks() {
        this.unitChunk.release(this.unit);
        this.launcherChunk.release(this.launcher);
    }

    private boolean pathExhaustedNearby(double distSq, double useDistance) {
        double slack = useDistance + NAV_STUCK_SLACK;
        return this.unit.getNavigation().isDone() && distSq <= slack * slack;
    }

    private long deadlineFromNow() {
        return this.unit.level().getGameTime() + APPROACH_TIMEOUT_TICKS;
    }

    @Override
    public void stop() {
        UnitHolster.setManningMortar(this.unit, false);
        this.unit.getNavigation().stop();
        this.approachTicks = 0;
        releaseForcedChunks();
        hold("goal stopped (preempted, or the order ended)");
    }
}
