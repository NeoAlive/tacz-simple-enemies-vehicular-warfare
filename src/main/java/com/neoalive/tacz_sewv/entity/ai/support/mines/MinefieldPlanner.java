package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationSpec;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

/**
 * The geometry half of a minefield: which shape, where its slots fall, which way each mine faces and
 * which engineer plants which. Pure, so {@code selfCheckMinefield} runs it headless; whether a slot
 * can actually take a mine is {@link MineSites}' job.
 */
public final class MinefieldPlanner {

    /** Wheel choice. Ordinal is the wire id: append only. */
    public enum ShapeChoice {
        AUTO, LINE, WEDGE, RING, ECHELON_LEFT, ECHELON_RIGHT;

        public static ShapeChoice byId(int id) {
            ShapeChoice[] all = values();
            return id >= 0 && id < all.length ? all[id] : AUTO;
        }

        @Nullable
        FormationShape shape() {
            return switch (this) {
                case AUTO -> null;
                case LINE -> FormationShape.LINE;
                case WEDGE -> FormationShape.WEDGE;
                case RING -> FormationShape.RING;
                case ECHELON_LEFT -> FormationShape.ECHELON_LEFT;
                case ECHELON_RIGHT -> FormationShape.ECHELON_RIGHT;
            };
        }
    }

    /** A single row holds this many before the line folds into a staggered second row. */
    static final int SINGLE_ROW_MAX = 6;

    public record Plan(FormationShape shape, FormationFrame frame, Vec3[] slots, float[] yaws) {}

    private MinefieldPlanner() {}

    /** The shape AUTO picks: a ring when nothing is known, a wedge of claymores, otherwise a line across. */
    static FormationShape autoShape(MineKinds.Placement placement, boolean threatKnown) {
        if (!threatKnown) return FormationShape.RING;
        return placement == MineKinds.Placement.DIRECTIONAL ? FormationShape.WEDGE : FormationShape.LINE;
    }

    /**
     * @param threat          nearest hostile, or null
     * @param fallbackForward heading to use with no threat (the player's look), horizontal
     * @param standoff        how far toward the threat the field is pushed out from the anchor
     */
    public static Plan lay(Vec3 anchor, @Nullable Vec3 threat, Vec3 fallbackForward, MineKinds.Placement placement,
                           ShapeChoice choice, int count, double spacing, double standoff) {
        double fx = fallbackForward.x;
        double fz = fallbackForward.z;
        double push = standoff;
        if (threat != null) {
            fx = threat.x - anchor.x;
            fz = threat.z - anchor.z;
            push = Math.min(standoff, Math.sqrt(fx * fx + fz * fz) * 0.5);
        }
        FormationShape shape = choice.shape() != null ? choice.shape() : autoShape(placement, threat != null);
        if (shape == FormationShape.RING && choice == ShapeChoice.AUTO) push = 0.0;
        FormationFrame dir = FormationFrame.facing(anchor.x, anchor.y, anchor.z, fx, fz);
        FormationFrame frame = FormationFrame.facing(anchor.x + dir.fx() * push, anchor.y,
                anchor.z + dir.fz() * push, dir.fx(), dir.fz());

        Vec3[] slots = slots(frame, shape, count, spacing);
        float[] yaws = new float[slots.length];
        float ahead = yaw(frame.fx(), frame.fz());
        for (int i = 0; i < slots.length; i++) {
            if (shape == FormationShape.RING) {
                yaws[i] = yaw(slots[i].x - frame.x(), slots[i].z - frame.z());
            } else if (placement == MineKinds.Placement.DIRECTIONAL && threat != null) {
                yaws[i] = yaw(threat.x - slots[i].x, threat.z - slots[i].z);
            } else {
                yaws[i] = ahead;
            }
        }
        return new Plan(shape, frame, slots, yaws);
    }

    static Vec3[] slots(FormationFrame frame, FormationShape shape, int count, double spacing) {
        if (count <= 0) return new Vec3[0];
        if (shape == FormationShape.RING) {
            // Spacing is a ring's radius; size it so neighbours sit about one spacing apart.
            double radius = Math.max(spacing * 1.5, spacing * count / (Math.PI * 2.0));
            return FormationGeometry.slots(frame, spec(shape, radius, count), count);
        }
        int rowSize = shape == FormationShape.LINE && count > SINGLE_ROW_MAX ? (count + 1) / 2 : count;
        Vec3[] out = FormationGeometry.slots(frame, spec(shape, spacing, rowSize), count);
        if (shape == FormationShape.LINE && rowSize < count) {
            // Stagger the second row half a gap so nothing drives through both rows in a clean lane.
            double half = spacing * 0.5;
            for (int i = rowSize; i < out.length; i++) {
                out[i] = out[i].add(frame.rightX() * half, 0.0, frame.rightZ() * half);
            }
        }
        return out;
    }

    private static FormationSpec spec(FormationShape shape, double spacing, int rowSize) {
        return new FormationSpec(shape, spacing, 1.0, 1.0, Math.max(1, rowSize), FormationSpec.Placement.CENTRED);
    }

    /** Minecraft yaw (degrees) of a horizontal heading: look vector is {@code (-sin, cos)}. */
    public static float yaw(double dx, double dz) {
        if (dx * dx + dz * dz < 1.0e-9) return 0.0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Which slots each engineer plants, in planting order. Engineers are ranked across the field by
     * where they stand and the slots are cut into contiguous runs in that order, sized by what each
     * carries, so nobody crosses the field to reach their share. Each run is then walked
     * nearest-first. A slot past the total carried is left out.
     *
     * @return per engineer (input order), indices into {@code slots}
     */
    public static List<List<Integer>> split(FormationFrame frame, FormationShape shape, Vec3[] slots,
                                            Vec3[] engineers, int[] carried) {
        int n = engineers.length;
        List<List<Integer>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        if (n == 0 || slots.length == 0) return out;

        Integer[] slotOrder = new Integer[slots.length];
        for (int i = 0; i < slots.length; i++) slotOrder[i] = i;
        Arrays.sort(slotOrder, Comparator.comparingDouble(i -> across(frame, shape, slots[i])));
        Integer[] engOrder = new Integer[n];
        for (int i = 0; i < n; i++) engOrder[i] = i;
        Arrays.sort(engOrder, Comparator.comparingDouble(i -> across(frame, shape, engineers[i])));

        int next = 0;
        for (int rank = 0; rank < n && next < slotOrder.length; rank++) {
            int eng = engOrder[rank];
            int take = Math.min(Math.max(0, carried[eng]), slotOrder.length - next);
            List<Integer> run = new ArrayList<>(take);
            for (int k = 0; k < take; k++) run.add(slotOrder[next++]);
            out.set(eng, nearestFirst(engineers[eng], run, slots));
        }
        return out;
    }

    /** A slot's position along the field: lateral for open shapes, bearing round the centre for a ring. */
    private static double across(FormationFrame frame, FormationShape shape, Vec3 p) {
        if (shape == FormationShape.RING) {
            double a = Math.atan2(frame.lateral(p.x, p.z), frame.ahead(p.x, p.z));
            return a < 0 ? a + Math.PI * 2.0 : a;
        }
        return frame.lateral(p.x, p.z);
    }

    private static List<Integer> nearestFirst(Vec3 from, List<Integer> run, Vec3[] slots) {
        List<Integer> left = new ArrayList<>(run);
        List<Integer> out = new ArrayList<>(run.size());
        double x = from.x;
        double z = from.z;
        while (!left.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < left.size(); i++) {
                Vec3 s = slots[left.get(i)];
                double d = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            int idx = left.remove(best);
            out.add(idx);
            x = slots[idx].x;
            z = slots[idx].z;
        }
        return out;
    }
}
