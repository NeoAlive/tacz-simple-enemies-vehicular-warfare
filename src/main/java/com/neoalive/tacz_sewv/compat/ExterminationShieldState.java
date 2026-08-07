package com.neoalive.tacz_sewv.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Breakable ranged shield budget on Extermination pods. Hits accumulate toward
 * {@link SewvConfig#TRIPOD_SHIELD_BREAK_DAMAGE}; while under budget the hit is cancelled. After
 * break, ranged damage applies until optional regen ({@link SewvConfig#TRIPOD_SHIELD_REGEN_TICKS}).
 */
public final class ExterminationShieldState {

    private static final String TAG_ABSORBED = "sewv:shield_absorbed";
    private static final String TAG_BROKEN = "sewv:shield_broken";
    private static final String TAG_REGEN_AT = "sewv:shield_regen_at";

    /** TACZ Pre + Attack/Hurt can see the same hit in one game tick — count it once. */
    private static int lastAbsorbEntityId = -1;
    private static long lastAbsorbGameTime = Long.MIN_VALUE;
    private static boolean lastAbsorbBlocked;

    private ExterminationShieldState() {}

    /** {@code true} if the shield is up and this hit should be cancelled. */
    public static boolean tryAbsorb(LivingEntity pod, float amount) {
        if (amount <= 0.0f) return false;
        maybeRegen(pod);

        long gameTime = pod.level().getGameTime();
        int id = pod.getId();
        if (id == lastAbsorbEntityId && gameTime == lastAbsorbGameTime) {
            return lastAbsorbBlocked;
        }

        CompoundTag data = pod.getPersistentData();
        if (data.getBoolean(TAG_BROKEN)) {
            remember(id, gameTime, false);
            return false;
        }

        float absorbed = data.getFloat(TAG_ABSORBED) + amount;
        float budget = SewvConfig.TRIPOD_SHIELD_BREAK_DAMAGE.get().floatValue();
        if (absorbed >= budget) {
            data.putFloat(TAG_ABSORBED, budget);
            data.putBoolean(TAG_BROKEN, true);
            int regen = SewvConfig.TRIPOD_SHIELD_REGEN_TICKS.get();
            if (regen > 0) {
                data.putLong(TAG_REGEN_AT, gameTime + regen);
            } else {
                data.remove(TAG_REGEN_AT);
            }
        } else {
            data.putFloat(TAG_ABSORBED, absorbed);
        }
        remember(id, gameTime, true);
        return true;
    }

    public static boolean isUp(@Nullable LivingEntity pod) {
        if (pod == null) return false;
        maybeRegen(pod);
        return !pod.getPersistentData().getBoolean(TAG_BROKEN);
    }

    private static void remember(int id, long gameTime, boolean blocked) {
        lastAbsorbEntityId = id;
        lastAbsorbGameTime = gameTime;
        lastAbsorbBlocked = blocked;
    }

    private static void maybeRegen(LivingEntity pod) {
        CompoundTag data = pod.getPersistentData();
        if (!data.getBoolean(TAG_BROKEN)) return;
        if (!data.contains(TAG_REGEN_AT)) return;
        long at = data.getLong(TAG_REGEN_AT);
        if (pod.level().getGameTime() < at) return;
        data.putBoolean(TAG_BROKEN, false);
        data.putFloat(TAG_ABSORBED, 0.0f);
        data.remove(TAG_REGEN_AT);
    }
}
