package com.neoalive.tacz_sewv.util;

import java.util.List;

import net.minecraft.util.RandomSource;

/**
 * Shared Fisher–Yates shuffle. Used by {@link VehicleEngineLoot} (loot slot scrambling) and by
 * {@link com.neoalive.tacz_sewv.crew.NamePools} (draw-without-replacement name pools).
 */
public final class RandomUtil {

    private RandomUtil() {
    }

    /** In-place Fisher–Yates shuffle. */
    public static <T> void shuffle(List<T> list, RandomSource random) {
        int n = list.size();
        if (n < 2) return;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T a = list.get(i);
            list.set(i, list.get(j));
            list.set(j, a);
        }
    }
}
