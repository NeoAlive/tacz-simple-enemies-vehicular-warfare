package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.item.LockItem;

/**
 * Lets RU/US infantry claim an abandoned mortar it walks past — the
 * {@link SeekAbandonedVehicleGoal} feature, extended to the one {@code VehicleEntity} that goal
 * deliberately excludes.
 *
 * <p>A mortar has no seats, so a unit can never board it the way it boards a hull — its crew
 * works it standing beside the tube via an {@link com.neoalive.tacz_sewv.bridge.IMortarCrew}
 * claim ({@link MortarSupport#claim}) instead of an {@link IVehicleBoarder} mount order. That is
 * the whole reason this is a separate goal rather than one more case in
 * {@code SeekAbandonedVehicleGoal.findAbandonedVehicle}: the claim it writes and the goal that
 * reads it ({@link ManMortarGoal}) are both different from the vehicle path.
 *
 * <p>Same shape as {@link SeekAbandonedVehicleGoal} otherwise: claims <b>no flags</b>, does its
 * work inside {@code canUse} and always returns false so it can never contend with
 * {@link ManMortarGoal} for MOVE/LOOK, and is RU/US only — a PMC's mortar order comes from its
 * owner over the network bridge ({@code PacketManMortar}), not from the unit's own initiative.
 *
 * <p>Guards against a unit that already holds a pending vehicle-board order, and
 * {@link SeekAbandonedVehicleGoal} guards the other way: both {@code ManMortarGoal} and
 * {@code BoardVehicleGoal} sit at goal priority 1 holding MOVE, so a unit handed both claims at
 * once would have one order permanently starved by the other winning the flag on registration
 * order — the same mutual exclusion {@link SeekEntrenchmentGoal} already keeps against both.
 */
public class SeekAbandonedMortarGoal extends Goal {

    /** Same cadence as {@link SeekAbandonedVehicleGoal} — see its doc for why. */
    private static final int SCAN_INTERVAL = 40;
    private static final int MAX_SCAN_INTERVAL = 200;

    private final AbstractUnit unit;
    private int scanCooldown;
    private int scanInterval = SCAN_INTERVAL;

    public SeekAbandonedMortarGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    /**
     * Always false — see the class doc. The scan happens here because {@code canUse} is the only
     * thing the goal selector calls on a goal that never runs.
     */
    @Override
    public boolean canUse() {
        if (!shouldScan()) return false;
        if (this.scanCooldown-- > 0) return false;

        MortarEntity mortar = findAbandonedMortar();
        this.scanInterval = mortar == null
                ? Math.min(MAX_SCAN_INTERVAL, this.scanInterval * 2)
                : SCAN_INTERVAL;
        this.scanCooldown = this.scanInterval;
        if (mortar != null) {
            MortarSupport.claim(this.unit, mortar);
        }
        return false;
    }

    private boolean shouldScan() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.AUTO_MAN_MORTAR_ENABLED.get()) return false;
        // RU/US only. A PMC takes its mortar assignment from its owner.
        if (!(this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity)) return false;
        if (this.unit.isPassenger()) return false;
        // Never break off a fight to go looking for a tube.
        if (this.unit.getTarget() != null) return false;
        // A vehicle-board order already stands — let it resolve (or time out) before this goal
        // hands the unit a second, flag-conflicting claim. See the class doc.
        if (this.unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) return false;
        // An order already stands; let ManMortarGoal work it (or release it) before re-scanning.
        return !MortarSupport.hasMortarClaim(this.unit);
    }

    /** The nearest mortar worth taking, or null. */
    private MortarEntity findAbandonedMortar() {
        double radius = SewvConfig.AUTO_MAN_MORTAR_SCAN_RADIUS.get();
        List<MortarEntity> candidates = this.unit.level().getEntitiesOfClass(
                MortarEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                this::isAbandoned);

        return candidates.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    private boolean isAbandoned(MortarEntity mortar) {
        if (!mortar.isAlive() || mortar.isWreck()) return false;
        if (LockItem.isLocked(mortar)) return false;
        // Scans for a unit already pointing at it — see MortarSupport.isMortarClaimed. This is
        // also what makes the claim self-healing: a crew that died or unloaded stops pointing at
        // its tube and it silently becomes eligible again.
        if (MortarSupport.isMortarClaimed(mortar, this.unit)) return false;

        // Same threshold as vehicle scavenging, and the same reasoning: a wrecked tube a crew
        // would only abandon again. SBW has no separate "owner" signal for a mortar the way a
        // vehicle's last driver is one (nobody ever rides a mortar to begin with), so there is no
        // player-ownership check to mirror here.
        float max = mortar.getMaxHealth();
        return !(max > 0.0F
                && mortar.getHealth() < max * SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION.get().floatValue());
    }
}
