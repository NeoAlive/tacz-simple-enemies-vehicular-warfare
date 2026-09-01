package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;

/**
 * RU/US forward observer: relays a live contact to friendly fixed-wing CAS in radio range.
 *
 * <p>PMC uses a physical radio ({@link RadioObserverGoal}); hostile factions use organic comms
 * ({@link SewvConfig#FACTION_ORGANIC_COMMS}) instead. Infantry, vehicle crews and helicopter
 * pilots all share this path — the unit keeps fighting; it only phones in whatever it already
 * holds as a target.
 */
public class CasRelayGoal extends Goal {

    private static final Set<FireMissionSupport.Kind> CAS_ONLY = Set.of(FireMissionSupport.Kind.CAS);

    /** Game ticks between rolls. */
    private static final int CHECK_INTERVAL = 20;

    /**
     * One roll in this many actually calls. Without this, every contact would re-task every
     * aircraft in range on every interval.
     */
    private static final int CALL_CHANCE = 10;

    /**
     * How long to wait after a call finds no aircraft. The roster scan is the expensive half,
     * so a unit with nothing airborne behind it backs off instead of paying every roll.
     */
    private static final int NO_AIRCRAFT_BACKOFF = 100;

    private final AbstractUnit unit;

    /** Game time, so the intervals mean the ticks they say; goals tick every other tick. */
    private long nextCheck;

    public CasRelayGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.FACTION_ORGANIC_COMMS.get()) return false;
        if (FireMissionSupport.kindOf(this.unit) == FireMissionSupport.Kind.CAS) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        return VehicleTargeting.mayAssignTarget(this.unit, target);
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        if (now < this.nextCheck) return;
        this.nextCheck = now + CHECK_INTERVAL;

        if (this.unit.getRandom().nextInt(CALL_CHANCE) != 0) return;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return;
        if (!VehicleTargeting.mayAssignTarget(this.unit, target)) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(this.unit);
        if (faction == null || faction == CrewFacts.Faction.PMC) return;

        FireMissionSupport.Call call = FireMissionSupport.callFireMission(
                this.unit.level(), faction, null, this.unit.position(),
                SewvConfig.MORTAR_RADIO_RANGE.get(), target, CAS_ONLY);

        if (call.empty()) {
            this.nextCheck = now + NO_AIRCRAFT_BACKOFF;
        }
    }
}
