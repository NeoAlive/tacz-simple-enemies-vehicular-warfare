package com.neoalive.tacz_sewv.entity.ai.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * Self-check for awareness cue classification, merge order, dedupe, and chance rolls.
 * Run via {@code ./gradlew selfCheckCues}.
 */
public final class AwarenessCuesSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        classifyPaths();
        classifyDistant();
        classifyIds();
        mergeOrdering();
        deterministicRoll();
        dedupe();

        System.out.println("awareness cues self-check: OK");
    }

    private static void classifyPaths() {
        assert AwarenessCueSounds.isEngine("track_vehicle_step");
        assert AwarenessCueSounds.isEngine("vehicle_swim");
        assert !AwarenessCueSounds.isEngine("bl_132_fire_3p_01");
        assert AwarenessCueSounds.isCannon("bl_132_fire_3p_01");
        assert AwarenessCueSounds.isCannon("mortar_fire");
        assert AwarenessCueSounds.isCannon("vehicle_strike");
        assert AwarenessCueSounds.isCannon("t_90a_far");
        assert !AwarenessCueSounds.isCannon("explosion_far");
        assert !AwarenessCueSounds.isCannon("ak_47_far");
        assert !AwarenessCueSounds.isCannon("track_vehicle_step");
    }

    private static void classifyDistant() {
        assertNull(AwarenessCueSounds.classifyDistantPath("explosion_far", null));
        assertNull(AwarenessCueSounds.classifyDistantPath("ak_47_far", null));
        assertKind(AwarenessCueSounds.classifyDistantPath("t_90a_far", null),
                AwarenessCues.TriggerKind.VEHICLE_CANNON);
        assertKind(AwarenessCueSounds.classifyDistantPath("t_90a_fire_3p", null),
                AwarenessCues.TriggerKind.VEHICLE_CANNON);
    }

    private static void classifyIds() {
        assertKind(AwarenessCues.TriggerKind.VEHICLE_ENGINE,
                id("superbwarfare", "track_vehicle_step"), SoundSource.PLAYERS, true);
        assertKind(AwarenessCues.TriggerKind.VEHICLE_CANNON,
                id("superbwarfare", "bl_132_fire_3p_01"), SoundSource.PLAYERS, false);
        assertKind(AwarenessCues.TriggerKind.CREW_VOICE,
                id("tacz_sewv", "ru_spotted_1"), SoundSource.VOICE, false);
        assertNull(AwarenessCueSounds.classifyId(
                id("minecraft", "entity.zombie.ambient"), SoundSource.HOSTILE, false));
        assertNull(AwarenessCueSounds.classifyId(
                id("superbwarfare", "wheel_vehicle_step"), SoundSource.PLAYERS, false));
    }

    private static void mergeOrdering() {
        assert AwarenessCues.compareSpots(0.85, 50, 100, 0.35, 20, 100) > 0
                : "cannon beats engine";
        assert AwarenessCues.compareSpots(1.0, 80, 100, 0.85, 40, 100) > 0
                : "outer entity beats cannon";
        assert AwarenessCues.compareSpots(0.35, 10, 100, 0.35, 20, 100) > 0
                : "closer tie-break";
    }

    private static void deterministicRoll() {
        boolean a = AwarenessCues.rollTrigger(42, BlockPos.ZERO.asLong(),
                AwarenessCues.TriggerKind.VEHICLE_ENGINE, 0.30);
        boolean b = AwarenessCues.rollTrigger(42, BlockPos.ZERO.asLong(),
                AwarenessCues.TriggerKind.VEHICLE_ENGINE, 0.30);
        assert a == b : "roll must be stable";
        assert AwarenessCues.rollTrigger(1, BlockPos.ZERO.asLong(),
                AwarenessCues.TriggerKind.VEHICLE_CANNON, 1.0);
        assert !AwarenessCues.rollTrigger(1, BlockPos.ZERO.asLong(),
                AwarenessCues.TriggerKind.CREW_VOICE, 0.0);
    }

    private static void dedupe() {
        AwarenessCues.Registry reg = new AwarenessCues.Registry();
        BlockPos pos = new BlockPos(8, 64, 8);
        long now = 1000L;
        assert !reg.wouldDedupe(pos, AwarenessCues.TriggerKind.VEHICLE_ENGINE, now);
        reg.registerForTest(pos, AwarenessCues.TriggerKind.VEHICLE_ENGINE, now);
        assert reg.wouldDedupe(pos, AwarenessCues.TriggerKind.VEHICLE_ENGINE, now + 10);
        assert !reg.wouldDedupe(pos, AwarenessCues.TriggerKind.VEHICLE_ENGINE, now + 40);
    }

    private static ResourceLocation id(String ns, String path) {
        return new ResourceLocation(ns, path);
    }

    private static void assertKind(AwarenessCues.TriggerKind actual, AwarenessCues.TriggerKind expected) {
        assert actual == expected : "expected " + expected + " got " + actual;
    }

    private static void assertKind(AwarenessCues.TriggerKind expected, ResourceLocation loc,
            SoundSource source, boolean moving) {
        AwarenessCues.TriggerKind kind = AwarenessCueSounds.classifyId(loc, source, moving);
        assert kind == expected : "expected " + expected + " for " + loc + " got " + kind;
    }

    private static void assertNull(AwarenessCues.TriggerKind kind) {
        assert kind == null : "expected null got " + kind;
    }

    private AwarenessCuesSelfCheck() {}
}
