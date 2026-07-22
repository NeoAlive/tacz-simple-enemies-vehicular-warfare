package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Vanilla's walking pathfinder sized for the hull the crewman is driving, not for the
 * crewman. All the structural machinery — start/goal resolution, neighbor expansion, the
 * per-search caches, partial-collision handling — is inherited from
 * {@link WalkNodeEvaluator}; only what actually differs for a vehicle is overridden:
 *
 * <ul>
 * <li><b>Footprint.</b> {@link #prepare} swaps the mob's dimensions for the hull's, so the
 *     whole inherited machinery searches for tank-sized clearance.</li>
 * <li><b>Water standoff.</b> Ground vehicles bog/sink in water, and the units have no
 *     negative water malus by default, so a route would otherwise cut straight through a
 *     lake whenever the dry detour is longer. Any node within {@link #WATER_MARGIN} blocks
 *     of water is BLOCKED outright.</li>
 * <li><b>No 26-neighbour hazard scan.</b> An armored vehicle doesn't route around cactus,
 *     fire, or water borders, and that scan is the single most expensive part of
 *     evaluating each block of a 100+ block volume.</li>
 * </ul>
 *
 * <p>{@code PatrolSupport} also uses an unprepared instance purely as a block classifier
 * through the 4-arg {@link #getBlockPathType(BlockGetter, int, int, int)}, which reads no
 * instance state.
 */
public class GroundVehicleNodeEvaluator extends WalkNodeEvaluator {

    // Required clear distance (in blocks) between any drivable node and water.
    private static final int WATER_MARGIN = 3;

    // A hull that is already wet must be able to path OUT: the standoff blocks the start node
    // and everything around it, so every search from in the water fails, and the drive goal is
    // left steering blind at the destination. Keeping water out is only a rule for a dry hull.
    private boolean inWater;

    // ponytail: step/jump/fall limits stay the crewman's (vanilla reads them off this.mob),
    // not the hull's — a >1.125-block ledge may not path, but the drive goal steers straight
    // at the goal when no path exists and the hull's own physics climbs it. Override
    // getNeighbors/findAcceptedNode if pathing over tall steps ever matters.
    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.inWater = false;
        if (mob.getVehicle() instanceof VehicleEntity vehicle) {
            this.inWater = vehicle.isInWater();
            // The path is searched for the HULL's footprint, not the crewman's.
            this.entityWidth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            this.entityHeight = Mth.floor(vehicle.getBbHeight() + 1.0F);
            this.entityDepth = Mth.floor(vehicle.getBbWidth() + 1.0F);
        }
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        // Water standoff: reject any node whose footprint sits within WATER_MARGIN blocks of
        // water, so routes keep clear of shorelines instead of driving in. Done before the
        // volume scan — a blocked node needs no further classifying. The aggregate result is
        // cached per node by the inherited getCachedBlockType, so this runs at most once per
        // unique node per search.
        if (!this.inWater && this.hasWaterWithinMargin(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }

        // Scans the vehicle's full W×H×D volume like vanilla, but bails out on the first
        // block that rejects the node (fence/rail/negative malus) instead of classifying the
        // entire volume first — a tank footprint is 100+ blocks, so hitting a wall face
        // early saves almost the whole scan.
        BlockPathTypes center = BlockPathTypes.BLOCKED;
        BlockPathTypes worst = BlockPathTypes.BLOCKED;
        float worstMalus = mob.getPathfindingMalus(BlockPathTypes.BLOCKED);
        BlockPos mobPos = mob.blockPosition();
        for (int i = 0; i < this.entityWidth; ++i) {
            for (int j = 0; j < this.entityHeight; ++j) {
                for (int k = 0; k < this.entityDepth; ++k) {
                    BlockPathTypes blockpathtypes = this.getBlockPathType(level, i + x, j + y, k + z);
                    blockpathtypes = this.evaluateBlockPathType(level, mobPos, blockpathtypes);
                    if (i == 0 && j == 0 && k == 0) {
                        center = blockpathtypes;
                    }
                    if (blockpathtypes == BlockPathTypes.FENCE || blockpathtypes == BlockPathTypes.UNPASSABLE_RAIL) {
                        return blockpathtypes;
                    }
                    float malus = mob.getPathfindingMalus(blockpathtypes);
                    if (malus < 0.0F) {
                        return blockpathtypes;
                    }
                    if (malus >= worstMalus) {
                        worst = blockpathtypes;
                        worstMalus = malus;
                    }
                }
            }
        }
        return center == BlockPathTypes.OPEN && worstMalus == 0.0F && this.entityWidth <= 1 ? BlockPathTypes.OPEN : worst;
    }

    // True if any block within WATER_MARGIN of the node footprint (horizontally, at the
    // driving level and one below to catch water under a shoreline ledge) is water.
    private boolean hasWaterWithinMargin(BlockGetter level, int x, int y, int z) {
        int minX = x - WATER_MARGIN;
        int maxX = x + this.entityWidth - 1 + WATER_MARGIN;
        int minZ = z - WATER_MARGIN;
        int maxZ = z + this.entityDepth - 1 + WATER_MARGIN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = minX; cx <= maxX; ++cx) {
            for (int cz = minZ; cz <= maxZ; ++cz) {
                if (level.getFluidState(pos.set(cx, y, cz)).is(FluidTags.WATER)
                        || level.getFluidState(pos.set(cx, y - 1, cz)).is(FluidTags.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        // Same block classification as vanilla's getBlockPathTypeStatic, minus its
        // 26-neighbour hazard scan (checkNeighbourBlocks) — see the class doc.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        BlockPathTypes blockpathtypes = getBlockPathTypeRaw(level, pos);
        if (blockpathtypes == BlockPathTypes.OPEN && y >= level.getMinBuildHeight() + 1) {
            BlockPathTypes below = getBlockPathTypeRaw(level, pos.set(x, y - 1, z));
            blockpathtypes = below != BlockPathTypes.WALKABLE && below != BlockPathTypes.OPEN && below != BlockPathTypes.WATER && below != BlockPathTypes.LAVA ? BlockPathTypes.WALKABLE : BlockPathTypes.OPEN;
            if (below == BlockPathTypes.DAMAGE_FIRE) {
                blockpathtypes = BlockPathTypes.DAMAGE_FIRE;
            }
            if (below == BlockPathTypes.DAMAGE_OTHER) {
                blockpathtypes = BlockPathTypes.DAMAGE_OTHER;
            }
            if (below == BlockPathTypes.STICKY_HONEY) {
                blockpathtypes = BlockPathTypes.STICKY_HONEY;
            }
            if (below == BlockPathTypes.POWDER_SNOW) {
                blockpathtypes = BlockPathTypes.DANGER_POWDER_SNOW;
            }
            if (below == BlockPathTypes.DAMAGE_CAUTIOUS) {
                blockpathtypes = BlockPathTypes.DAMAGE_CAUTIOUS;
            }
        }
        return blockpathtypes;
    }
}
