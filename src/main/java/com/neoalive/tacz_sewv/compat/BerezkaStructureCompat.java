package com.neoalive.tacz_sewv.compat;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.berezka.berezka_api.API;
import org.berezka.berezka_api.events.onStructureSpawned;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Soft compat with <b>berezka_api</b>: when one of its structures generates, field faction
 * vehicles from the config pools at it. Every faction — RU, US and PMC — gets a crewed, armed,
 * fuelled hull ({@link TankSpawner#spawnTankWithCrew}), like a village garrison; a PMC structure
 * fields friendly (ownerless) PMC crew. (Earlier PMC structures spawned a bare hull for the
 * player to capture; they are crewed now so a friendly camp is actually defended.)
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
        // Footprint of the just-generated structure — hulls spawn just OUTSIDE it, not at the
        // origin. Pieces are immutable data, safe to read off this (possibly worldgen) thread.
        BoundingBox bounds = structureBounds(event.getPieces());

        // berezka can post this from a worldgen worker; hop to the main thread to spawn.
        // ponytail: assumes the structure's chunk is loaded by next tick (true when it
        // generates near a player). If distant pre-gen ever drops spawns, gate the spawn
        // on level.isLoaded(pos) and retry via berezka's ServerScheduler.
        level.getServer().execute(() -> spawnVehicles(level, anchor, bounds, faction));
    }

    private static TankSpawner.TankFaction factionFor(String structureName) {
        if (structureName == null) return null;
        if (SewvConfig.PMC_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.PMC;
        if (SewvConfig.RU_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.RU;
        if (SewvConfig.US_VEHICLE_STRUCTURES.get().contains(structureName)) return TankSpawner.TankFaction.US;
        return null;
    }

    private static void spawnVehicles(ServerLevel level, BlockPos anchor, @Nullable BoundingBox bounds,
                                      TankSpawner.TankFaction faction) {
        int count = rollCount(level);
        for (int i = 0; i < count; i++) {
            BlockPos pos = placement(level, anchor, bounds, i);
            // Every faction gets a crewed, fuelled, armed hull — a PMC structure fields friendly
            // PMC crew (ownerId null = FRIENDLY_DEFAULT, ownerless), the same as RU/US and the
            // village garrisons. See the class doc for why crewed and not a parked bare hull.
            TankSpawner.spawnTankWithCrew(level, pos, faction, null);
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

    /** How far past the structure's outer wall a hull is pushed before the ground/clear search. */
    private static final int EDGE_MARGIN = 3;

    // Fan hulls around the OUTSIDE of the structure's footprint on a golden-angle spread, each
    // pushed just past the bounding-box edge in its direction — the anchor is the structure
    // origin (usually mid-building), so spawning there buries the hull. adjustHeight drops it to
    // the local surface and TankSpawner.findClearSpawn snaps off any remaining obstruction.
    // Falls back to an anchor-centred ring when the event carried no pieces to bound.
    private static BlockPos placement(ServerLevel level, BlockPos anchor, @Nullable BoundingBox bounds, int index) {
        double angle = index * 2.399963; // ~137.5°, even angular spread
        double dirX = Math.cos(angle), dirZ = Math.sin(angle);
        int x, z;
        if (bounds != null) {
            double cx = (bounds.minX() + bounds.maxX() + 1) / 2.0;
            double cz = (bounds.minZ() + bounds.maxZ() + 1) / 2.0;
            double hx = (bounds.maxX() - bounds.minX() + 1) / 2.0;
            double hz = (bounds.maxZ() - bounds.minZ() + 1) / 2.0;
            // Distance from centre to the box edge along the ray (nearer face wins), then a margin
            // clear of the wall. |dir| is never both ~0, so at least one term is finite.
            double tx = Math.abs(dirX) < 1e-4 ? Double.MAX_VALUE : hx / Math.abs(dirX);
            double tz = Math.abs(dirZ) < 1e-4 ? Double.MAX_VALUE : hz / Math.abs(dirZ);
            double t = Math.min(tx, tz) + EDGE_MARGIN;
            x = (int) Math.round(cx + dirX * t);
            z = (int) Math.round(cz + dirZ * t);
        } else {
            int radius = 6 + index * 2;
            x = anchor.getX() + (int) Math.round(dirX * radius);
            z = anchor.getZ() + (int) Math.round(dirZ * radius);
        }
        return TankSpawner.adjustHeight(level, new BlockPos(x, anchor.getY(), z));
    }

    /** Union of the structure pieces' boxes — the footprint hulls must clear. Null if none given. */
    @Nullable
    private static BoundingBox structureBounds(List<StructurePiece> pieces) {
        if (pieces == null || pieces.isEmpty()) return null;
        BoundingBox union = null;
        for (StructurePiece piece : pieces) {
            if (piece == null) continue;
            BoundingBox box = piece.getBoundingBox();
            union = union == null ? box : union.encapsulate(box);
        }
        return union;
    }
}
