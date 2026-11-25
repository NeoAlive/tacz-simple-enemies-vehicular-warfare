package com.neoalive.tacz_sewv.entity.ai.targeting;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import com.neoalive.tacz_sewv.entity.ai.suppression.HullPressure;

/**
 * Targets the priority stack must not take away: one handed out by an order-like path (a fire
 * mission, a FOB scramble, a drone sighting, a helicopter's committed pass), or the unit's own
 * recent attacker. Transient: a stamp is a game time, kept per unit until it lapses.
 */
public final class AssignedTargets {

    /** How long an assigned target is held against re-ranking. */
    public static final long HOLD_TICKS = 200;

    private static final Map<Mob, Long> UNTIL = Collections.synchronizedMap(new WeakHashMap<>());

    private AssignedTargets() {}

    /** Called by a path that sets a target the stack must leave alone. */
    public static void stamp(Mob unit) {
        UNTIL.put(unit, unit.level().getGameTime() + HOLD_TICKS);
    }

    /** True while the unit's current target is assigned or is retaliation. */
    public static boolean held(Mob unit) {
        long now = unit.level().getGameTime();
        Long until = UNTIL.get(unit);
        if (until != null) {
            if (now < until) return true;
            UNTIL.remove(unit);
        }
        LivingEntity current = unit.getTarget();
        if (current == null) return false;
        if (current == unit.getLastHurtByMob()
                && unit.tickCount - unit.getLastHurtByMobTimestamp() <= HullPressure.RETALIATE_WINDOW) {
            return true;
        }
        return unit.getVehicle() instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity hull
                && HullPressure.recentHeavyAttacker(hull, now) == current;
    }

    /**
     * Hurt recently by a hostile other than the current target: retaliation should get its turn.
     * Friendly fire does not count, since retaliation would refuse it and the unit would sit idle.
     */
    public static boolean hurtByOther(net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit unit) {
        LivingEntity attacker = unit.getLastHurtByMob();
        return attacker != null && attacker != unit.getTarget() && attacker.isAlive()
                && unit.tickCount - unit.getLastHurtByMobTimestamp() <= HullPressure.RETALIATE_WINDOW
                && !com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting.isNonHostile(unit, attacker);
    }
}
