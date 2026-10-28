package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/**
 * Headless check of the player formation's heading: a free yaw on a cardinal lays exactly the slots the axis
 * does, and the heading named back to the player is the one the units face. Run via
 * {@code ./gradlew selfCheckPlayerFormation}.
 */
public final class PlayerFormationSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        yawMatchesCardinals();
        cardinalYawLaysLegacySlots();
        diagonalIsBetweenItsCardinals();
        headingNames();
        heliFollowRing();
        System.out.println("PlayerFormationSelfCheck OK");
    }

    /** FOLLOW_ME helicopter ring: neighbours exactly one separation apart, slot 0 north, a lone follower stays put. */
    private static void heliFollowRing() {
        double sep = 14.0;
        assert HeliFollowSlots.ringRadius(1, sep) == 0.0 : "a lone follower has no ring";
        double[] lone = HeliFollowSlots.offset(0, 1, sep);
        assert lone[0] == 0.0 && lone[1] == 0.0 : "a lone follower must hold over the commander";
        for (int n = 2; n <= 8; n++) {
            for (int i = 0; i < n; i++) {
                double[] a = HeliFollowSlots.offset(i, n, sep);
                double[] b = HeliFollowSlots.offset((i + 1) % n, n, sep);
                assert near(Math.hypot(a[0] - b[0], a[1] - b[1]), sep) : n + " followers: neighbours not " + sep + " apart";
            }
        }
        double[] north = HeliFollowSlots.offset(0, 4, sep);
        assert near(north[0], 0.0) && north[1] < 0.0 : "slot 0 must be due north (-Z)";
    }

    private static final Object[][] CARDINALS = {
            {0.0f, Direction.SOUTH}, {90.0f, Direction.WEST}, {180.0f, Direction.NORTH}, {-90.0f, Direction.EAST}};

    private static void yawMatchesCardinals() {
        for (Object[] c : CARDINALS) {
            Vec3 f = VehicleFormation.forwardOfYaw((Float) c[0]);
            Vec3 axis = VehicleFormation.forward((Direction) c[1]);
            assert near(f.x, axis.x) && near(f.z, axis.z) : c[0] + "° should face " + c[1];
            assert Direction.fromYRot((Float) c[0]) == c[1] : "wheel's axis for " + c[0] + "°";
        }
    }

    private static void cardinalYawLaysLegacySlots() {
        Vec3 anchor = new Vec3(10.5, 64.0, -3.25);
        for (Object[] c : CARDINALS) {
            for (FormationShape shape : new FormationShape[] {FormationShape.WEDGE, FormationShape.COLUMN,
                    FormationShape.LINE, FormationShape.ECHELON_LEFT, FormationShape.ECHELON_RIGHT}) {
                VehicleFormation.Layout axisOnly = layout((Direction) c[1], Float.NaN, shape);
                VehicleFormation.Layout withYaw = layout((Direction) c[1], (Float) c[0], shape);
                for (int i = 0; i < 9; i++) {
                    Vec3 a = VehicleFormation.slotCenter(axisOnly, anchor, i);
                    Vec3 b = VehicleFormation.slotCenter(withYaw, anchor, i);
                    assert near(a.x, b.x) && near(a.z, b.z) : shape + " " + c[1] + " slot " + i + ": " + a + " vs " + b;
                }
            }
        }
    }

    /** A 45° column runs exactly half-way between the two cardinals either side of it. */
    private static void diagonalIsBetweenItsCardinals() {
        Vec3 anchor = Vec3.ZERO;
        VehicleFormation.Layout ne = layout(Direction.NORTH, -135.0f, FormationShape.COLUMN);
        Vec3 slot0 = VehicleFormation.slotCenter(ne, anchor, 0);
        // Facing north-east the column trails south-west: -X, +Z, equal parts.
        assert slot0.x < 0 && slot0.z > 0 && near(-slot0.x, slot0.z) : "NE column trails SW: " + slot0;
        assert near(Math.hypot(slot0.x, slot0.z), 12.0) : "one spacing back";
    }

    private static void headingNames() {
        String[] expected = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
        for (int o = 0; o < 8; o++) {
            float yaw = o * 45.0f;
            String key = PacketVehicleFormation.headingKey(Direction.fromYRot(yaw), yaw);
            assert key.endsWith("." + expected[o]) : yaw + "° named " + key;
            Vec3 f = VehicleFormation.forwardOfYaw(yaw);
            String dir = (f.z < -0.5 ? "north" : f.z > 0.5 ? "south" : "") + (f.x > 0.5 ? "east" : f.x < -0.5 ? "west" : "");
            assert dir.equals(expected[o]) : yaw + "° faces " + dir + " but is named " + expected[o];
        }
        assert PacketVehicleFormation.headingKey(Direction.EAST, Float.NaN).endsWith(".east") : "no yaw: the axis";
    }

    private static VehicleFormation.Layout layout(Direction axis, float yaw, FormationShape shape) {
        return new VehicleFormation.Layout(axis, yaw, shape, 3, 1.0f, 1.0f, FormationAnchorMode.PLAYER, null,
                FormationComposition.SPACING_VEHICLE, FormationComposition.Kind.GROUND, 0L);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    private PlayerFormationSelfCheck() {}
}
