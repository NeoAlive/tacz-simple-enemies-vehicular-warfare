package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.phys.Vec3;

/** Package-private access for {@code SuppressionSelfCheck}: the beaten zone's geometry. */
public final class SuppressiveFireProbe {

    private SuppressiveFireProbe() {}

    public static void check() {
        assert SuppressiveFire.spreadRadius(1.0) == SuppressiveFire.MIN_SPREAD : "floored up close";
        assert SuppressiveFire.spreadRadius(10_000.0) == SuppressiveFire.MAX_SPREAD : "capped far out";
        assert SuppressiveFire.spreadRadius(60.0) > SuppressiveFire.spreadRadius(30.0) : "widens with range";

        Vec3 from = new Vec3(0, 70, 0);
        Vec3 center = new Vec3(60, 70, 0);
        double r = SuppressiveFire.spreadRadius(from.distanceTo(center));
        boolean moved = false;
        Vec3 first = SuppressiveFire.beatenZone(from, center, 42, 0);
        for (long t = 0; t < 2000; t += 10) {
            Vec3 p = SuppressiveFire.beatenZone(from, center, 42, t);
            assert p.distanceTo(center) <= r * 1.2 + 1.0e-9 : "stays in the beaten zone";
            assert p.equals(SuppressiveFire.beatenZone(from, center, 42, t)) : "deterministic";
            moved |= p.distanceToSqr(first) > 0.01;
        }
        assert moved : "the fire walks across the spot instead of drilling one block";
        for (long a = -5; a < 50; a++) {
            double h = SuppressiveFire.hash01(a, a * 7);
            assert h >= 0.0 && h < 1.0;
        }
    }
}
