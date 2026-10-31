package com.neoalive.tacz_sewv.compat;

/** {@code ./gradlew selfCheckShieldRay}: ray against a tripod shield ellipsoid. */
public final class ShieldRaySelfCheck {

    public static void main(String[] args) {
        // Shield centred 45 up, rx 10, ry 16. A tank 60 blocks out, muzzle 3 up.
        double cx = 0, cy = 45, cz = 0, rx = 10, ry = 16;

        // Straight at the centre: hit.
        assert ShieldRay.hits(60, 3, 0, -60, 42, 0, cx, cy, cz, rx, ry);
        // Clamped at +20 degrees: reaches ~25 at the tripod, under the shell's 29 floor: miss.
        double t20 = Math.tan(Math.toRadians(20));
        assert !ShieldRay.hits(60, 3, 0, -1, t20, 0, cx, cy, cz, rx, ry);
        // Clamped at +30 degrees from 50 out: reaches ~32 in the shell's lower half: hit.
        double t30 = Math.tan(Math.toRadians(30));
        assert ShieldRay.hits(50, 3, 0, -1, t30, 0, cx, cy, cz, rx, ry);
        // Pointing away from the shield: miss, though the backwards line would cross it.
        assert !ShieldRay.hits(60, 3, 0, 60, -42, 0, cx, cy, cz, rx, ry);
        // The y radius counts: 14 above centre is inside ry 16, outside a sphere of rx 10.
        assert ShieldRay.hits(60, 59, 0, -1, 0, 0, cx, cy, cz, rx, ry);
        assert !ShieldRay.hits(60, 62, 0, -1, 0, 0, cx, cy, cz, rx, ry);
        // Inside the shell every direction hits.
        assert ShieldRay.hits(2, 45, 1, 0, -1, 0, cx, cy, cz, rx, ry);
        // Degenerate input never fires.
        assert !ShieldRay.hits(60, 3, 0, 0, 0, 0, cx, cy, cz, rx, ry);
        assert !ShieldRay.hits(60, 3, 0, -1, 0, 0, cx, cy, cz, 0, ry);

        System.out.println("ShieldRaySelfCheck OK");
    }
}
