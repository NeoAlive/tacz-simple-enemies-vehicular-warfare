package com.neoalive.tacz_sewv.procedural.events;

/** Pins {@link EventBand}: the event ring never reaches past what the server simulates. */
public final class EventSpotsSelfCheck {

    private EventSpotsSelfCheck() {}

    public static void main(String[] args) {
        // 8 view, 12 simulation: 8 * 16 - 16.
        int r = EventBand.simulatedRadius(8, 12);
        check(r == 112, "radius 8/12 is 112, got " + r);
        check(EventBand.simulatedRadius(1, 1) == 32, "tiny view keeps a usable radius");

        // A base ring inside the radius is left alone.
        EventBand base = EventBand.clamp(70, 100, 112);
        check(base.min() == 70 && base.max() == 100, "inside ring unchanged: " + base);

        // far_combat at 2.5x (175-300) is pulled inside the radius with a real width.
        EventBand far = EventBand.clamp(175, 300, 112);
        check(far.max() == 112, "far edge capped: " + far);
        check(far.min() == (int) (112 * EventBand.NEAR_SHARE), "near edge pulled in: " + far);
        check(far.max() - far.min() >= EventBand.MIN_WIDTH, "ring keeps width: " + far);

        // A near edge just under the cap still leaves the minimum width.
        EventBand tight = EventBand.clamp(105, 300, 112);
        check(tight.max() - tight.min() >= EventBand.MIN_WIDTH, "tight ring keeps width: " + tight);

        // Swapped input and no radius are tolerated.
        EventBand swapped = EventBand.clamp(120, 70, 0);
        check(swapped.min() == 70 && swapped.max() == 120, "swapped input sorted: " + swapped);

        for (int view = 2; view <= 32; view++) {
            for (int sim = 5; sim <= 32; sim++) {
                int rad = EventBand.simulatedRadius(view, sim);
                EventBand b = EventBand.clamp(250, 425, rad);
                check(b.max() <= rad && b.min() >= 0 && b.min() < b.max(), "view " + view + " sim " + sim + ": " + b);
            }
        }
        // A wide event laid across the line of sight keeps both ends inside the radius.
        for (int rad : new int[] {112, 176, 240}) {
            for (int reach : new int[] {54, 60, 70, 88}) {
                EventBand w = EventBand.clamp(250, 425, rad, reach);
                double end = Math.sqrt((double) w.max() * w.max() + (double) reach * reach);
                check(end <= rad + 1 || w.max() == EventBand.MIN_WIDTH * 2, "reach " + reach + " radius " + rad + ": " + w);
                check(w.max() - w.min() >= EventBand.MIN_WIDTH, "wide ring keeps width: " + w);
            }
        }
        check(EventBand.clamp(175, 300, 112, 88).max() == 69, "large_combat at 8 chunks centres within 69");
        check(EventBand.clamp(175, 300, 112, 0).equals(EventBand.clamp(175, 300, 112)), "zero reach is the plain clamp");
        System.out.println("EventSpotsSelfCheck OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
