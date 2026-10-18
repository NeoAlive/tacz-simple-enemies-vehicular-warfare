package com.neoalive.tacz_sewv.entity.ai.formation;

import net.minecraft.world.phys.Vec3;


/**
 * The one implementation of formation slot maths. Every slot is a {@code (back, lateral)} offset
 * in the frame — back along {@code -forward}, lateral along right — and nothing else in the mod
 * should be computing those.
 *
 * <p>The world placement is written as {@code (anchor + forward * -back) + right * lateral} in
 * that order on purpose: it is bit-identical to the {@code Vec3} chain the player formation used
 * before this was extracted, which {@code FormationSelfCheck} pins.
 */
public final class FormationGeometry {

    /**
     * SEM's wedge widens 1.25x as fast as it deepens (FormationUtils: 2.5 lateral against 2.0
     * back). Keeping it as a ratio rather than a second spacing is what makes one baseline scale
     * the whole shape without changing its proportions.
     */
    static final double LATERAL_RATIO = 2.5 / 2.0;

    private FormationGeometry() {}

    /**
     * Slot {@code index}'s distance behind the anchor, before placement. {@code count} is only read by
     * {@code RING}, whose angles depend on how many share it.
     */
    public static double back(FormationSpec spec, int index, int count) {
        double s = spec.spacing();
        double l = spec.length();
        return switch (spec.shape()) {
            // Written as -(cos·r) so that -back is exactly cos·r: the idle hold polygon this replaced was
            // cos·r + scramble, and its self-check pins the numbers.
            case RING -> count <= 1 ? 0.0 : -(Math.cos(ringAngle(index, count)) * s * spec.width());
            case COLUMN, ECHELON_RIGHT, ECHELON_LEFT -> s * (1 + index) * l;
            case LINE -> s * (1 + index / Math.max(1, spec.rowSize())) * l;
            default -> index == 0 ? s * l : s * (1 + wedgeRank(index)) * l; // WEDGE
        };
    }

    /** Slot {@code index}'s distance to the right of the anchor, before placement. See {@link #back}. */
    public static double lateral(FormationSpec spec, int index, int count) {
        double s = spec.spacing();
        double w = spec.width();
        return switch (spec.shape()) {
            case RING -> count <= 1 ? 0.0 : Math.sin(ringAngle(index, count)) * s * w;
            case COLUMN -> 0.0;
            case LINE -> {
                int cols = Math.max(1, spec.rowSize());
                yield (index % cols - (cols - 1) / 2.0) * s * w;
            }
            case ECHELON_RIGHT -> s * index * w;
            case ECHELON_LEFT -> -s * index * w;
            default -> index == 0 ? 0.0 : (index % 2 != 0 ? -1 : 1) * s * LATERAL_RATIO * wedgeRank(index) * w;
        };
    }

    /**
     * Slot 0 dead ahead, then evenly round to the right. Spacing is the RING's radius, not a gap between
     * neighbours: a hold polygon is sized by how far out its points sit.
     */
    private static double ringAngle(int index, int count) {
        return index * (Math.PI * 2.0 / count);
    }

    private static int wedgeRank(int index) {
        return (index - 1) / 2 + 1;
    }

    /**
     * World centre of slot {@code index} of {@code count}. {@code count} is only read under
     * {@link FormationSpec.Placement#CENTRED}; prefer {@link #slots} when laying out a whole
     * formation, which pays for the centring once.
     */
    public static Vec3 slot(FormationFrame f, FormationSpec spec, int index, int count) {
        double back = back(spec, index, count);
        double lat = lateral(spec, index, count);
        if (spec.placement() == FormationSpec.Placement.CENTRED) {
            double[] mean = meanOffset(spec, count);
            back -= mean[0];
            lat -= mean[1];
        }
        return place(f, back, lat);
    }

    /** World centres of all {@code count} slots. */
    public static Vec3[] slots(FormationFrame f, FormationSpec spec, int count) {
        Vec3[] out = new Vec3[Math.max(0, count)];
        double mb = 0.0;
        double ml = 0.0;
        if (spec.placement() == FormationSpec.Placement.CENTRED) {
            double[] mean = meanOffset(spec, count);
            mb = mean[0];
            ml = mean[1];
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = place(f, back(spec, i, out.length) - mb, lateral(spec, i, out.length) - ml);
        }
        return out;
    }

    private static double[] meanOffset(FormationSpec spec, int count) {
        if (count <= 0) return new double[] {0.0, 0.0};
        double b = 0.0;
        double l = 0.0;
        for (int i = 0; i < count; i++) {
            b += back(spec, i, count);
            l += lateral(spec, i, count);
        }
        return new double[] {b / count, l / count};
    }

    private static Vec3 place(FormationFrame f, double back, double lateral) {
        double x = f.x() + f.fx() * -back;
        double z = f.z() + f.fz() * -back;
        return new Vec3(x + f.rightX() * lateral, f.y(), z + f.rightZ() * lateral);
    }
}
