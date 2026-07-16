package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Designating a target for crews that cannot find their own, shared by
 * {@link com.neoalive.tacz_sewv.item.HandheldRadioItem} and {@link RadioObserverGoal}.
 *
 * <p>Both weapons this serves outrange the eyes behind them. A mortar shoots ~770 blocks
 * and its crew sees SEM's FOLLOW_RANGE of 96 (and only ±4 vertically). A TOW crew is
 * mounted, so it gets {@link VehicleTargetScanGoal}'s cylinder instead — but that is still
 * only {@code vehicleTargetScanRadius} of level ground with line of sight, which is not
 * where you want to be choosing which tank in a column dies first. The radio is how a
 * target reaches either of them.
 */
public final class FireMissionSupport {

    private FireMissionSupport() {}

    /**
     * Whether a unit is manning something a fire mission means anything to. A rifleman is
     * not: it can see whatever it can shoot, so designating for it would only override the
     * target it already picked.
     */
    private static boolean servesFireMissions(PmcUnitEntity unit) {
        return MortarSupport.hasMortarClaim(unit) || TowSupport.isCrewing(unit);
    }

    /**
     * One owner's fire-mission crews within {@code range} of {@code origin}.
     *
     * <p>A crew in an unloaded chunk can't be found, which is what mortarChunkLoading keeps
     * from happening.
     */
    public static List<PmcUnitEntity> crewsInRange(Level level, @Nullable UUID owner, Vec3 origin, double range) {
        if (owner == null) return List.of();

        List<PmcUnitEntity> crews = new ArrayList<>();
        for (PmcUnitEntity pmc : level.getEntitiesOfClass(
                PmcUnitEntity.class, new AABB(origin, origin).inflate(range))) {
            if (pmc.isAlive() && owner.equals(pmc.getOwnerUUID()) && servesFireMissions(pmc)) {
                crews.add(pmc);
            }
        }
        return crews;
    }

    /**
     * Puts every crew in range onto {@code target}, and reports how many took it.
     *
     * <p>SEM's AttackSpecificTargetGoal (targetSelector priority 0) re-forces the target
     * from this id every 5 ticks, so it overrides a crew's own scan for as long as the
     * order stands — which is the whole point. For a mounted TOW crew it also stands down
     * {@link VehicleTargetScanGoal}, which yields under ATTACK_THAT_TARGET rather than
     * fight SEM's goal for the TARGET flag.
     */
    public static int callFireMission(Level level, @Nullable UUID owner, Vec3 origin,
                                      double range, LivingEntity target) {
        List<PmcUnitEntity> crews = crewsInRange(level, owner, origin, range);
        for (PmcUnitEntity crew : crews) {
            crew.setAttackTargetId(target.getId());
            crew.setOrder(OrderType.ATTACK_THAT_TARGET);
        }
        return crews.size();
    }

    /**
     * Ends the fire mission for every crew in range, and reports how many were on one.
     *
     * <p>Dropping the order is enough on its own: SEM's goal goes false, and its stop()
     * clears the target and the stored id for us — after which the crew is back on its own
     * targeting.
     */
    public static int standDown(Level level, @Nullable UUID owner, Vec3 origin, double range) {
        int released = 0;
        for (PmcUnitEntity crew : crewsInRange(level, owner, origin, range)) {
            if (crew.getOrder() != OrderType.ATTACK_THAT_TARGET) continue;
            crew.setOrder(OrderType.FREE_FIRE);
            crew.setAttackTargetId(-1);
            released++;
        }
        return released;
    }
}
