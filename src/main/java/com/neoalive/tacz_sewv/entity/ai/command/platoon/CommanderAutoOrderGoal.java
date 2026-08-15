package com.neoalive.tacz_sewv.entity.ai.command.platoon;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * A Commander's idle-driven standing orders to its own platoon — rappel, search &amp; destroy, or
 * patrol. Fires only while the platoon has no active {@code BattleGroup} doctrine assignment (the
 * same carve-out {@link com.neoalive.tacz_sewv.entity.ai.goal.PlatoonCohesionGoal} uses), on a
 * game-time deadline like {@code IdleCrewGoal}. Claims no flags — it writes orders onto other
 * units' own goal state, it never moves the Commander itself.
 */
public class CommanderAutoOrderGoal extends Goal {

    private static final int MIN_ROLL_TICKS = 400;
    private static final int ROLL_JITTER_TICKS = 400;

    private final PmcCommanderEntity commander;
    private long nextRoll = Long.MIN_VALUE;

    public CommanderAutoOrderGoal(PmcCommanderEntity commander) {
        this.commander = commander;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return !this.commander.level().isClientSide() && this.commander.isAlive()
                && this.commander.autoOrdersEnabled();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        long now = this.commander.level().getGameTime();
        if (this.nextRoll == Long.MIN_VALUE) {
            this.nextRoll = now + rollDelay();
            return;
        }
        if (now < this.nextRoll) return;
        this.nextRoll = now + rollDelay();

        if (!(this.commander.level() instanceof ServerLevel level)) return;
        Platoon platoon = PlatoonRegistry.platoonOf(level, this.commander.getId());
        // Only the platoon's own elected leader acts — a Commander who ended up as a plain member
        // of someone else's already-commanded platoon issues nothing.
        if (platoon == null || platoon.size() < 2
                || !platoon.hasCommander() || platoon.commanderId() != this.commander.getId()) {
            return;
        }
        // A platoon under a live doctrine assignment is already fighting from a plan — an
        // autonomous standing order here would just fight it.
        for (int memberId : platoon.memberIds()) {
            if (CrewAssignment.of(memberId) != null) return;
        }

        CommanderOrderDispatch.dispatch(level, this.commander, platoon, pickOrder(platoon));
    }

    private CommanderOrderType pickOrder(Platoon platoon) {
        if (platoon.type() == Platoon.Type.INFANTRY
                && this.commander.getVehicle() instanceof VehicleEntity hull
                && HullFacts.isHelicopterHull(hull)) {
            return CommanderOrderType.RAPPEL;
        }
        return this.commander.getRandom().nextBoolean()
                ? CommanderOrderType.SEARCH_AND_DESTROY
                : CommanderOrderType.PATROL;
    }

    private int rollDelay() {
        return MIN_ROLL_TICKS + this.commander.getRandom().nextInt(ROLL_JITTER_TICKS);
    }
}
