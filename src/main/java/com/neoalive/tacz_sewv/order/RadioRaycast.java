package com.neoalive.tacz_sewv.order;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;

/**
 * The radio's one designation raycast, run on the SERVER from the caller's view so it reaches as
 * far as the weapons do rather than as far as the client renders. What it hits decides the
 * mission: an entity becomes an entity mission, a block a grid mission.
 *
 * <p>It never loads a chunk — {@code ServerLevel.getBlockState} on an unloaded chunk loads it
 * synchronously. Loaded chunks are clipped exactly; past them the terrain is the world
 * generator's noise surface, which knows nothing of builds, structures or trees. Entities exist
 * only in loaded chunks, so beyond them only a grid mission is possible at all.
 */
public final class RadioRaycast {

    /** Noise terrain is sampled once per 4x4 column cell — the generator's own cell size. */
    private static final int CELL_SHIFT = 2;

    /**
     * @param entity    designated target (a hull already resolved to a crew member), or null
     * @param friendly  the ray struck an own unit / own hull before anything else
     * @param pos       grid aimpoint when no entity was hit, or null for a clean miss
     * @param estimated {@code pos} comes from the generator surface, not a loaded block
     */
    public record Hit(@Nullable LivingEntity entity, boolean friendly, @Nullable BlockPos pos, boolean estimated) {}

    private RadioRaycast() {}

    public static Hit resolve(ServerPlayer player, float yaw, float pitch, double range) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));

        Long2IntOpenHashMap surfaces = new Long2IntOpenHashMap();
        BlockTrace block = BlockGetter.traverseBlocks(eye, end, level,
                (lvl, pos) -> testBlock(lvl, pos, eye, end, surfaces), lvl -> null);
        Vec3 stop = block != null ? block.location() : end;

        AABB box = new AABB(eye, stop).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, stop, box,
                e -> e != player && (HandheldRadioItem.isDesignatable(e) || isOwnSide(e)),
                eye.distanceToSqr(stop));
        if (hit != null) {
            Entity e = hit.getEntity();
            if (isOwnSide(e)) return new Hit(null, true, null, false);
            LivingEntity living = e instanceof VehicleEntity hull
                    ? HandheldRadioItem.representativeCrew(hull)
                    : e instanceof LivingEntity l ? l : null;
            if (living != null) return new Hit(living, false, null, false);
        }
        return block == null
                ? new Hit(null, false, null, false)
                : new Hit(null, false, block.pos(), block.estimated());
    }

    private static boolean isOwnSide(Entity e) {
        return e instanceof PmcUnitEntity
                || (e instanceof VehicleEntity hull && CrewFacts.factionOf(hull) == CrewFacts.Faction.PMC);
    }

    private record BlockTrace(BlockPos pos, Vec3 location, boolean estimated) {}

    @Nullable
    private static BlockTrace testBlock(ServerLevel level, BlockPos pos, Vec3 from, Vec3 to,
                                        Long2IntOpenHashMap surfaces) {
        if (level.isOutsideBuildHeight(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk != null) {
            BlockState state = chunk.getBlockState(pos);
            BlockHitResult clip = state.getCollisionShape(chunk, pos).clip(from, to, pos);
            return clip == null ? null : new BlockTrace(pos.immutable(), clip.getLocation(), false);
        }
        // ponytail: one noise column per 4x4 cell the ray crosses (≤ ~300 at mortar range, one-off
        // per call). Stride + refine if a profile ever shows it.
        int cx = pos.getX() >> CELL_SHIFT;
        int cz = pos.getZ() >> CELL_SHIFT;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        int surface = surfaces.computeIfAbsent(key, k -> {
            int sx = (cx << CELL_SHIFT) + 2;
            int sz = (cz << CELL_SHIFT) + 2;
            return level.getChunkSource().getGenerator().getBaseHeight(sx, sz,
                    Heightmap.Types.OCEAN_FLOOR, level, level.getChunkSource().randomState());
        });
        if (pos.getY() >= surface) return null;
        BlockPos ground = new BlockPos(pos.getX(), surface - 1, pos.getZ());
        return new BlockTrace(ground, Vec3.atCenterOf(ground), true);
    }
}
