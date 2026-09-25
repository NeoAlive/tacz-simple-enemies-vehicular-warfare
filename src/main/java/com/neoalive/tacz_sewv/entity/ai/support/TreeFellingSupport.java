package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesFeller;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.PathingPerf;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * Fells trees a ground hull's real hitbox touches, using {@link EnhancedFallingTreesCompat}.
 * Called unconditionally, once per tick, from {@code DriveVehicleGoal.tick()} — every early-out
 * below is the first thing checked, in that order, so the mod-absent path costs one static
 * boolean read and nothing else.
 *
 * <p>"Touches" is decided from SBW's own per-part {@link OBB}s ({@code Part.WHEEL_LEFT}/
 * {@code WHEEL_RIGHT}/{@code TURRET}/{@code BODY}/…, {@link VehicleEntity#getOBBs()} —
 * every {@code VehicleEntity} implements {@link OBBEntity}), inflated by one block so a
 * newer SBW collision resolution cannot climb into the canopy before contact registers.
 * Tracks and a turret overhang routinely sit outside the entity AABB, and SBW already
 * keeps these world-space and current every tick (its own hit-detection runs off the same
 * list), so this reuses real data instead of a second, approximate hitbox. Leaf contact
 * resolves downward to a trunk before felling starts.
 *
 * <p>Scan volume is each part's {@link OBB#getWorldAABB} (not one fat vertex-union), and
 * {@code #minecraft:logs}/{@code leaves} are filtered <em>before</em> SAT so air/dirt cells
 * never pay {@link OBB#isColliding}.
 *
 * <p>A tree does not fall on the first tick of contact — it needs {@link
 * SewvConfig#VEHICLE_TREE_CONTACT_TICKS} of <em>unbroken</em> contact first, so a hull that only
 * clips a trunk in passing does not fell it. Per-tree contact start times live in the vehicle's
 * own persistent data ({@link #TAG_CONTACTS}, a flat {@code long[]} of (x, y, z, startTick)
 * quads) rather than a static map keyed by entity id: this is exactly the "BlockPos, no network
 * id" shape this mod already persists elsewhere (see {@code bridge/IMortarCrew}'s fire mission),
 * so it needs no cleanup hook — it lives and dies with the entity. Surviving a save/reload mid-
 * contact is harmless (worst case a timer resumes instead of resetting); nothing depends on it.
 */
public final class TreeFellingSupport {

    /** Below this speed a hull cannot be driving into anything new by contact; skip the scan. */
    private static final double MIN_SPEED_SQ = 1.0E-4;

    /**
     * Extra reach (blocks) around each OBB for tree contact. SBW's newer collision lets a hull
     * climb into the canopy before the exact OBB grazes the trunk; one block of buffer fells
     * earlier without inventing a second hitbox system.
     */
    private static final double CONTACT_BUFFER = 1.0;

    /** How far below a leaf contact to search for the trunk to fell. */
    private static final int TRUNK_SEARCH_DEPTH = 8;

    private static final String TAG_CONTACTS = "sewv_tree_contacts";
    /** Game-time deadline of the next scan ({@code treeScanIntervalTicks}); on the hull, so no cleanup hook. */
    private static final String TAG_NEXT_SCAN = "sewv_tree_next_scan";

    /** Palette pre-check: a chunk section that cannot contain either is skipped without a block read. */
    private static final Predicate<BlockState> TREE_BLOCK =
            state -> state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);

    private TreeFellingSupport() {}

    /**
     * @param farIdle idle and far from every player ({@code groundFarLodBlocks}): scans half as often.
     */
    public static void tick(AbstractUnit unit, VehicleEntity vehicle, HullFacts hull, boolean farIdle) {
        if (!EnhancedFallingTreesCompat.available()) return;
        if (!SewvConfig.VEHICLE_TREE_FELLING_ENABLED.get()) return;
        if (!hull.isGroundMobile()) return;
        if (vehicle.getDeltaMovement().horizontalDistanceSqr() < MIN_SPEED_SQ) return;

        // treeScanIntervalTicks: contact start times are game time, so a tree still falls after the same
        // unbroken contact window — at most one interval later. A deadline, never a modulo on game time.
        Level level = vehicle.level();
        long now0 = level.getGameTime();
        int interval = Math.max(1, SewvConfig.TREE_SCAN_INTERVAL_TICKS.get()) * (farIdle ? 2 : 1);
        CompoundTag data = vehicle.getPersistentData();
        long nextScan = data.getLong(TAG_NEXT_SCAN);
        if (interval > 1) {
            if (now0 < nextScan && nextScan - now0 <= interval) return;
            data.putLong(TAG_NEXT_SCAN, now0 + interval);
        }
        long t0 = System.nanoTime();
        try {
            scanAndFell(level, vehicle);
        } finally {
            PathingPerf.treeScanNanos += System.nanoTime() - t0;
            PathingPerf.treeScans++;
        }
    }

    private static void scanAndFell(Level level, VehicleEntity vehicle) {
        List<OBB> obbs = vehicle.getOBBs();
        List<OBB> contactObbs = inflateForContact(obbs);

        List<AABB> partBoxes;
        AABB fallbackBox = null;
        if (contactObbs.isEmpty()) {
            partBoxes = List.of();
            fallbackBox = vehicle.getBoundingBox().inflate(CONTACT_BUFFER);
        } else {
            partBoxes = new ArrayList<>(contactObbs.size());
            for (OBB obb : contactObbs) {
                partBoxes.add(OBB.getWorldAABB(obb));
            }
        }

        LongOpenHashSet visited = new LongOpenHashSet();
        Set<BlockPos> touchedNow = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        if (fallbackBox != null) {
            scanBox(level, contactObbs, partBoxes, vehicle, fallbackBox, visited, touchedNow, cursor);
        } else {
            for (AABB partBox : partBoxes) {
                scanBox(level, contactObbs, partBoxes, vehicle, partBox, visited, touchedNow, cursor);
            }
        }

        long now = level.getGameTime();
        int contactTicks = SewvConfig.VEHICLE_TREE_CONTACT_TICKS.get();
        Map<BlockPos, Long> contacts = readContacts(vehicle);
        Map<BlockPos, Long> nextContacts = touchedNow.isEmpty() ? Map.of() : new HashMap<>(touchedNow.size());

        int felled = 0;
        for (BlockPos pos : touchedNow) {
            long startedAt = contacts.getOrDefault(pos, now);
            if (now - startedAt >= contactTicks) {
                if (EnhancedFallingTreesFeller.tryFell(level, pos, vehicle)) {
                    felled++;
                    continue; // tree is gone; don't carry its contact forward
                }
            }
            nextContacts.put(pos, startedAt);
        }
        writeContacts(vehicle, nextContacts);

        // One hit for the whole tick rather than one per tree — a wide hull straddling two
        // trunks in the same tick must not have the second hit silently dropped by invuln-frame
        // suppression on the vehicle's own hurt() handling.
        if (felled > 0) {
            vehicle.hurt(level.damageSources().generic(), felled * SewvConfig.VEHICLE_TREE_FELL_DAMAGE.get().floatValue());
        }
    }

    private static void scanBox(Level level, List<OBB> contactObbs, List<AABB> partBoxes,
                                VehicleEntity vehicle, AABB box, LongOpenHashSet visited,
                                Set<BlockPos> touchedNow, BlockPos.MutableBlockPos cursor) {
        int minX = Mth.floor(box.minX);
        int minY = Math.max(Mth.floor(box.minY), level.getMinBuildHeight());
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Math.min(Mth.floor(box.maxY), level.getMaxBuildHeight() - 1);
        int maxZ = Mth.floor(box.maxZ);

        // Walk section by section so a section whose palette holds no log or leaf is skipped whole —
        // on open ground that is every section, and the scan does no block reads at all. Exact: such a
        // section has no cell this loop could accept.
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
                if (chunk == null) continue;
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section.hasOnlyAir() || !section.maybeHas(TREE_BLOCK)) {
                        PathingPerf.treeSectionsSkipped++;
                        continue;
                    }
                    int x0 = Math.max(minX, sx << 4), x1 = Math.min(maxX, (sx << 4) + 15);
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                    int z0 = Math.max(minZ, sz << 4), z1 = Math.min(maxZ, (sz << 4) + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int y = y0; y <= y1; y++) {
                            for (int z = z0; z <= z1; z++) {
                                long packed = BlockPos.asLong(x, y, z);
                                if (!visited.add(packed)) continue;
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                // Tag filter before SAT — most cells in the overestimate are air/dirt.
                                if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) continue;
                                cursor.set(x, y, z);
                                if (!touchesHull(contactObbs, partBoxes, vehicle, cursor)) continue;
                                BlockPos trunk = resolveTrunk(level, cursor, state);
                                if (trunk == null) continue;
                                if (!EnhancedFallingTreesFeller.isFellable(level, trunk, level.getBlockState(trunk))) continue;
                                touchedNow.add(trunk);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Inflate every part by {@link #CONTACT_BUFFER}; empty list stays empty (caller uses entity BB). */
    private static List<OBB> inflateForContact(List<OBB> obbs) {
        if (obbs == null || obbs.isEmpty()) return List.of();
        List<OBB> out = new ArrayList<>(obbs.size());
        for (OBB obb : obbs) {
            out.add(obb.inflate(CONTACT_BUFFER));
        }
        return out;
    }

    /**
     * Log under the contact cell, or the cell itself if it is already a log. Leaf-first contact
     * (wide canopy) must still name a trunk or felling never starts and the hull climbs foliage.
     */
    private static BlockPos resolveTrunk(Level level, BlockPos pos, BlockState state) {
        if (state.is(BlockTags.LOGS)) return pos.immutable();
        if (!state.is(BlockTags.LEAVES)) return null;
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int dy = 1; dy <= TRUNK_SEARCH_DEPTH; dy++) {
            cursor.set(pos.getX(), pos.getY() - dy, pos.getZ());
            if (level.getBlockState(cursor).is(BlockTags.LOGS)) return cursor.immutable();
        }
        return null;
    }

    /**
     * Exact per-part collision when OBBs are available; falls back to the plain hitbox only for
     * a hull with no OBB data at all. Broadphase uses each part's world AABB before SAT.
     */
    private static boolean touchesHull(List<OBB> obbs, List<AABB> worldBoxes, VehicleEntity vehicle,
                                       BlockPos pos) {
        AABB blockBox = new AABB(pos);
        if (obbs.isEmpty()) {
            return vehicle.getBoundingBox().inflate(CONTACT_BUFFER).intersects(blockBox);
        }
        for (int i = 0; i < obbs.size(); i++) {
            if (!worldBoxes.get(i).intersects(blockBox)) continue;
            if (OBB.isColliding(obbs.get(i), blockBox)) return true;
        }
        return false;
    }

    private static Map<BlockPos, Long> readContacts(VehicleEntity vehicle) {
        CompoundTag data = vehicle.getPersistentData();
        if (!data.contains(TAG_CONTACTS)) return Map.of();
        long[] flat = data.getLongArray(TAG_CONTACTS);
        Map<BlockPos, Long> contacts = new HashMap<>(flat.length / 4);
        for (int i = 0; i + 3 < flat.length; i += 4) {
            BlockPos pos = new BlockPos((int) flat[i], (int) flat[i + 1], (int) flat[i + 2]);
            contacts.put(pos, flat[i + 3]);
        }
        return contacts;
    }

    private static void writeContacts(VehicleEntity vehicle, Map<BlockPos, Long> contacts) {
        CompoundTag data = vehicle.getPersistentData();
        if (contacts.isEmpty()) {
            data.remove(TAG_CONTACTS);
            return;
        }
        long[] flat = new long[contacts.size() * 4];
        int i = 0;
        for (Map.Entry<BlockPos, Long> entry : contacts.entrySet()) {
            BlockPos pos = entry.getKey();
            flat[i++] = pos.getX();
            flat[i++] = pos.getY();
            flat[i++] = pos.getZ();
            flat[i++] = entry.getValue();
        }
        data.putLongArray(TAG_CONTACTS, flat);
    }
}
