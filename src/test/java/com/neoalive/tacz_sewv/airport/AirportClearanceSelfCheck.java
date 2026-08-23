package com.neoalive.tacz_sewv.airport;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Headless geometry checks for {@link AirportClearance} and {@link RunwaySlots}. Run with
 * {@code ./gradlew selfCheckAirport}.
 */
public final class AirportClearanceSelfCheck {

    private static final double SLOT_FACTOR = 0.10;
    private static final double BUFFER_FACTOR = 0.03;
    private static final double EXTRA_FACTOR = 0.0;
    private static final AirportClearance.Rules RULES =
            new AirportClearance.Rules(3.0, 64, 65536, SLOT_FACTOR, BUFFER_FACTOR, EXTRA_FACTOR);
    private static final int RUNWAY_Y = 64;

    public static void main(String[] args) {
        aspectRejectsSquare();
        lengthRejectsShort();
        areaRejectsHuge();
        orientations();
        reciprocal();
        headingRoundTrip();
        takeoffCurve();
        segmentation();
        capacityFits();
        centrelineClamps();
        System.out.println("AirportClearanceSelfCheck OK");
    }

    private static void aspectRejectsSquare() {
        AirportClearance.Result r = AirportClearance.evaluate(
                new BlockPos(0, RUNWAY_Y, 0), new BlockPos(19, RUNWAY_Y, 19), RUNWAY_Y, RULES);
        assert r.status() == AirportClearance.Status.ASPECT : r.status();
    }

    private static void lengthRejectsShort() {
        // 30×10 is runway-shaped (3:1) but shorter than 64.
        AirportClearance.Result r = AirportClearance.evaluate(
                new BlockPos(0, RUNWAY_Y, 0), new BlockPos(29, RUNWAY_Y, 9), RUNWAY_Y, RULES);
        assert r.status() == AirportClearance.Status.TOO_SHORT : r.status();
        assert r.length() == 30 : r.length();
    }

    private static void areaRejectsHuge() {
        AirportClearance.Result r = AirportClearance.evaluate(
                new BlockPos(0, RUNWAY_Y, 0), new BlockPos(999, RUNWAY_Y, 999), RUNWAY_Y, RULES);
        assert r.status() == AirportClearance.Status.TOO_LARGE : r.status();
    }

    /** Long-X / long-Z, both corner orders — heading and touchdown must agree. */
    private static void orientations() {
        checkStrip(new BlockPos(0, RUNWAY_Y, 0), new BlockPos(63, RUNWAY_Y, 19), true);
        checkStrip(new BlockPos(63, RUNWAY_Y, 19), new BlockPos(0, RUNWAY_Y, 0), true);
        checkStrip(new BlockPos(0, RUNWAY_Y, 0), new BlockPos(19, RUNWAY_Y, 63), false);
        checkStrip(new BlockPos(19, RUNWAY_Y, 63), new BlockPos(0, RUNWAY_Y, 0), false);
    }

    private static void checkStrip(BlockPos a, BlockPos b, boolean longIsX) {
        AirportClearance.Result r = AirportClearance.evaluate(a, b, RUNWAY_Y, RULES);
        assert r.status() == AirportClearance.Status.OK : r.status();
        assert r.length() == 64 && r.width() == 20 : r.length() + "x" + r.width();
        assert r.touchdown() != null && r.slots() != null;

        float expectedHdg = longIsX ? 90.0F : 0.0F;
        assert Math.abs(r.headingDeg() - expectedHdg) < 1.0e-3 : r.headingDeg();

        // The aim point is the start of the reserved takeoff run: an arrival flies over every
        // parking slot and puts its wheels down on the one stretch nothing may park in.
        int along = (int) r.slots().usableLength();
        if (longIsX) {
            assert r.touchdown().getX() == along : r.touchdown();
            assert r.touchdown().getZ() == 9 : r.touchdown();
            assert r.threshold().getX() == 0 : r.threshold();
        } else {
            assert r.touchdown().getZ() == along : r.touchdown();
            assert r.touchdown().getX() == 9 : r.touchdown();
            assert r.threshold().getZ() == 0 : r.threshold();
        }
        assert r.touchdown().getY() == RUNWAY_Y + 1 : r.touchdown();
    }

    /**
     * The other end of the same strip: opposed heading, thresholds swapped, and the two touchdown
     * points symmetric about the centre — landing the reciprocal way must not shift the runway.
     */
    private static void reciprocal() {
        BlockPos a = new BlockPos(0, RUNWAY_Y, 0);
        BlockPos b = new BlockPos(63, RUNWAY_Y, 19);
        AirportClearance.Result fwd = AirportClearance.evaluate(a, b, RUNWAY_Y, RULES, false);
        AirportClearance.Result rev = AirportClearance.evaluate(a, b, RUNWAY_Y, RULES, true);
        assert rev.status() == AirportClearance.Status.OK : rev.status();
        assert Math.abs(fwd.headingDeg() - 90.0F) < 1.0e-3 : fwd.headingDeg();
        assert Math.abs(rev.headingDeg() + 90.0F) < 1.0e-3 : rev.headingDeg();
        assert rev.threshold().getX() == 63 : rev.threshold();
        assert rev.touchdown().getZ() == fwd.touchdown().getZ() : rev.touchdown();
        assert fwd.touchdown().getX() + rev.touchdown().getX() == 63
                : fwd.touchdown() + " / " + rev.touchdown();
    }

    /** Same reconstruction DrivePlaneGoal.approachAxis uses from TAG_APPROACH_YAW. */
    private static void headingRoundTrip() {
        for (float hdg : new float[] {0.0F, 90.0F}) {
            double rad = Math.toRadians(hdg);
            double x = Math.sin(rad);
            double z = Math.cos(rad);
            double back = Math.toDegrees(Math.atan2(x, z));
            assert Math.abs(back - hdg) < 1.0e-6 : hdg + " -> " + back;
        }
    }

    /**
     * The takeoff run grows with the strip but never as fast as it — the whole reason it is
     * interpolated rather than a percentage. A flat fraction would give a 512-block airbase 200
     * blocks of runway it cannot use for anything.
     */
    private static void takeoffCurve() {
        double prev = -1.0;
        for (int len : new int[] {32, 64, 96, 128, 256, 512, 1024}) {
            double base = RunwaySlots.baseTakeoffBuffer(len);
            assert base >= prev : len + " -> " + base;
            assert base > 0.0 : len + " -> " + base;
            prev = base;
        }
        assert RunwaySlots.baseTakeoffBuffer(32) == RunwaySlots.baseTakeoffBuffer(64)
                : "below the first reference point the curve must clamp, not extrapolate";
        assert RunwaySlots.baseTakeoffBuffer(512) == RunwaySlots.baseTakeoffBuffer(4096)
                : "above the last reference point the curve must clamp";
        double shortStrip = RunwaySlots.baseTakeoffBuffer(64) / 64.0;
        double longStrip = RunwaySlots.baseTakeoffBuffer(512) / 512.0;
        assert longStrip < shortStrip : shortStrip + " vs " + longStrip;
    }

    /** Slots in order, separated by the buffer, and all of them clear of the takeoff run. */
    private static void segmentation() {
        RunwaySlots slots = RunwaySlots.of(new BlockPos(0, RUNWAY_Y + 1, 9), 90.0F, 200, 20,
                SLOT_FACTOR, BUFFER_FACTOR, EXTRA_FACTOR);
        assert slots.capacity() > 1 : slots.capacity();
        assert slots.slots().size() == slots.capacity();
        assert slots.takeoffBuffer() >= RunwaySlots.baseTakeoffBuffer(slots.length());
        assert Math.abs(slots.usableLength() + slots.takeoffBuffer() - slots.length()) < 1.0e-6;

        List<RunwaySlots.Slot> list = slots.slots();
        for (int i = 0; i < list.size(); i++) {
            RunwaySlots.Slot s = list.get(i);
            assert s.index() == i : s.index();
            assert s.bounds().contains(s.center().getX() + 0.5, s.center().getY() + 0.5,
                    s.center().getZ() + 0.5) : "slot " + i + " centre outside its own bounds";
            // Slot 0 is at the threshold and they run toward the takeoff end.
            assert s.center().getX() > 0 : s.center();
            if (i > 0) {
                RunwaySlots.Slot prev = list.get(i - 1);
                assert s.center().getX() > prev.center().getX() : "slots out of order at " + i;
                double gap = s.bounds().minX - prev.bounds().maxX;
                assert gap >= slots.bufferLength() - 1.0e-6 : "slot " + i + " gap " + gap;
            }
            // Nothing may be parked in the reserved acceleration area.
            assert s.bounds().maxX <= slots.usableLength() + 1.0e-6
                    : "slot " + i + " runs into the takeoff buffer";
        }
        // Every slot's departure point is the start of that reserved area — the touchdown too.
        assert list.get(0).departure().equals(slots.touchdown());
    }

    /** A strip too short for even one aircraft has a capacity of zero rather than a bad slot. */
    private static void capacityFits() {
        RunwaySlots tiny = RunwaySlots.of(new BlockPos(0, RUNWAY_Y + 1, 5), 90.0F, 64, 12,
                SLOT_FACTOR, BUFFER_FACTOR, EXTRA_FACTOR);
        assert tiny.capacity() >= 0;
        assert tiny.slotLength() >= RunwaySlots.MIN_SLOT_LENGTH : tiny.slotLength();
        assert tiny.bufferLength() >= RunwaySlots.MIN_BUFFER_LENGTH : tiny.bufferLength();
        assert tiny.capacity() * (tiny.slotLength() + tiny.bufferLength()) - tiny.bufferLength()
                <= tiny.usableLength() + 1.0e-6 : "capacity overruns the usable length";

        // All of it reserved for takeoff: no slots, and still a valid strip to land on.
        RunwaySlots none = RunwaySlots.of(new BlockPos(0, RUNWAY_Y + 1, 5), 90.0F, 64, 12,
                SLOT_FACTOR, BUFFER_FACTOR, 1.0);
        assert none.capacity() == 0 : none.capacity();
        assert none.touchdown() != null;
        // However the numbers are set, the reserved area cannot be longer than the runway.
        assert none.takeoffBuffer() <= none.length() : none.takeoffBuffer();
    }

    /** The touchdown placement: nearest point on the centreline, never off the end of the strip. */
    private static void centrelineClamps() {
        RunwaySlots slots = RunwaySlots.of(new BlockPos(0, RUNWAY_Y + 1, 9), 90.0F, 200, 20,
                SLOT_FACTOR, BUFFER_FACTOR, EXTRA_FACTOR);
        assert Math.abs(slots.nearestCentreline(50.0, 30.0).z - 9.5) < 1.0e-6
                : "must land on the centreline whatever the cross-track error was";
        assert Math.abs(slots.nearestCentreline(50.0, 30.0).x - 50.0) < 1.0e-6;
        assert Math.abs(slots.nearestCentreline(-40.0, 9.5).x - 0.5) < 1.0e-6 : "short: clamp on";
        assert Math.abs(slots.nearestCentreline(400.0, 9.5).x - 199.5) < 1.0e-6 : "long: clamp on";
    }

    private AirportClearanceSelfCheck() {}
}
