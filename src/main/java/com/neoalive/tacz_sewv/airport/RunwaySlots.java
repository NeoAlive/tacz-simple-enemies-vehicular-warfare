package com.neoalive.tacz_sewv.airport;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.plane.PlaneNav;

/**
 * A cleared strip cut into parking slots: {@code [SLOT][BUFFER][SLOT][BUFFER]...[TAKEOFF BUFFER]}.
 *
 * <p>The problem is deliberately one-dimensional. A runway here is always a straight rectangle, so
 * everything below is a distance <b>along</b> the strip; the width is simply inherited by every
 * slot. There is no packing algorithm, no topology detection and no general parking geometry —
 * segment the long axis, hand out the pieces in order.
 *
 * <p>Slot 0 sits at the landing threshold and the numbers run toward the far end, because that is
 * the order an arriving aircraft can reach them in: it touches down in the reserved takeoff buffer
 * (the one stretch that is guaranteed empty) and backs up into its slot. It also means a departing
 * aircraft always has the rest of the strip in front of it.
 *
 * <p>Slot and buffer lengths are <b>proportions of the runway</b>, not block counts, so one setting
 * suits a 64-block dirt strip and a 400-block airbase. The takeoff buffer is the exception and is
 * interpolated instead: it is an acceleration distance, which is a property of the aircraft rather
 * than of the runway, so a flat percentage would leave a short strip with no room to rotate and
 * waste half of a long one. Capacity is never configured — it falls out of the geometry.
 *
 * <p>Pure: no world access, no entity access, no config lookups. Occupancy lives in
 * {@link RunwayTraffic}, which is the only part that needs a level.
 */
public final class RunwaySlots {

    /** Shortest slot worth generating — an aircraft hull is several blocks long. */
    public static final int MIN_SLOT_LENGTH = 8;
    /** Separation that still reads as separation once block rounding has had its say. */
    public static final int MIN_BUFFER_LENGTH = 2;
    /** Slot boxes are only used to ask "is something parked here", so one hull tall is plenty. */
    private static final double SLOT_HEIGHT = 6.0;

    /**
     * Runway length → baseline takeoff run, interpolated between these points and clamped outside
     * them. Sub-linear on purpose: the distance an aircraft needs to unstick barely changes, so
     * past a point extra runway should become parking rather than more acceleration room.
     */
    private static final double[][] TAKEOFF_CURVE = {
            {64.0, 40.0},
            {128.0, 64.0},
            {256.0, 96.0},
            {512.0, 128.0}
    };

    /** One aircraft's reserved ground. Everything here is precomputed and cached. */
    public record Slot(int index, BlockPos center, BlockPos approach, BlockPos departure,
                       AABB bounds) {}

    private final BlockPos threshold;
    private final float headingDeg;
    private final int length;
    private final int width;
    private final double slotFactor;
    private final double bufferFactor;
    private final double extraFactor;
    private final int slotLength;
    private final int bufferLength;
    private final double baseTakeoffBuffer;
    private final double extraTakeoffBuffer;
    private final double takeoffBuffer;
    private final double usableLength;
    private final BlockPos touchdown;
    private final AABB area;
    private final List<Slot> slots;

    private RunwaySlots(BlockPos threshold, float headingDeg, int length, int width,
                        double slotFactor, double bufferFactor, double extraFactor,
                        int slotLength, int bufferLength, double baseTakeoffBuffer,
                        double extraTakeoffBuffer, double takeoffBuffer, double usableLength,
                        BlockPos touchdown, AABB area, List<Slot> slots) {
        this.threshold = threshold;
        this.headingDeg = headingDeg;
        this.length = length;
        this.width = width;
        this.slotFactor = slotFactor;
        this.bufferFactor = bufferFactor;
        this.extraFactor = extraFactor;
        this.slotLength = slotLength;
        this.bufferLength = bufferLength;
        this.baseTakeoffBuffer = baseTakeoffBuffer;
        this.extraTakeoffBuffer = extraTakeoffBuffer;
        this.takeoffBuffer = takeoffBuffer;
        this.usableLength = usableLength;
        this.touchdown = touchdown;
        this.area = area;
        this.slots = List.copyOf(slots);
    }

    /**
     * Segment a strip. Built once when the runway is cleared and cached with it — an aircraft
     * reads this, it never re-derives it.
     *
     * @param threshold centre of the landing end, at the height an aircraft stands on
     * @param headingDeg compass bearing of the landing direction (threshold → far end)
     * @param slotFactor slot length as a fraction of the runway
     * @param bufferFactor separation as a fraction of the runway
     * @param extraFactor additional takeoff room as a fraction of the runway; only ever adds
     */
    public static RunwaySlots of(BlockPos threshold, float headingDeg, int length, int width,
                                 double slotFactor, double bufferFactor, double extraFactor) {
        Vec3 dir = PlaneNav.directionFromBearingDeg(headingDeg);

        double base = baseTakeoffBuffer(length);
        double extra = length * Math.max(extraFactor, 0.0);
        double takeoff = Math.min(base + extra, length);
        double usable = Math.max(0.0, length - takeoff);

        int slotLength = Math.max(MIN_SLOT_LENGTH, (int) Math.round(length * Math.max(slotFactor, 0.0)));
        int bufferLength = Math.max(MIN_BUFFER_LENGTH,
                (int) Math.round(length * Math.max(bufferFactor, 0.0)));

        // The last aircraft needs no buffer behind it, hence the added one on the numerator.
        int capacity = (int) Math.floor((usable + bufferLength) / (double) (slotLength + bufferLength));
        capacity = Math.max(0, capacity);

        // Touch down at the start of the reserved takeoff run: it is the one stretch of the strip
        // that is guaranteed to be empty, so an arrival can never land on a parked aircraft.
        BlockPos touchdown = point(threshold, dir, usable);
        BlockPos departure = touchdown;

        List<Slot> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            double start = i * (double) (slotLength + bufferLength);
            double end = start + slotLength;
            slots.add(new Slot(i,
                    point(threshold, dir, (start + end) * 0.5),
                    point(threshold, dir, end),
                    departure,
                    box(threshold, dir, start, end, width)));
        }
        return new RunwaySlots(threshold, headingDeg, length, width,
                slotFactor, bufferFactor, extraFactor, slotLength, bufferLength,
                base, extra, takeoff, usable, touchdown,
                box(threshold, dir, 0.0, length, width), slots);
    }

    /** Baseline acceleration room for a strip of this length. Piecewise linear, clamped. */
    public static double baseTakeoffBuffer(int length) {
        double[][] curve = TAKEOFF_CURVE;
        if (length <= curve[0][0]) return curve[0][1];
        for (int i = 1; i < curve.length; i++) {
            if (length <= curve[i][0]) {
                double t = (length - curve[i - 1][0]) / (curve[i][0] - curve[i - 1][0]);
                return Mth.lerp(t, curve[i - 1][1], curve[i][1]);
            }
        }
        return curve[curve.length - 1][1];
    }

    private static Vec3 centre(BlockPos threshold, Vec3 dir, double along) {
        return new Vec3(threshold.getX() + 0.5 + dir.x * along, threshold.getY(),
                threshold.getZ() + 0.5 + dir.z * along);
    }

    private static BlockPos point(BlockPos threshold, Vec3 dir, double along) {
        Vec3 c = centre(threshold, dir, along);
        return BlockPos.containing(c.x, threshold.getY(), c.z);
    }

    private static AABB box(BlockPos threshold, Vec3 dir, double from, double to, int width) {
        Vec3 a = centre(threshold, dir, from);
        Vec3 b = centre(threshold, dir, to);
        // The strip is axis-aligned, so the across-track direction is the other world axis.
        Vec3 across = new Vec3(-dir.z, 0.0, dir.x).scale(width / 2.0);
        double minX = Math.min(a.x, b.x) - Math.abs(across.x);
        double maxX = Math.max(a.x, b.x) + Math.abs(across.x);
        double minZ = Math.min(a.z, b.z) - Math.abs(across.z);
        double maxZ = Math.max(a.z, b.z) + Math.abs(across.z);
        double y = threshold.getY();
        return new AABB(minX, y, minZ, maxX, y + SLOT_HEIGHT, maxZ);
    }

    public BlockPos threshold() { return this.threshold; }
    public float headingDeg() { return this.headingDeg; }
    public int length() { return this.length; }
    public int width() { return this.width; }
    /** The settings this segmentation was cut with — kept so a strip can be saved and rebuilt. */
    public double slotFactor() { return this.slotFactor; }
    public double bufferFactor() { return this.bufferFactor; }
    public double extraFactor() { return this.extraFactor; }
    public int slotLength() { return this.slotLength; }
    public int bufferLength() { return this.bufferLength; }
    public double baseTakeoffBuffer() { return this.baseTakeoffBuffer; }
    public double extraTakeoffBuffer() { return this.extraTakeoffBuffer; }
    public double takeoffBuffer() { return this.takeoffBuffer; }
    public double usableLength() { return this.usableLength; }
    public int capacity() { return this.slots.size(); }
    public List<Slot> slots() { return this.slots; }

    /** The whole strip as one box — the query volume for "what is standing on this runway". */
    public AABB area() { return this.area; }

    /** Where an arrival is aimed: the start of the reserved takeoff run. */
    public BlockPos touchdown() { return this.touchdown; }

    public Slot slot(int index) {
        return index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
    }

    /**
     * The nearest point on the strip's centreline to {@code (x, z)}, clamped to the strip. This is
     * where a landing aircraft is put down: it kills the sideways error of the touchdown in one
     * step instead of steering it out along a rollout it does not have room for.
     */
    public Vec3 nearestCentreline(double x, double z) {
        Vec3 dir = PlaneNav.directionFromBearingDeg(this.headingDeg);
        double along = (x - (this.threshold.getX() + 0.5)) * dir.x
                + (z - (this.threshold.getZ() + 0.5)) * dir.z;
        return centre(this.threshold, dir, Mth.clamp(along, 0.0, this.length - 1.0));
    }
}
