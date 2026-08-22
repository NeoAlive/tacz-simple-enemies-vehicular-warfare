package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Freeze a captured RU/US medic in place: claims MOVE + LOOK + JUMP to outrank all competing goals,
 * re-asserts the synced capture flag every tick (chunk-reload self-heal), and on timeout simply
 * clears the captured state (letting normal AI resume) rather than killing the unit. Modeled on
 * {@code DownedGoal} with that key divergence.
 *
 * <p>Runs at priority 0 (alongside the existing {@code FloatGoal} for the same unit type —
 * multiple priority-0 goals coexisting is an established pattern in SEM units). Gated on
 * {@code IMedicCaptured} to ensure it only affects RU/US medics (never PMC, which doesn't
 * implement that interface).
 */
public class MedicCapturedGoal extends Goal {
    private final AbstractUnit unit;

    public MedicCapturedGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit instanceof IMedicCaptured captured)) {
            return false;
        }
        return captured.sewv$isCaptured() && SewvConfig.MEDIC_CAPTURE_ENABLED.get();
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.unit instanceof IMedicCaptured captured)) {
            return false;
        }
        if (!captured.sewv$isCaptured()) {
            return false;
        }
        // Check for timeout: on absolute deadline reached, clear the captured state.
        if (this.unit.level().getGameTime() >= captured.sewv$capturedDeadline()) {
            captured.sewv$setCaptured(false, 0L);
            captured.sewv$setCapturedSynced(false);
            return false; // Stop the goal.
        }
        return true;
    }

    @Override
    public void start() {
        this.unit.getNavigation().stop();
        // Note: unlike DownedGoal, we don't clear the target here because a medic never holds one anyway.
    }

    @Override
    public void tick() {
        // Reassert the synced flag every tick for chunk-reload self-heal (persistent NBT doesn't
        // replicate to client, so the synced mirror needs constant reinforcement from the goal).
        if (this.unit instanceof IMedicCaptured captured) {
            captured.sewv$setCapturedSynced(true);
        }
    }

    @Override
    public void stop() {
        // Nothing to clean up — the goal releases its flags naturally on stop.
    }
}
