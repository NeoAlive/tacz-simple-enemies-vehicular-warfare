package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;

/**
 * Whether a planned slot can actually take a mine. Ground mines need solid, dry, open footing at
 * about the field's own height; a wall charge needs a sturdy vertical face with room for its beam.
 * Never a floor or ceiling face for a wall charge, never a wall for a ground mine.
 */
public final class MineSites {

    /** How far a failed ground slot may slide to find footing. */
    static final int GROUND_NUDGE = 2;
    /** How far from its slot a wall charge looks for a wall. */
    static final int WALL_SEARCH = 4;
    /** Clear air a wall charge's beam needs in front of the face. */
    static final int WALL_BEAM_CLEAR = 3;
    /** A slot more than this above or below the anchor is on a roof or in a pit. */
    static final int MAX_STEP = 4;

    public record WallSpot(BlockPos pos, Direction face) {}

    private MineSites() {}

    /**
     * Feet block for a ground mine at or near {@code slot}, or null.
     *
     * @param taken mines already planned this order
     */
    @Nullable
    public static BlockPos ground(ServerLevel level, Vec3 slot, double anchorY, List<Vec3> taken, double gap,
                                  @Nullable UUID owner) {
        int cx = Mth.floor(slot.x);
        int cz = Mth.floor(slot.z);
        for (int r = 0; r <= GROUND_NUDGE; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos feet = groundFeet(level, cx + dx, cz + dz, anchorY);
                    if (feet != null && clearOfMines(level, Vec3.atBottomCenterOf(feet), taken, gap)
                            && !insideOwnFob(level, feet, owner)) {
                        return feet;
                    }
                }
            }
        }
        return null;
    }

    /** Re-check at plant time: the ground may have changed while the engineer walked over. */
    public static boolean stillGround(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && footing(level, feet);
    }

    @Nullable
    private static BlockPos groundFeet(ServerLevel level, int x, int z, double anchorY) {
        BlockPos column = new BlockPos(x, Mth.floor(anchorY), z);
        if (!level.hasChunkAt(column)) return null;
        int y = (int) GroundSnap.groundY(level, x + 0.5, z + 0.5, anchorY);
        if (Math.abs(y - anchorY) > MAX_STEP) return null;
        BlockPos feet = new BlockPos(x, y, z);
        return footing(level, feet) ? feet : null;
    }

    private static boolean footing(ServerLevel level, BlockPos feet) {
        BlockState under = level.getBlockState(feet.below());
        if (under.is(BlockTags.LEAVES) || under.is(BlockTags.ICE) || !under.getFluidState().isEmpty()) return false;
        if (!level.getBlockState(feet).getFluidState().isEmpty()) return false;
        return BailOutSupport.isStandable(level, feet);
    }

    /**
     * A vertical face near {@code slot} for a wall charge. Prefers a face whose beam crosses the
     * approach (normal across the field's heading) and needs open air in front of it.
     */
    @Nullable
    public static WallSpot wall(ServerLevel level, Vec3 slot, double anchorY, FormationFrame frame,
                                List<Vec3> taken, double gap, @Nullable UUID owner) {
        BlockPos base = groundFeet(level, Mth.floor(slot.x), Mth.floor(slot.z), anchorY);
        if (base == null) base = BlockPos.containing(slot.x, anchorY, slot.z);
        WallSpot best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -WALL_SEARCH; dx <= WALL_SEARCH; dx++) {
            for (int dz = -WALL_SEARCH; dz <= WALL_SEARCH; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos air = base.offset(dx, dy, dz);
                    if (!level.hasChunkAt(air) || !BailOutSupport.isPassable(level, air)) continue;
                    if (!level.getBlockState(air).getFluidState().isEmpty()) continue;
                    for (Direction face : Direction.Plane.HORIZONTAL) {
                        BlockPos wall = air.relative(face.getOpposite());
                        if (!level.getBlockState(wall).isFaceSturdy(level, wall, face)) continue;
                        if (!beamClear(level, air, face)) continue;
                        if (!clearOfMines(level, Vec3.atCenterOf(air), taken, gap)) continue;
                        if (insideOwnFob(level, air, owner)) continue;
                        // 0 when the beam runs straight across the approach, 1 when it points along it.
                        double along = Math.abs(face.getStepX() * frame.fx() + face.getStepZ() * frame.fz());
                        double score = along * 16.0 + dx * dx + dz * dz + dy;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new WallSpot(air, face);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean beamClear(ServerLevel level, BlockPos air, Direction face) {
        for (int i = 1; i <= WALL_BEAM_CLEAR; i++) {
            if (!BailOutSupport.isPassable(level, air.relative(face, i))) return false;
        }
        return true;
    }

    private static boolean clearOfMines(ServerLevel level, Vec3 at, List<Vec3> taken, double gap) {
        double min = Math.max(1.0, gap - 1.0);
        double min2 = min * min;
        for (Vec3 t : taken) {
            if (t.distanceToSqr(at) < min2) return false;
        }
        Set<EntityType<?>> types = mineTypes();
        return level.getEntitiesOfClass(Entity.class, new AABB(at, at).inflate(min),
                e -> types.contains(e.getType())).isEmpty();
    }

    private static Set<EntityType<?>> mineTypes() {
        Set<EntityType<?>> out = new HashSet<>();
        for (MineKinds.Kind k : MineKinds.all().values()) {
            EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(k.entity());
            if (t != null) out.add(t);
        }
        return out;
    }

    private static boolean insideOwnFob(ServerLevel level, BlockPos pos, @Nullable UUID owner) {
        if (owner == null) return false;
        FobInstance fob = FobManager.get(level).getFobForOwner(owner);
        return fob != null && fob.cachedMasterAabb != null && fob.cachedMasterAabb.contains(Vec3.atCenterOf(pos));
    }
}
