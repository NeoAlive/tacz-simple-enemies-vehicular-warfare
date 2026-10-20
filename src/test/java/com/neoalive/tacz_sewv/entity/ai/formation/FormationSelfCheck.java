package com.neoalive.tacz_sewv.entity.ai.formation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.map.BattleFieldMarker;

/**
 * Headless check of the shared formation kernel. Run via {@code ./gradlew selfCheckFormation}.
 *
 * <p>The parity block keeps a verbatim copy of the player formation's slot maths as it stood
 * before the kernel was extracted, so "no behaviour change" is checked against the old code and not
 * against itself.
 */
public final class FormationSelfCheck {

    private static final Direction[] CARDINALS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final float[] STRETCHES = {0.5f, 1.0f, 1.7f, 3.0f};
    /**
     * The shapes the player formation had before the kernel — the ones the legacy parity copy covers. RING came
     * with the kernel; its parity is against the old idle polygon, pinned by {@code IdleGroupSupportSelfCheck}.
     */
    private static final FormationShape[] LINEAR_SHAPES = {FormationShape.WEDGE, FormationShape.COLUMN,
            FormationShape.LINE, FormationShape.ECHELON_RIGHT, FormationShape.ECHELON_LEFT};

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        legacyParity();
        centredPlacement();
        noOverlap();
        frameBasis();
        battleLeftParity();
        ring();
        matcherOptimal();
        matcherSticky();
        templateParsing();
        System.out.println("FormationSelfCheck OK");
    }

    /** Every cardinal × shape × index × stretch lands exactly where the pre-kernel maths put it. */
    private static void legacyParity() {
        Vec3 anchor = new Vec3(103.25, 64.0, -47.5);
        for (Direction axis : CARDINALS) {
            for (FormationShape shape : LINEAR_SHAPES) {
                for (int rowSize = 1; rowSize <= 5; rowSize++) {
                    for (float w : STRETCHES) {
                        for (float l : STRETCHES) {
                            for (int i = 0; i < 12; i++) {
                                for (double spacing : new double[] {2.5, 12.0}) {
                                    Vec3 want = legacySlotCenter(anchor, axis, shape, i, rowSize, spacing, w, l);
                                    Vec3 got = VehicleFormation.slotCenter(anchor, axis, shape, i, rowSize, spacing, w, l);
                                    assert want.x == got.x && want.y == got.y && want.z == got.z
                                            : axis + " " + shape + " i=" + i + " row=" + rowSize + " w=" + w
                                            + " l=" + l + " want=" + want + " got=" + got;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** CENTRED slots average to the anchor; TRAIL slots all sit behind it. */
    private static void centredPlacement() {
        FormationFrame f = FormationFrame.facing(10.0, 70.0, 20.0, 0.6, -0.8);
        for (FormationShape shape : FormationShape.values()) {
            for (int n = 1; n <= 12; n++) {
                FormationSpec centred = new FormationSpec(shape, 12.0, 1.0, 1.0, 4, FormationSpec.Placement.CENTRED);
                Vec3[] slots = FormationGeometry.slots(f, centred, n);
                double sx = 0.0;
                double sz = 0.0;
                for (int i = 0; i < n; i++) {
                    sx += slots[i].x;
                    sz += slots[i].z;
                    Vec3 single = FormationGeometry.slot(f, centred, i, n);
                    assert near(single.x, slots[i].x) && near(single.z, slots[i].z) : "slot/slots disagree " + shape;
                }
                assert near(sx / n, f.x()) && near(sz / n, f.z()) : "centred mean off anchor " + shape + " n=" + n;

                FormationSpec trail = new FormationSpec(shape, 12.0, 1.0, 1.0, 4, FormationSpec.Placement.TRAIL);
                for (Vec3 p : FormationGeometry.slots(f, trail, n)) {
                    assert shape == FormationShape.RING || f.ahead(p.x, p.z) < 0.0 : "trail slot ahead of its anchor " + shape;
                }
            }
        }
    }

    /** At the smallest stretch no two slots crowd closer than the player-order overlap floor. */
    private static void noOverlap() {
        FormationFrame f = FormationFrame.facing(0.0, 0.0, 0.0, 1.0, 1.0);
        for (FormationShape shape : LINEAR_SHAPES) {
            for (int rowSize = 1; rowSize <= 6; rowSize++) {
                FormationSpec spec = new FormationSpec(shape, 12.0, 0.5, 0.5, rowSize, FormationSpec.Placement.CENTRED);
                Vec3[] s = FormationGeometry.slots(f, spec, 12);
                double floor = 12.0 * 0.5 * 0.85;
                for (int i = 0; i < s.length; i++) {
                    for (int j = i + 1; j < s.length; j++) {
                        double dx = s[i].x - s[j].x;
                        double dz = s[i].z - s[j].z;
                        assert dx * dx + dz * dz >= floor * floor - 1.0e-9
                                : shape + " row=" + rowSize + " slots " + i + "/" + j + " overlap";
                    }
                }
            }
        }
    }

    /** Forward is unit length, right ⟂ forward, left = -right, and right is east when facing north. */
    private static void frameBasis() {
        FormationFrame north = FormationFrame.ofCardinal(Vec3.ZERO, Direction.NORTH);
        assert north.rightX() == 1.0 && north.rightZ() == 0.0 : "facing north, right must be +X (east)";
        for (double bearing = -Math.PI; bearing <= Math.PI; bearing += 0.37) {
            FormationFrame f = FormationFrame.ofBearing(0.0, 0.0, 0.0, bearing);
            assert near(f.fx() * f.fx() + f.fz() * f.fz(), 1.0);
            assert near(f.fx() * f.rightX() + f.fz() * f.rightZ(), 0.0);
            assert f.leftX() == -f.rightX() && f.leftZ() == -f.rightZ();
            assert near(f.lateral(f.leftX(), f.leftZ()), 1.0) : "lateral measures leftwards";
        }
        FormationFrame degenerate = FormationFrame.facing(0.0, 0.0, 0.0, 0.0, 0.0);
        assert Double.isFinite(degenerate.fx()) && Double.isFinite(degenerate.fz()) : "zero heading must not NaN";
    }

    /** The command layer's (-axisZ, axisX) "left" is exactly the left of a frame facing the enemy. */
    private static void battleLeftParity() {
        double[][] axes = {{1, 0}, {0, 1}, {-0.6, 0.8}, {0.28, -0.96}};
        for (double[] a : axes) {
            assert FormationFrame.battleLeftX(a[0], a[1]) == -a[1];
            assert FormationFrame.battleLeftZ(a[0], a[1]) == a[0];
            assert BattleFieldMarker.leftX(a[0], a[1]) == -a[1];
            assert BattleFieldMarker.leftZ(a[0], a[1]) == a[0];
            FormationFrame f = FormationFrame.ofBattleAxis(0.0, 0.0, 0.0, a[0], a[1]);
            assert near(f.leftX(), -a[1]) && near(f.leftZ(), a[0]);
        }
    }

    /** A RING puts every slot on its radius, evenly spaced, slot 0 dead ahead; a lone member sits on the centre. */
    private static void ring() {
        FormationFrame f = FormationFrame.facing(5.0, 0.0, -3.0, 0.0, 1.0);
        FormationSpec ring = new FormationSpec(FormationShape.RING, 20.0, 1.0, 1.0, 1, FormationSpec.Placement.CENTRED);
        Vec3 solo = FormationGeometry.slots(f, ring, 1)[0];
        assert near(solo.x, 5.0) && near(solo.z, -3.0) : "one member holds the centre";
        for (int n = 2; n <= 8; n++) {
            Vec3[] s = FormationGeometry.slots(f, ring, n);
            assert near(f.ahead(s[0].x, s[0].z), 20.0) && near(f.lateral(s[0].x, s[0].z), 0.0) : "slot 0 ahead";
            double chord = 2.0 * 20.0 * Math.sin(Math.PI / n);
            for (int i = 0; i < n; i++) {
                assert near(Math.hypot(s[i].x - 5.0, s[i].z + 3.0), 20.0) : "n=" + n + " slot " + i + " off radius";
                Vec3 next = s[(i + 1) % n];
                assert near(Math.hypot(next.x - s[i].x, next.z - s[i].z), chord) : "n=" + n + " uneven spacing";
            }
        }
        assert FormationShape.RING.mirror() == FormationShape.RING;
    }

    /** Hungarian equals brute force on every small case; greedy still yields a permutation. */
    private static void matcherOptimal() {
        java.util.Random rnd = new java.util.Random(0x5EEDL);
        for (int trial = 0; trial < 400; trial++) {
            int n = 1 + trial % 7;
            double[] mx = new double[n], mz = new double[n], sx = new double[n], sz = new double[n];
            int[] prev = new int[n];
            for (int i = 0; i < n; i++) {
                mx[i] = rnd.nextDouble() * 100;
                mz[i] = rnd.nextDouble() * 100;
                sx[i] = rnd.nextDouble() * 100;
                sz[i] = rnd.nextDouble() * 100;
                prev[i] = rnd.nextInt(n + 1) - 1;
            }
            double stick = rnd.nextDouble() * 400;
            int[] got = SlotMatcher.match(mx, mz, sx, sz, prev, stick);
            assertPermutation(got);
            double[][] cost = costs(mx, mz, sx, sz, prev, stick);
            double best = bruteForce(cost, new int[n], new boolean[n], 0);
            assert Math.abs(SlotMatcher.total(cost, got) - best) < 1e-6 : "n=" + n + " not optimal";
        }
        int big = SlotMatcher.EXACT_LIMIT + 5;
        double[] bx = new double[big], bz = new double[big];
        for (int i = 0; i < big; i++) {
            bx[i] = i * 3.0;
            bz[i] = -i;
        }
        assertPermutation(SlotMatcher.match(bx, bz, bz, bx, null, 0.0));
    }

    /** With stickiness, a small drift keeps everyone in place; without it, crossing paths are never chosen. */
    private static void matcherSticky() {
        double[] sx = {0, 16, 32};
        double[] sz = {0, 0, 0};
        // Members drifted halfway toward the next slot over.
        double[] mx = {9, 25, 41};
        double[] mz = {0, 0, 0};
        int[] held = SlotMatcher.match(mx, mz, sx, sz, new int[] {0, 1, 2}, 16 * 16);
        assert held[0] == 0 && held[1] == 1 && held[2] == 2 : "drift alone does not reshuffle";
        int[] free = SlotMatcher.match(new double[] {30, 2}, new double[] {0, 0},
                new double[] {0, 32}, new double[] {0, 0}, null, 0.0);
        assert free[0] == 1 && free[1] == 0 : "each takes the near slot — no crossing";
    }

    private static void templateParsing() {
        FormationTemplates shipped = FormationTemplates.parse(java.util.Map.of(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tacz_sewv", "formations"), shippedTemplates()));
        FormationTemplates.Template left = shipped.lookup(null, "maneuver_left");
        FormationTemplates.Template right = shipped.lookup(null, "maneuver_right");
        assert left.equals(right.mirror()) : "flank wings must be mirror images: " + left + " vs " + right;
        for (FormationShape shape : FormationShape.values()) {
            assert shape.mirror().mirror() == shape : shape + " mirror is not an involution";
        }
        assert shipped.lookup("pursuit", "maneuver").placement() == FormationSpec.Placement.TRAIL
                : "play-specific row wins";
        assert shipped.lookup("hold_defend", "maneuver").placement() == FormationSpec.Placement.CENTRED
                : "other plays use the role row";

        FormationTemplates bad = FormationTemplates.parse(java.util.Map.of(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tacz_sewv", "bad"),
                com.google.gson.JsonParser.parseString(
                        "{ \"hold\": { \"shape\": \"blob\" }, \"withdraw\": { \"spacing\": 0.1 } }")));
        assert !bad.defines("hold") : "unknown shape: row ignored";
        assert bad.lookup(null, "withdraw").spacing() >= 4.0 : "spacing clamped";
        assert FormationTemplates.fallback().lookup("x", "anything").shape() == FormationShape.LINE
                : "fallback is the firing line";
    }

    private static com.google.gson.JsonElement shippedTemplates() {
        try (var in = FormationSelfCheck.class.getResourceAsStream("/data/tacz_sewv/sewv/formations/formations.json")) {
            if (in == null) throw new AssertionError("shipped formations.json is not on the classpath");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static double[][] costs(double[] mx, double[] mz, double[] sx, double[] sz, int[] prev, double stick) {
        int n = mx.length;
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double dx = mx[i] - sx[j];
                double dz = mz[i] - sz[j];
                c[i][j] = dx * dx + dz * dz + (prev[i] >= 0 && prev[i] != j ? stick : 0.0);
            }
        }
        return c;
    }

    private static double bruteForce(double[][] cost, int[] perm, boolean[] used, int i) {
        if (i == cost.length) return SlotMatcher.total(cost, perm);
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < cost.length; j++) {
            if (used[j]) continue;
            used[j] = true;
            perm[i] = j;
            best = Math.min(best, bruteForce(cost, perm, used, i + 1));
            used[j] = false;
        }
        return best;
    }

    private static void assertPermutation(int[] slotOf) {
        boolean[] seen = new boolean[slotOf.length];
        for (int s : slotOf) {
            assert s >= 0 && s < slotOf.length && !seen[s] : "not a permutation";
            seen[s] = true;
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    // ---- verbatim pre-kernel VehicleFormation.slotCenter -------------------------------------------

    private static Vec3 legacySlotCenter(Vec3 anchor, Direction axis, FormationShape shape, int index,
                                         int rowSize, double baselineSpacing,
                                         float widthStretch, float lengthStretch) {
        double spacing = baselineSpacing;
        double w = Math.max(0.5f, Math.min(3.0f, widthStretch));
        double l = Math.max(0.5f, Math.min(3.0f, lengthStretch));
        Vec3 forward = new Vec3(axis.getStepX(), 0.0, axis.getStepZ());
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);
        double back;
        double lateral;
        switch (shape) {
            case COLUMN -> {
                back = spacing * (1 + index) * l;
                lateral = 0.0;
            }
            case LINE -> {
                int cols = Math.max(1, rowSize);
                int row = index / cols;
                int col = index % cols;
                back = spacing * (1 + row) * l;
                lateral = (col - (cols - 1) / 2.0) * spacing * w;
            }
            case ECHELON_RIGHT -> {
                back = spacing * (1 + index) * l;
                lateral = spacing * index * w;
            }
            case ECHELON_LEFT -> {
                back = spacing * (1 + index) * l;
                lateral = -spacing * index * w;
            }
            default -> {
                if (index == 0) {
                    back = spacing * l;
                    lateral = 0.0;
                } else {
                    int rank = (index - 1) / 2 + 1;
                    back = spacing * (1 + rank) * l;
                    lateral = (index % 2 != 0 ? -1 : 1) * spacing * (2.5 / 2.0) * rank * w;
                }
            }
        }
        return anchor.add(forward.scale(-back)).add(perp.scale(lateral));
    }
}
