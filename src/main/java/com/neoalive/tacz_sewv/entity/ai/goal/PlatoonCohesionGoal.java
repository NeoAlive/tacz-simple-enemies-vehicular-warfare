package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.Platoon;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.PlatoonRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.InfantrySquads;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/**
 * Platoons always try to stay within {@code SewvConfig.PLATOON_COHESION_RADIUS} of each other.
 * Added to every {@code PmcUnitEntity} (not Commander-only — every member needs the leash), and
 * only ever engages when the unit is otherwise idle: no target, no standing positional order
 * (mirrors {@link MoveToPositionGoal}/{@link EscortGoal}'s own order gates so it never fights
 * them), and no platoon member currently under a live {@code BattleGroup} doctrine assignment —
 * "unless performing doctrine operations".
 *
 * <p>The leash is still measured from the platoon centroid, but a straggler walks back to its own slot in a
 * wedge behind the platoon commander ({@link InfantrySquads#slot}, ranked by id), not to the centroid itself —
 * which used to pile every returning member onto one point. Without a live commander (or for the commander
 * itself) the centroid remains the destination.
 */
public class PlatoonCohesionGoal extends Goal {

    private static final double SPEED = 1.0;
    private static final int REPATH_INTERVAL = 20;
    /** Done once this close to the destination. */
    private static final double ARRIVE = 2.0;

    private final PmcUnitEntity unit;
    private double destX;
    private double destZ;
    private int repathCooldown;

    public PlatoonCohesionGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Platoon platoon = eligiblePlatoon();
        if (platoon == null) return false;

        double radius;
        try {
            radius = SewvConfig.PLATOON_COHESION_RADIUS.get();
        } catch (Throwable ignored) {
            return false;
        }
        double dx = this.unit.getX() - platoon.centroidX();
        double dz = this.unit.getZ() - platoon.centroidZ();
        if (dx * dx + dz * dz <= radius * radius) return false;

        aimAt(platoon);
        return true;
    }

    /** Keep going until on the slot — not just back inside the radius, which would stop it at the edge. */
    @Override
    public boolean canContinueToUse() {
        Platoon platoon = eligiblePlatoon();
        if (platoon == null) return false;
        aimAt(platoon);
        double dx = this.unit.getX() - this.destX;
        double dz = this.unit.getZ() - this.destZ;
        return dx * dx + dz * dz > ARRIVE * ARRIVE;
    }

    @javax.annotation.Nullable
    private Platoon eligiblePlatoon() {
        if (this.unit.level().isClientSide()) return null;
        if (this.unit.isPassenger()) return null;
        if (this.unit.getTarget() != null) return null;
        if (this.unit.getOrder() != OrderType.FREE_FIRE) return null;
        if (!(this.unit.level() instanceof ServerLevel level)) return null;
        Platoon platoon = PlatoonRegistry.platoonOf(level, this.unit.getId());
        if (platoon == null || platoon.size() < 2 || underDoctrine(platoon)) return null;
        return platoon;
    }

    /** This member's wedge slot behind the commander, or the centroid when there is no one to form on. */
    private void aimAt(Platoon platoon) {
        this.destX = platoon.centroidX();
        this.destZ = platoon.centroidZ();
        if (!platoon.hasCommander() || platoon.commanderId() == this.unit.getId()) return;
        if (!(this.unit.level().getEntity(platoon.commanderId()) instanceof LivingEntity commander)
                || !commander.isAlive()) {
            return;
        }
        int rank = 0;
        int members = 0;
        for (int id : platoon.memberIds()) {
            if (id == platoon.commanderId()) continue;
            members++;
            if (id < this.unit.getId()) rank++;
        }
        Vec3 f = VehicleFormation.forwardOfYaw(commander.yBodyRot);
        Vec3 slot = InfantrySquads.slot(commander.position(), f.x, f.z, rank, Math.max(1, members), false);
        this.destX = slot.x;
        this.destZ = slot.z;
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
        this.unit.getLookControl().setLookAt(this.destX, this.unit.getY(), this.destZ, 30.0F, 30.0F);
        if (--this.repathCooldown > 0) return;
        this.repathCooldown = REPATH_INTERVAL;
        this.unit.getNavigation().moveTo(this.destX, this.unit.getY(), this.destZ, SPEED);
    }

    private static boolean underDoctrine(Platoon platoon) {
        for (int memberId : platoon.memberIds()) {
            if (CrewAssignment.of(memberId) != null) return true;
        }
        return false;
    }
}
