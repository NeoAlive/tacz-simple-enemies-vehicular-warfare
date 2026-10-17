package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

/**
 * Self-check for {@link RadialInputState}. Run with {@code ./gradlew selfCheckRadial}.
 *
 * <p>Plain {@code main} + {@code assert} — no Forge/Minecraft types on this class's dependencies
 * beyond what the pure radial state already avoids.
 */
public final class RadialInputStateSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        deadzoneIgnoresSmallMotion();
        deltasSelectWedge();
        commitEntersSubmenuAndResetsHot();
        commitFiresLeaf();
        commitInDeadzoneIsNoOp();
        popAtRootCloses();
        popFromSubmenuResetsHot();

        System.out.println("radial input self-check: OK");
    }

    private static List<WedgeEntry> sampleRoot() {
        return List.of(
                new WedgeEntry.CategoryEntry("LAND", "\u2694", 0x6BA84A, List.of(
                        new WedgeEntry.PipelineEntry("Dig In", "\u26F0", "dig_in", 0x6BA84A))),
                new WedgeEntry.CategoryEntry("AIR", "\u2708", 0x4AB8E8, List.of(
                        new WedgeEntry.PipelineEntry("Quick Evac", "\u21E7", "quick_evac", 0x4AB8E8))),
                new WedgeEntry.CategoryEntry("SEA", "\u2693", 0x4A8BC8, List.of()));
    }

    private static void deadzoneIgnoresSmallMotion() {
        RadialInputState state = new RadialInputState(sampleRoot());
        state.feedMouseDelta(5.0, 5.0);
        assert state.hotIndex() == -1 : "inside deadzone must stay -1";
        assert state.commitHot() instanceof RadialInputState.CommitResult.Deadzone
                : "commit in deadzone";
    }

    private static void deltasSelectWedge() {
        RadialInputState state = new RadialInputState(sampleRoot());
        // Straight up (−Y) should land in wedge 0 (LAND) for a 3-slice wheel.
        state.feedMouseDelta(0.0, -40.0);
        assert state.hotIndex() == 0 : "up → LAND, got " + state.hotIndex();

        state = new RadialInputState(sampleRoot());
        // Roughly 120° clockwise from up → wedge 1 (AIR).
        double a = Math.toRadians(120.0);
        state.feedMouseDelta(Math.sin(a) * 40.0, -Math.cos(a) * 40.0);
        assert state.hotIndex() == 1 : "120° → AIR, got " + state.hotIndex();
    }

    private static void commitEntersSubmenuAndResetsHot() {
        RadialInputState state = new RadialInputState(sampleRoot());
        state.feedMouseDelta(0.0, -40.0);
        assert state.hotIndex() == 0;
        RadialInputState.CommitResult result = state.commitHot();
        assert result instanceof RadialInputState.CommitResult.EnteredSubmenu : result;
        assert state.depth() == 2;
        assert state.hotIndex() == -1 : "push must clear hot";
        assert state.accumX() == 0.0 && state.accumY() == 0.0;
        assert state.currentWedges().size() == 1;
        assert state.currentWedges().get(0) instanceof WedgeEntry.PipelineEntry;
    }

    private static void commitFiresLeaf() {
        RadialInputState state = new RadialInputState(sampleRoot());
        state.feedMouseDelta(0.0, -40.0);
        state.commitHot(); // LAND
        state.feedMouseDelta(0.0, -40.0);
        RadialInputState.CommitResult result = state.commitHot();
        assert result instanceof RadialInputState.CommitResult.FiredLeaf leaf
                && leaf.pipelineId().equals("dig_in") : result;
    }

    private static void commitInDeadzoneIsNoOp() {
        RadialInputState state = new RadialInputState(sampleRoot());
        assert state.commitHot() instanceof RadialInputState.CommitResult.Deadzone;
        assert state.depth() == 1;
    }

    private static void popAtRootCloses() {
        RadialInputState state = new RadialInputState(sampleRoot());
        assert state.popMenu() : "root pop must signal close";
        assert state.depth() == 1;
    }

    private static void popFromSubmenuResetsHot() {
        RadialInputState state = new RadialInputState(sampleRoot());
        state.feedMouseDelta(0.0, -40.0);
        state.commitHot();
        state.feedMouseDelta(0.0, -40.0);
        assert state.hotIndex() == 0;
        assert !state.popMenu();
        assert state.depth() == 1;
        assert state.hotIndex() == -1 : "pop must clear hot";
    }
}
