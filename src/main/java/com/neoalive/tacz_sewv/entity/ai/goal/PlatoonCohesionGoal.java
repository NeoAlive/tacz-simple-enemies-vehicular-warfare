package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.Platoon;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.PlatoonRegistry;

/**
 * Platoons always try to stay within {@code SewvConfig.PLATOON_COHESION_RADIUS} of each other.
 * Added to every {@code PmcUnitEntity} (not Commander-only — every member needs the leash), and
 * only ever engages when the unit is otherwise idle: no target, no standing positional order
 * (mirrors {@link MoveToPositionGoal}/{@link EscortGoal}'s own order gates so it never fights
 * them), and no platoon member currently under a live {@code BattleGroup} doctrine assignment —
 * "unless performing doctrine operations".
 */
public class PlatoonCohesionGoal extends Goal {

    private static final double SPEED = 1.0;
    private static final int REPATH_INTERVAL = 20;

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
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false;
        if (this.unit.getTarget() != null) return false;
        if (this.unit.getOrder() != OrderType.FREE_FIRE) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;

        Platoon platoon = PlatoonRegistry.platoonOf(level, this.unit.getId());
        if (platoon == null || platoon.size() < 2 || underDoctrine(platoon)) return false;

        double radius;
        try {
            radius = SewvConfig.PLATOON_COHESION_RADIUS.get();
        } catch (Throwable ignored) {
            return false;
        }
        double dx = this.unit.getX() - platoon.centroidX();
        double dz = this.unit.getZ() - platoon.centroidZ();
        if (dx * dx + dz * dz <= radius * radius) return false;

        this.destX = platoon.centroidX();
        this.destZ = platoon.centroidZ();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
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
