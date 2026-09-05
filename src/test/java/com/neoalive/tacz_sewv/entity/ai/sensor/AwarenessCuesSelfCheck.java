package com.neoalive.tacz_sewv.entity.ai.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * Self-check for awareness cue classification, merge order, dedupe, chance latch, and radii.
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
        classifyMinecraft();
        mergeOrdering();
        deterministicRoll();
        dedupe();
        taczDedupeHard();
        hearRadii();
        chanceLatch();

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
        assertKind(AwarenessCues.TriggerKind.DRONE,
                id("superbwarfare", "drone_engine"), SoundSource.PLAYERS, false);
        assertNull(AwarenessCueSounds.classifyId(
                id("minecraft", "entity.zombie.ambient"), SoundSource.HOSTILE, false));
        assertNull(AwarenessCueSounds.classifyId(
                id("superbwarfare", "wheel_vehicle_step"), SoundSource.PLAYERS, false));
    }

    private static void classifyMinecraft() {
        assertKind(AwarenessCueSounds.classifyMinecraft("entity.player.hurt"),
                AwarenessCues.TriggerKind.PLAYER_HURT);
        assertKind(AwarenessCueSounds.classifyMinecraft("entity.player.hurt_on_fire"),
                AwarenessCues.TriggerKind.PLAYER_HURT);
        assertKind(AwarenessCueSounds.classifyMinecraft("entity.generic.eat"),
                AwarenessCues.TriggerKind.PLAYER_EAT);
        assertKind(AwarenessCueSounds.classifyMinecraft("entity.player.burp"),
                AwarenessCues.TriggerKind.PLAYER_EAT);
        assertNull(AwarenessCueSounds.classifyMinecraft("entity.player.levelup"));
        assertNull(AwarenessCueSounds.classifyMinecraft("block.grass.break"));
    }

    private static void mergeOrdering() {
        assert AwarenessCues.compareSpots(0.85, 50, 100, 0.35, 20, 100) > 0
                : "cannon beats engine";
        assert AwarenessCues.compareSpots(1.0, 80, 100, 0.85, 40, 100) > 0
                : "outer entity beats cannon";
        assert AwarenessCues.compareSpots(0.35, 10, 100, 0.35, 20, 100) > 0
                : "closer tie-break";
        assert AwarenessCues.compareSpots(0.55, 40, 100, 0.40, 20, 100) > 0
                : "tacz fire beats drone";
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
        AwarenessCueRegistry reg = AwarenessCueRegistry.forTest();
        BlockPos pos = new BlockPos(8, 64, 8);
        long now = 1000L;
        assert !reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE, now);
        reg.registerForTest(pos, AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE, now);
        assert reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE, now + 10);
        assert !reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE, now + 40);
    }

    /** TaCZ fire: 120-tick / 16-block-cell throttle — still blocked well after engine's 40. */
    private static void taczDedupeHard() {
        AwarenessCueRegistry reg = AwarenessCueRegistry.forTest();
        BlockPos pos = new BlockPos(8, 64, 8);
        long now = 2000L;
        assert !reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now);
        reg.registerForTest(pos, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now);
        assert reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now + 40)
                : "tacz must still dedupe at engine interval";
        assert reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now + 119)
                : "tacz must dedupe until 120 ticks";
        assert !reg.wouldDedupe(pos, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now + 120)
                : "tacz may re-register at 120 ticks";
        // Same 16-block cell: offset still inside [0,16)
        BlockPos nearby = new BlockPos(15, 64, 8);
        assert reg.wouldDedupe(nearby, AwarenessCueRegistry.TriggerKind.TACZ_FIRE, now + 10)
                : "tacz 16-block cell should cover nearby fire";
    }

    private static void hearRadii() {
        assert AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE.hearRadius == 30;
        assert AwarenessCueRegistry.TriggerKind.VEHICLE_CANNON.hearRadius == 128;
        assert AwarenessCueRegistry.TriggerKind.CREW_VOICE.hearRadius == 20;
        assert AwarenessCueRegistry.TriggerKind.TACZ_FIRE.hearRadius == 64;
        assert AwarenessCueRegistry.TriggerKind.DRONE.hearRadius == 48;
        assert AwarenessCueRegistry.TriggerKind.PLAYER_HURT.hearRadius == 24;
        assert AwarenessCueRegistry.TriggerKind.PLAYER_EAT.hearRadius == 16;
        assert AwarenessCueRegistry.TriggerKind.maxHearRadius() == 128;
        assert AwarenessCues.MAX_SOUND_HEAR_RADIUS == 128;
    }

    private static void chanceLatch() {
        AwarenessCues cues = new AwarenessCues();
        long key = BlockPos.ZERO.asLong() ^ ((long) AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE.ordinal() << 48);
        assert cues.latchGet(key) == null;
        boolean pass = AwarenessCues.rollTrigger(7, BlockPos.ZERO.asLong(),
                AwarenessCues.TriggerKind.VEHICLE_ENGINE, 0.30);
        cues.latchPut(key, pass);
        assert cues.latchGet(key) != null && cues.latchGet(key) == pass;
        // Second put must not flip — latch semantics are write-once from consume path; here we
        // only assert the map holds the first decision.
        Boolean again = cues.latchGet(key);
        assert again != null && again == pass;
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
