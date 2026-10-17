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

    /** Slot {@code index}'s distance behind the anchor, before placement. */
    public static double back(FormationSpec spec, int index) {
        double s = spec.spacing();
        double l = spec.length();
        return switch (spec.shape()) {
            case COLUMN, ECHELON_RIGHT, ECHELON_LEFT -> s * (1 + index) * l;
            case LINE -> s * (1 + index / Math.max(1, spec.rowSize())) * l;
            default -> index == 0 ? s * l : s * (1 + wedgeRank(index)) * l; // WEDGE
        };
    }

    /** Slot {@code index}'s distance to the right of the anchor, before placement. */
    public static double lateral(FormationSpec spec, int index) {
        double s = spec.spacing();
        double w = spec.width();
        return switch (spec.shape()) {
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

    private static int wedgeRank(int index) {
        return (index - 1) / 2 + 1;
    }

    /**
     * World centre of slot {@code index} of {@code count}. {@code count} is only read under
     * {@link FormationSpec.Placement#CENTRED}; prefer {@link #slots} when laying out a whole
     * formation, which pays for the centring once.
     */
    public static Vec3 slot(FormationFrame f, FormationSpec spec, int index, int count) {
        double back = back(spec, index);
        double lat = lateral(spec, index);
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
            out[i] = place(f, back(spec, i) - mb, lateral(spec, i) - ml);
        }
        return out;
    }

    private static double[] meanOffset(FormationSpec spec, int count) {
        if (count <= 0) return new double[] {0.0, 0.0};
        double b = 0.0;
        double l = 0.0;
        for (int i = 0; i < count; i++) {
            b += back(spec, i);
            l += lateral(spec, i);
        }
        return new double[] {b / count, l / count};
    }

    private static Vec3 place(FormationFrame f, double back, double lateral) {
        double x = f.x() + f.fx() * -back;
        double z = f.z() + f.fz() * -back;
        return new Vec3(x + f.rightX() * lateral, f.y(), z + f.rightZ() * lateral);
    }
}
