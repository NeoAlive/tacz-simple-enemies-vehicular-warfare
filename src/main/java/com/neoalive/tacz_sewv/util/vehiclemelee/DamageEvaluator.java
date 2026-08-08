package com.neoalive.tacz_sewv.util.vehiclemelee;

import net.minecraft.util.Mth;

/**
 * Pure score → damage for mob melee against SuperbWarfare hulls. Replaces datapack zeroes for
 * {@code minecraft:mob_attack} when {@code canMobsDamageVehicles} is on.
 */
public final class DamageEvaluator {

    private static final float BASE_WEIGHT = 0.35F;
    private static final float HEALTH_WEIGHT = 0.65F;
    private static final float WEAPON_STEP = 0.2F;
    private static final float SOFT_REF_HP = 12.0F;
    private static final float SWARM_STEP = 0.08F;
    private static final int SWARM_CAP = 5;
    private static final float MIN_HIT = 0.5F;
    private static final float MAX_FRAC_OF_HULL = 0.04F;
    /** Global output scale (post-score, pre-clamp envelope scales with it). */
    private static final float OUTPUT_SCALE = 4.0F;

    private DamageEvaluator() {}

    public static float evaluate(AttackerFacts attacker, VehicleFacts vehicle) {
        float raw = attacker.attackDamage()
                * (BASE_WEIGHT + HEALTH_WEIGHT * attacker.healthFrac())
                * (1.0F + WEAPON_STEP * attacker.weaponBonus());
        float scaled = raw * (SOFT_REF_HP / Math.max(vehicle.maxHealth(), SOFT_REF_HP));
        int extras = Math.min(Math.max(vehicle.attackerCount() - 1, 0), SWARM_CAP);
        float swarm = 1.0F + SWARM_STEP * extras;
        float damage = scaled * swarm * OUTPUT_SCALE;
        float minHit = MIN_HIT * OUTPUT_SCALE;
        float cap = vehicle.maxHealth() * MAX_FRAC_OF_HULL * OUTPUT_SCALE;
        return Mth.clamp(damage, minHit, Math.max(minHit, cap));
    }
}
