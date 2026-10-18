package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.march.MarchRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.InfantryOrders;
import com.neoalive.tacz_sewv.entity.ai.support.InfantrySquads;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/**
 * An RU/US squad member keeping its slot behind its leader while nothing is happening — see
 * {@link InfantrySquads}. The leader runs no such goal and keeps SEM's own wander and investigate, so a squad
 * goes where its leader's curiosity takes it, as one.
 *
 * <p><b>Out of contact only.</b> The moment the member holds a target it stands down and SEM's cover, chase and
 * peek goals take the fight exactly as before; nothing here changes how a squad fights. A member that a fight
 * scattered walks back to its slot when it ends, which is the whole of the leash.
 *
 * <p>Priority 1, MOVE, like the other order goals at that priority — and it <b>yields to every one of them</b>
 * (a board order, a mortar claim, a capture order, an entrenchment, a tow recovery, an escort — see
 * {@link InfantryOrders}), and to a march place of the unit's own ({@code MarchGoal}). At equal priority whoever
 * holds MOVE keeps it, so declining in {@code canUse} is the only way to let them in. A squad <i>member</i> of a
 * marching squad keeps this goal: it follows its leader, and the leader holds the march place.
 *
 * <p>Squad resolution is an entity scan, so {@code canUse} is throttled on a game-time deadline and the slot is
 * re-resolved only every few repaths.
 */
public class InfantrySquadGoal extends Goal {

    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int REPATH_INTERVAL = 10;
    private static final int RESOLVE_EVERY_REPATHS = 3;
    /** Start walking past this distance from the slot; settle inside it. */
    private static final double START = 4.0;
    private static final double SETTLE = 1.5;
    private static final double SPEED = 1.1;
    private static final double CATCH_UP_SPEED = 1.3;
    private static final double MOVING_EPS = 0.02;

    private final AbstractUnit unit;
    private InfantrySquads.View view;
    private long nextCheck;
    private int repathCooldown;
    private int repaths;
    private double headingX = Double.NaN;
    private double headingZ = Double.NaN;

    public InfantrySquadGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.level() instanceof ServerLevel level) || !free()) return false;
        long now = level.getGameTime();
        if (now < this.nextCheck) return false;
        this.nextCheck = now + CHECK_INTERVAL_TICKS;
        if (InfantrySquads.squadOf(this.unit) == null) return false;
        this.view = InfantrySquads.resolve(level, this.unit);
        return this.view != null && this.unit.position().distanceToSqr(slot()) > START * START;
    }

    @Override
    public boolean canContinueToUse() {
        return this.view != null && this.view.leader().isAlive() && free();
    }

    /** No fight, no ride, no other order holding this unit's legs, and no march place of its own. */
    private boolean free() {
        return InfantryOrders.free(this.unit) && !MarchRegistry.isMarching(this.unit.getId());
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.repaths = 0;
    }

    @Override
    public void stop() {
        this.view = null;
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--this.repathCooldown > 0) return;
        this.repathCooldown = REPATH_INTERVAL;
        if (++this.repaths % RESOLVE_EVERY_REPATHS == 0 && this.unit.level() instanceof ServerLevel level) {
            InfantrySquads.View fresh = InfantrySquads.resolve(level, this.unit);
            if (fresh == null) {
                this.view = null; // canContinueToUse ends the goal
                return;
            }
            this.view = fresh;
        }
        Vec3 slot = slot();
        double distSq = this.unit.position().distanceToSqr(slot);
        if (distSq <= SETTLE * SETTLE) {
            this.unit.getNavigation().stop();
            this.unit.getLookControl().setLookAt(slot.x + this.headingX * 8.0, this.unit.getEyeY(),
                    slot.z + this.headingZ * 8.0);
            return;
        }
        this.unit.getNavigation().moveTo(slot.x, slot.y, slot.z, distSq > 64.0 ? CATCH_UP_SPEED : SPEED);
    }

    /**
     * This member's slot. The squad faces the way its leader walks; a leader standing still keeps the last
     * heading it walked (or its body facing, before it has walked at all), so a pause does not spin the wedge.
     */
    private Vec3 slot() {
        AbstractUnit leader = this.view.leader();
        Vec3 v = leader.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed > MOVING_EPS) {
            this.headingX = v.x / speed;
            this.headingZ = v.z / speed;
        } else if (Double.isNaN(this.headingX)) {
            Vec3 f = VehicleFormation.forwardOfYaw(leader.yBodyRot);
            this.headingX = f.x;
            this.headingZ = f.z;
        }
        Vec3 p = InfantrySquads.slot(leader.position(), this.headingX, this.headingZ, this.view.slot(),
                this.view.members(), speed > InfantrySquads.COLUMN_SPEED);
        double y = GroundSnap.groundY(this.unit.level(), p.x, p.z, leader.getY());
        return new Vec3(p.x, y, p.z);
    }
}
