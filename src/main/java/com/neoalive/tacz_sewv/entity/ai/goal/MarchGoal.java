package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.march.March;
import com.neoalive.tacz_sewv.entity.ai.march.MarchRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.InfantryOrders;

/**
 * An RU/US unit on foot keeping its place in a march ({@link MarchRegistry}): a squad leader trailing an idle
 * column, or a rifleman in the file behind a CohesiveLine hull.
 *
 * <p><b>Pace without knowing the walk speed.</b> The unit walks for a point a little down the road from its slot
 * and stops dead once it is ahead of the slot, so it moves at the march's pace by walking and waiting rather than
 * by a speed that would have to match SEM's movement attribute and every terrain modifier. Behind, it hurries.
 *
 * <p>Under {@code IDLE_TRAVEL} it is out-of-contact only — a contact ends the march anyway. Under
 * {@code COHESIVE_LINE} it <b>keeps MOVE through combat</b>, so the rifleman keeps walking behind its hull while
 * SEM's rifle goal fires on the LOOK flag; that is the whole point of the line, and the {@code EscortGoal}
 * precedent. Priority 1, MOVE; yields to every other order goal by declining.
 */
public class MarchGoal extends Goal {

    private static final int REPATH_INTERVAL = 8;
    /** How far down the road from its slot the unit walks for. */
    private static final double CARROT = 4.0;
    /** Ahead of its slot by more than this, it waits. */
    private static final double WAIT_AHEAD = 1.5;
    private static final double SPEED = 1.0;
    private static final double HURRY_SPEED = 1.3;
    private static final double HURRY_LAG = 6.0;

    private final AbstractUnit unit;
    private int repathCooldown;

    public MarchGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return slot() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return slot() != null;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--this.repathCooldown > 0) return;
        this.repathCooldown = REPATH_INTERVAL;
        MarchRegistry.Slot slot = slot();
        if (slot == null) return;
        double lag = slot.lag(this.unit.getX(), this.unit.getZ());
        boolean inPlace = slot.paused() ? slot.caughtUp(this.unit.getX(), this.unit.getZ()) : lag < -WAIT_AHEAD;
        if (inPlace) {
            this.unit.getNavigation().stop();
            this.unit.getLookControl().setLookAt(this.unit.getX() + slot.fx() * 8.0, this.unit.getEyeY(),
                    this.unit.getZ() + slot.fz() * 8.0);
            return;
        }
        double carrot = slot.paused() ? 0.0 : CARROT;
        double x = slot.x() + slot.fx() * carrot;
        double z = slot.z() + slot.fz() * carrot;
        double y = GroundSnap.groundY(this.unit.level(), x, z, this.unit.getY());
        this.unit.getNavigation().moveTo(x, y, z, lag > HURRY_LAG ? HURRY_SPEED : SPEED);
    }

    /** This unit's march slot if it may walk it now, else null. */
    private MarchRegistry.Slot slot() {
        MarchRegistry.Slot slot = MarchRegistry.slotFor(this.unit.getId(), this.unit.level().getGameTime());
        if (slot == null || slot.hull()) return null;
        boolean fights = slot.kind() == March.Kind.COHESIVE_LINE;
        return (fights ? InfantryOrders.unordered(this.unit) : InfantryOrders.free(this.unit)) ? slot : null;
    }
}
