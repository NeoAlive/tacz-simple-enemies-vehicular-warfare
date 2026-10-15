package com.neoalive.tacz_sewv.entity.ai.sensor;

import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueRegistry.TriggerKind;

/**
 * Self-check for gunfire cue classification, merge order, dedupe, the deterministic roll, and radii.
 * Run via {@code ./gradlew selfCheckCues}.
 */
public final class AwarenessCuesSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        classifyPaths();
        classifyDistant();
        mergeOrdering();
        deterministicRoll();
        cannonDedupe();
        taczDedupeHard();
        hearRadii();

        System.out.println("awareness cues self-check: OK");
    }

    private static void classifyPaths() {
        assert AwarenessCueSounds.isCannon("bl_132_fire_3p_01");
        assert AwarenessCueSounds.isCannon("mortar_fire");
        assert AwarenessCueSounds.isCannon("vehicle_strike");
        assert AwarenessCueSounds.isCannon("t_90a_far");
        assert !AwarenessCueSounds.isCannon("explosion_far");
        assert !AwarenessCueSounds.isCannon("ak_47_far");
        assert !AwarenessCueSounds.isCannon("track_vehicle_step");
    }

    private static void classifyDistant() {
        assert !AwarenessCueSounds.isDistantCannonPath("explosion_far", null);
        assert !AwarenessCueSounds.isDistantCannonPath("ak_47_far", null);
        assert AwarenessCueSounds.isDistantCannonPath("t_90a_far", null);
        assert AwarenessCueSounds.isDistantCannonPath("t_90a_fire_3p", null);
    }

    private static void mergeOrdering() {
        assert AwarenessCues.compareSpots(0.85, 50, 100, 0.55, 20, 100) > 0 : "cannon beats tacz fire";
        assert AwarenessCues.compareSpots(1.0, 80, 100, 0.85, 40, 100) > 0 : "foliage contact beats cannon";
        assert AwarenessCues.compareSpots(0.55, 10, 100, 0.55, 20, 100) > 0 : "closer tie-break";
        assert AwarenessCues.compareSpots(0.55, 10, 200, 0.55, 10, 100) > 0 : "fresher tie-break";
    }

    private static void deterministicRoll() {
        boolean a = AwarenessCues.rollTrigger(42, BlockPos.ZERO.asLong(), TriggerKind.TACZ_FIRE, 0.45);
        boolean b = AwarenessCues.rollTrigger(42, BlockPos.ZERO.asLong(), TriggerKind.TACZ_FIRE, 0.45);
        assert a == b : "roll must be stable";
        assert AwarenessCues.rollTrigger(1, BlockPos.ZERO.asLong(), TriggerKind.VEHICLE_CANNON, 1.0);
        assert !AwarenessCues.rollTrigger(1, BlockPos.ZERO.asLong(), TriggerKind.TACZ_FIRE, 0.0);
    }

    private static void cannonDedupe() {
        AwarenessCueRegistry reg = AwarenessCueRegistry.forTest();
        BlockPos pos = new BlockPos(8, 64, 8);
        long now = 1000L;
        assert !reg.wouldDedupe(pos, TriggerKind.VEHICLE_CANNON, now);
        reg.registerForTest(pos, TriggerKind.VEHICLE_CANNON, now);
        assert reg.wouldDedupe(pos, TriggerKind.VEHICLE_CANNON, now + 5);
        assert !reg.wouldDedupe(pos, TriggerKind.VEHICLE_CANNON, now + 10);
    }

    /** TaCZ fire: 120-tick / 16-block-cell throttle. */
    private static void taczDedupeHard() {
        AwarenessCueRegistry reg = AwarenessCueRegistry.forTest();
        BlockPos pos = new BlockPos(8, 64, 8);
        long now = 2000L;
        assert !reg.wouldDedupe(pos, TriggerKind.TACZ_FIRE, now);
        reg.registerForTest(pos, TriggerKind.TACZ_FIRE, now);
        assert reg.wouldDedupe(pos, TriggerKind.TACZ_FIRE, now + 119) : "tacz must dedupe until 120 ticks";
        assert !reg.wouldDedupe(pos, TriggerKind.TACZ_FIRE, now + 120) : "tacz may re-register at 120 ticks";
        // Same 16-block cell: offset still inside [0,16)
        assert reg.wouldDedupe(new BlockPos(15, 64, 8), TriggerKind.TACZ_FIRE, now + 10)
                : "tacz 16-block cell should cover nearby fire";
    }

    private static void hearRadii() {
        assert TriggerKind.VEHICLE_CANNON.hearRadius == 128;
        assert TriggerKind.TACZ_FIRE.hearRadius == 64;
        assert TriggerKind.maxHearRadius() == 128;
        assert AwarenessCues.MAX_SOUND_HEAR_RADIUS == 128;
    }

    private AwarenessCuesSelfCheck() {}
}
