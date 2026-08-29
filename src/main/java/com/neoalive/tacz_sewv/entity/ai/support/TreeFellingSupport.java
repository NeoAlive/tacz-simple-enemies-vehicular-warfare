package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesFeller;
import com.neoalive.tacz_sewv.config.SewvConfig;
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

    private TreeFellingSupport() {}

    public static void tick(AbstractUnit unit, VehicleEntity vehicle, HullFacts hull) {
        if (!EnhancedFallingTreesCompat.available()) return;
        if (!SewvConfig.VEHICLE_TREE_FELLING_ENABLED.get()) return;
        if (!hull.isGroundMobile()) return;
        if (vehicle.getDeltaMovement().horizontalDistanceSqr() < MIN_SPEED_SQ) return;

        Level level = vehicle.level();
        List<OBB> obbs = vehicle.getOBBs();
        List<OBB> contactObbs = inflateForContact(obbs);
        AABB scanBox = contactObbs.isEmpty()
                ? vehicle.getBoundingBox().inflate(CONTACT_BUFFER)
                : unionBox(contactObbs);

        int minX = Mth.floor(scanBox.minX);
        int minY = Mth.floor(scanBox.minY);
        int minZ = Mth.floor(scanBox.minZ);
        int maxX = Mth.floor(scanBox.maxX);
        int maxY = Mth.floor(scanBox.maxY);
        int maxZ = Mth.floor(scanBox.maxZ);

        // Cheapest check first: the OBB union AABB usually overestimates an angled hull's real
        // footprint, so most positions in it never touch anything. touchesHull (plain geometry)
        // and the #minecraft:logs / leaves tags filter for free; isFellable's registry scan runs
        // last, only against the handful of candidates that already passed both.
        Set<BlockPos> touchedNow = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!touchesHull(contactObbs, vehicle, pos)) continue;
            BlockState state = level.getBlockState(pos);
            BlockPos trunk = resolveTrunk(level, pos, state);
            if (trunk == null) continue;
            if (!EnhancedFallingTreesFeller.isFellable(level, trunk, level.getBlockState(trunk))) continue;
            touchedNow.add(trunk);
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

    /** Exact per-part collision when OBBs are available; falls back to the plain hitbox only for
     * a hull with no OBB data at all (unionBox already covers this — an empty obbs list means
     * scanBox came from getBoundingBox() in the first place). */
    private static boolean touchesHull(List<OBB> obbs, VehicleEntity vehicle, BlockPos pos) {
        AABB blockBox = new AABB(pos);
        if (obbs.isEmpty()) {
            return vehicle.getBoundingBox().inflate(CONTACT_BUFFER).intersects(blockBox);
        }
        for (OBB obb : obbs) {
            if (OBB.isColliding(obb, blockBox)) return true;
        }
        return false;
    }

    private static AABB unionBox(List<OBB> obbs) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (OBB obb : obbs) {
            for (Vector3d v : obb.getVertices()) {
                if (v.x < minX) minX = v.x;
                if (v.y < minY) minY = v.y;
                if (v.z < minZ) minZ = v.z;
                if (v.x > maxX) maxX = v.x;
                if (v.y > maxY) maxY = v.y;
                if (v.z > maxZ) maxZ = v.z;
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
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
