package com.neoalive.tacz_sewv.compat;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.berezka.berezka_api.API;
import org.berezka.berezka_api.events.onStructureSpawned;

/**
 * Soft compat with <b>berezka_api</b>: when one of its structures generates, field faction
 * vehicles from the config pools at it. RU/US structures get crewed, armed, fuelled hulls
 * ({@link TankSpawner#spawnTankWithCrew}); PMC structures get a bare hull
 * ({@link TankSpawner#spawnBareVehicle}) for the player to capture.
 *
 * <p>This class references berezka_api types directly, so it is <b>only ever loaded</b> via
 * {@link #register()}, which {@code TaczSewv} calls behind a {@code ModList.isLoaded} gate.
 * berezka posts {@code onStructureSpawned} on the Forge event bus with the structure's
 * start-pool id (e.g. {@code "russian_army_structures:tank"}) and an anchor position, and
 * exposes {@link API#getCurWorld()} for the level being generated.
 */
public final class BerezkaStructureCompat {

    public static final String MODID = "berezka_api";

    private BerezkaStructureCompat() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(BerezkaStructureCompat.class);
    }

    @SubscribeEvent
    public static void onStructureSpawned(onStructureSpawned event) {
        if (!SewvConfig.STRUCTURE_VEHICLES_ENABLED.get()) return;

        TankSpawner.TankFaction faction = factionFor(event.getStructureName());
        if (faction == null) return; // not a mapped structure — ignore

        ServerLevel level = API.getCurWorld();
        if (level == null) return;
        BlockPos anchor = event.getBlockPos();
        if (anchor == null) return;

        // berezka can post this from a worldgen worker; hop to the main thread to spawn.
        // ponytail: assumes the structure's chunk is loaded by next tick (true when it
        // generates near a player). If distant pre-gen ever drops spawns, gate the spawn
        // on level.isLoaded(pos) and retry via berezka's ServerScheduler.
        level.getServer().execute(() -> spawnVehicles(level, anchor, faction));
    }

    private static TankSpawner.TankFaction factionFor(String structureName) {
        if (structureName == null) return null;
        if (SewvConfig.PMC_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.PMC;
        if (SewvConfig.RU_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.RU;
        if (SewvConfig.US_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.US;
        return null;
    }

    private static void spawnVehicles(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction) {
        int count = rollCount(level);
        for (int i = 0; i < count; i++) {
            BlockPos pos = placement(level, anchor, i);
            if (faction == TankSpawner.TankFaction.PMC) {
                TankSpawner.spawnBareVehicle(level, pos, faction);
            } else {
                TankSpawner.spawnTankWithCrew(level, pos, faction, null);
            }
        }
    }

    // One vehicle at world start, ramping toward the configured cap as in-game days accrue.
    // Each vehicle past the first is an independent weighted coin flip on the ramp progress,
    // so the count climbs with the war's age (with randomness) and never exceeds the cap.
    private static int rollCount(ServerLevel level) {
        int max = SewvConfig.STRUCTURE_VEHICLE_MAX_COUNT.get();
        if (max <= 1) return 1;
        int rampDays = SewvConfig.STRUCTURE_VEHICLE_RAMP_DAYS.get();
        double days = level.getGameTime() / 24000.0;
        double progress = rampDays <= 0 ? 1.0 : Math.min(1.0, days / rampDays);
        int count = 1;
        for (int i = 1; i < max; i++) {
            if (level.random.nextDouble() < progress) count++;
        }
        return count;
    }

    // Spread extra hulls around the anchor on a golden-angle spiral, each dropped to the
    // local surface, so a multi-vehicle spawn neither stacks in one block nor buries itself
    // in the structure's buildings. TankSpawner's own hasSpace check skips any that don't fit.
    private static BlockPos placement(ServerLevel level, BlockPos anchor, int index) {
        if (index == 0) return TankSpawner.adjustHeight(level, anchor);
        double angle = index * 2.399963; // ~137.5°, even angular spread
        int radius = 4 + index * 2;
        int x = anchor.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * radius);
        return TankSpawner.adjustHeight(level, new BlockPos(x, anchor.getY(), z));
    }
}
