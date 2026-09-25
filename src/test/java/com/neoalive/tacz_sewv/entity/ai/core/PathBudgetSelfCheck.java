package com.neoalive.tacz_sewv.entity.ai.core;

import net.minecraft.core.BlockPos;

/**
 * Headless check of the path-search budget decision and the destination quantum.
 * Run via {@code ./gradlew selfCheckPathBudget}.
 */
public final class PathBudgetSelfCheck {

    public static void main(String[] args) {
        unlimited();
        cap();
        starvationBound();
        lowPriority();
        simulatedQueue();
        quantum();
        System.out.println("PathBudgetSelfCheck OK");
    }

    private static void unlimited() {
        assert PathBudget.decide(1000, 0, 0, 10, false) : "limit 0 = the old unlimited behaviour";
        assert PathBudget.decide(1000, 0, 0, 10, true);
    }

    private static void cap() {
        assert PathBudget.decide(0, 2, 0, 10, false);
        assert PathBudget.decide(1, 2, 0, 10, false);
        assert !PathBudget.decide(2, 2, 0, 10, false) : "third search in the tick is refused";
    }

    private static void starvationBound() {
        assert !PathBudget.decide(5, 2, 9, 10, false);
        assert PathBudget.decide(5, 2, 10, 10, false) : "waited maxWait: searches regardless of the cap";
    }

    private static void lowPriority() {
        assert PathBudget.decide(0, 2, 0, 10, true) : "low priority gets the lower half of the slots";
        assert !PathBudget.decide(1, 2, 0, 10, true);
        assert !PathBudget.decide(0, 1, 0, 10, true) : "with one slot, low priority only gets in by waiting";
        assert !PathBudget.decide(0, 1, 19, 10, true);
        assert PathBudget.decide(0, 1, 20, 10, true) : "...and waits twice as long";
    }

    /** 10 hulls all stale on tick 0, cap 2, maxWait 10: everyone is served within the bound. */
    private static void simulatedQueue() {
        int hulls = 10, limit = 2, maxWait = 10;
        long[] waitingSince = new long[hulls];
        boolean[] served = new boolean[hulls];
        java.util.Arrays.fill(waitingSince, Long.MIN_VALUE);
        int servedCount = 0;
        for (long tick = 0; tick < 20 && servedCount < hulls; tick++) {
            int used = 0;
            for (int h = 0; h < hulls; h++) {
                if (served[h]) continue;
                long waited = waitingSince[h] == Long.MIN_VALUE ? 0 : tick - waitingSince[h];
                if (PathBudget.decide(used, limit, waited, maxWait, false)) {
                    used++;
                    served[h] = true;
                    servedCount++;
                    assert waited <= maxWait : "hull " + h + " waited " + waited;
                } else if (waitingSince[h] == Long.MIN_VALUE) {
                    waitingSince[h] = tick;
                }
            }
            assert used <= limit || tick >= maxWait : "cap only exceeded by the starvation bound";
        }
        assert servedCount == hulls : "all hulls served: " + servedCount;
    }

    private static void quantum() {
        BlockPos p = new BlockPos(13, 70, -6);
        assert VehicleDriver.quantize(p, 0) == p : "0 = off";
        assert VehicleDriver.quantize(p, 1) == p : "1 = off";
        BlockPos q = VehicleDriver.quantize(p, 4);
        assert q.getX() == 12 && q.getZ() == -4 && q.getY() == 70 : "nearest grid point, Y untouched: " + q;
        assert VehicleDriver.quantize(new BlockPos(14, 0, -5), 4).getX() == 16 : "rounds, not truncates";
        // points within the same cell map to the same target — that is what suppresses drift repaths
        assert VehicleDriver.quantize(new BlockPos(11, 0, 11), 4).equals(VehicleDriver.quantize(new BlockPos(13, 0, 13), 4));
    }

    private PathBudgetSelfCheck() {}
}
