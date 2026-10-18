package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.phys.Vec3;

/**
 * Headless geometry checks for the idle hold ring (the march column is {@code MarchSelfCheck}). Run with {@code ./gradlew selfCheckIdle}.
 */
public final class IdleGroupSupportSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        singletonIsCenterOnly();
        polygonAngles();
        radiusFormulaClamped();

        System.out.println("idle-group geometry self-check: OK");
    }

    private static void singletonIsCenterOnly() {
        Vec3 off = IdleGroupSupport.holdSlotOffset(0, 1, 15.0, 15.0, 40.0, 2.0, -3.0);
        assertClose(2.0, off.x, "N=1 scramble x");
        assertClose(-3.0, off.z, "N=1 scramble z");
        assert Math.abs(off.y) < 1.0E-9 : "N=1 y must be 0";
    }

    private static void polygonAngles() {
        // N=4 square: indices at 0, π/2, π, 3π/2
        for (int n = 2; n <= 5; n++) {
            double radius = clamp(15.0 * (1.0 + 0.2 * (n - 1)), 15.0, 40.0);
            for (int i = 0; i < n; i++) {
                Vec3 off = IdleGroupSupport.holdSlotOffset(i, n, 15.0, 15.0, 40.0, 0.0, 0.0);
                double expectedAngle = i * (Math.PI * 2.0 / n);
                double expectedX = Math.cos(expectedAngle) * radius;
                double expectedZ = Math.sin(expectedAngle) * radius;
                assertClose(expectedX, off.x, "N=" + n + " i=" + i + " x");
                assertClose(expectedZ, off.z, "N=" + n + " i=" + i + " z");
            }
        }
    }

    private static void radiusFormulaClamped() {
        // N=5 → 15 * (1 + 0.8) = 27, within [15,40]
        Vec3 a = IdleGroupSupport.holdSlotOffset(0, 5, 15.0, 15.0, 40.0, 0.0, 0.0);
        double r5 = Math.hypot(a.x, a.z);
        assertClose(27.0, r5, "N=5 radius");

        // Base 40 with N=5 would be 72 → clamp to 40
        Vec3 b = IdleGroupSupport.holdSlotOffset(0, 5, 40.0, 15.0, 40.0, 0.0, 0.0);
        assertClose(40.0, Math.hypot(b.x, b.z), "N=5 clamped max");

        // Base 5 with N=2 → 5 * 1.2 = 6 → clamp to 15
        Vec3 c = IdleGroupSupport.holdSlotOffset(0, 2, 5.0, 15.0, 40.0, 0.0, 0.0);
        assertClose(15.0, Math.hypot(c.x, c.z), "N=2 clamped min");
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void assertClose(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1.0E-6
                : what + ": expected " + expected + " but was " + actual;
    }

    private IdleGroupSupportSelfCheck() {}
}
