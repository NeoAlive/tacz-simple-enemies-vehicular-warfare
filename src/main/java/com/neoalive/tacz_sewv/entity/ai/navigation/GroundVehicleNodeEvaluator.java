package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class GroundVehicleNodeEvaluator extends NodeEvaluator {
    private static final double DEFAULT_MOB_JUMP_HEIGHT = 1.125D;
    // Required clear distance (in blocks) between any drivable node and water.
    // Ground vehicles bog/sink in water, and the units have no negative water
    // malus by default, so a route will otherwise cut straight through a lake
    // whenever the dry detour is longer. This enforces a hard standoff instead.
    private static final int WATER_MARGIN = 3;
    private final Long2ObjectMap<BlockPathTypes> pathTypesByPosCache = new Long2ObjectOpenHashMap<>();
    private final Object2BooleanMap<AABB> collisionCache = new Object2BooleanOpenHashMap<>();
    private VehicleEntity vehicle;

    public void prepare(PathNavigationRegion p_77620_, Mob p_77621_) {
        super.prepare(p_77620_, p_77621_);
        if (p_77621_.getVehicle() instanceof VehicleEntity vehicle) {
            this.vehicle = vehicle;
            this.entityWidth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            this.entityHeight = Mth.floor(vehicle.getBbHeight() + 1.0F);
            this.entityDepth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            p_77621_.onPathfindingStart();
        }
    }

    public void done() {
    if (this.mob != null) {
        this.mob.onPathfindingDone();
    }
    this.pathTypesByPosCache.clear();
    this.collisionCache.clear();
    this.vehicle = null; // don't retain the hull entity between searches
    super.done();
}

    public Node getStart() {
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        int i = this.vehicle.getBlockY();
        BlockState blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(this.vehicle.getX(), (double) i, this.vehicle.getZ()));
        if (!this.canStandOnFluid(blockstate.getFluidState())) {
            if (this.canFloat() && this.vehicle.isInWater()) {
                while (true) {
                    if (!blockstate.is(Blocks.WATER) && blockstate.getFluidState() != Fluids.WATER.getSource(false)) {
                        --i;
                        break;
                    }
                    ++i;
                    blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(this.vehicle.getX(), (double) i, this.vehicle.getZ()));
                }
            } else if (this.vehicle.onGround()) {
                i = Mth.floor(this.vehicle.getY() + 0.5D);
            } else {
                BlockPos blockpos;
                for (blockpos = this.vehicle.blockPosition(); (this.level.getBlockState(blockpos).isAir() || this.level.getBlockState(blockpos).isPathfindable(this.level, blockpos, PathComputationType.LAND)) && blockpos.getY() > this.vehicle.level().getMinBuildHeight(); blockpos = blockpos.below()) {
                }
                i = blockpos.above().getY();
            }
        } else {
            while (this.canStandOnFluid(blockstate.getFluidState())) {
                ++i;
                blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(this.vehicle.getX(), (double) i, this.vehicle.getZ()));
            }
            --i;
        }
        BlockPos blockpos1 = this.vehicle.blockPosition();
        if (!this.canStartAt(blockpos$mutableblockpos.set(blockpos1.getX(), i, blockpos1.getZ()))) {
            AABB aabb = this.vehicle.getBoundingBox();
            if (this.canStartAt(blockpos$mutableblockpos.set(aabb.minX, (double) i, aabb.minZ)) || this.canStartAt(blockpos$mutableblockpos.set(aabb.minX, (double) i, aabb.maxZ)) || this.canStartAt(blockpos$mutableblockpos.set(aabb.maxX, (double) i, aabb.minZ)) || this.canStartAt(blockpos$mutableblockpos.set(aabb.maxX, (double) i, aabb.maxZ))) {
                return this.getStartNode(blockpos$mutableblockpos);
            }
        }
        return this.getStartNode(new BlockPos(blockpos1.getX(), i, blockpos1.getZ()));
    }

    private boolean canStandOnFluid(FluidState fluidState) {
        return false;
    }

    protected Node getStartNode(BlockPos p_230632_) {
        Node node = this.getNode(p_230632_);
        node.type = this.getBlockPathType(this.mob, node.asBlockPos());
        node.costMalus = this.mob.getPathfindingMalus(node.type);
        return node;
    }

    protected boolean canStartAt(BlockPos p_262596_) {
        BlockPathTypes blockpathtypes = this.getBlockPathType(this.mob, p_262596_);
        return blockpathtypes != BlockPathTypes.OPEN && this.mob.getPathfindingMalus(blockpathtypes) >= 0.0F;
    }

    public Target getGoal(double p_77550_, double p_77551_, double p_77552_) {
        return this.getTargetFromNode(this.getNode(Mth.floor(p_77550_), Mth.floor(p_77551_), Mth.floor(p_77552_)));
    }

    public int getNeighbors(Node[] p_77640_, Node p_77641_) {
        int i = 0;
        int j = 0;
        BlockPathTypes blockpathtypes = this.getCachedBlockType(this.mob, p_77641_.x, p_77641_.y + 1, p_77641_.z);
        BlockPathTypes blockpathtypes1 = this.getCachedBlockType(this.mob, p_77641_.x, p_77641_.y, p_77641_.z);
        if (this.mob.getPathfindingMalus(blockpathtypes) >= 0.0F && blockpathtypes1 != BlockPathTypes.STICKY_HONEY) {
            j = Mth.floor(Math.max(1.0F, this.vehicle.getStepHeight()));
        }
        double d0 = this.getFloorLevel(new BlockPos(p_77641_.x, p_77641_.y, p_77641_.z));
        Node node = this.findAcceptedNode(p_77641_.x, p_77641_.y, p_77641_.z + 1, j, d0, Direction.SOUTH, blockpathtypes1);
        if (this.isNeighborValid(node, p_77641_)) {
            p_77640_[i++] = node;
        }
        Node node1 = this.findAcceptedNode(p_77641_.x - 1, p_77641_.y, p_77641_.z, j, d0, Direction.WEST, blockpathtypes1);
        if (this.isNeighborValid(node1, p_77641_)) {
            p_77640_[i++] = node1;
        }
        Node node2 = this.findAcceptedNode(p_77641_.x + 1, p_77641_.y, p_77641_.z, j, d0, Direction.EAST, blockpathtypes1);
        if (this.isNeighborValid(node2, p_77641_)) {
            p_77640_[i++] = node2;
        }
        Node node3 = this.findAcceptedNode(p_77641_.x, p_77641_.y, p_77641_.z - 1, j, d0, Direction.NORTH, blockpathtypes1);
        if (this.isNeighborValid(node3, p_77641_)) {
            p_77640_[i++] = node3;
        }
        Node node4 = this.findAcceptedNode(p_77641_.x - 1, p_77641_.y, p_77641_.z - 1, j, d0, Direction.NORTH, blockpathtypes1);
        if (this.isDiagonalValid(p_77641_, node1, node3, node4)) {
            p_77640_[i++] = node4;
        }
        Node node5 = this.findAcceptedNode(p_77641_.x + 1, p_77641_.y, p_77641_.z - 1, j, d0, Direction.NORTH, blockpathtypes1);
        if (this.isDiagonalValid(p_77641_, node2, node3, node5)) {
            p_77640_[i++] = node5;
        }
        Node node6 = this.findAcceptedNode(p_77641_.x - 1, p_77641_.y, p_77641_.z + 1, j, d0, Direction.SOUTH, blockpathtypes1);
        if (this.isDiagonalValid(p_77641_, node1, node, node6)) {
            p_77640_[i++] = node6;
        }
        Node node7 = this.findAcceptedNode(p_77641_.x + 1, p_77641_.y, p_77641_.z + 1, j, d0, Direction.SOUTH, blockpathtypes1);
        if (this.isDiagonalValid(p_77641_, node2, node, node7)) {
            p_77640_[i++] = node7;
        }
        return i;
    }

    protected boolean isNeighborValid(@Nullable Node p_77627_, Node p_77628_) {
        return p_77627_ != null && !p_77627_.closed && (p_77627_.costMalus >= 0.0F || p_77628_.costMalus < 0.0F);
    }

    protected boolean isDiagonalValid(Node p_77630_, @Nullable Node p_77631_, @Nullable Node p_77632_, @Nullable Node p_77633_) {
        if (p_77633_ != null && p_77632_ != null && p_77631_ != null) {
            if (p_77633_.closed) {
                return false;
            } else if (p_77632_.y <= p_77630_.y && p_77631_.y <= p_77630_.y) {
                if (p_77631_.type != BlockPathTypes.WALKABLE_DOOR && p_77632_.type != BlockPathTypes.WALKABLE_DOOR && p_77633_.type != BlockPathTypes.WALKABLE_DOOR) {
                    boolean flag = p_77632_.type == BlockPathTypes.FENCE && p_77631_.type == BlockPathTypes.FENCE && (double) this.vehicle.getBbWidth() < 0.5D;
                    return p_77633_.costMalus >= 0.0F && (p_77632_.y < p_77630_.y || p_77632_.costMalus >= 0.0F || flag) && (p_77631_.y < p_77630_.y || p_77631_.costMalus >= 0.0F || flag);
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private static boolean doesBlockHavePartialCollision(BlockPathTypes p_230626_) {
        return p_230626_ == BlockPathTypes.FENCE || p_230626_ == BlockPathTypes.DOOR_WOOD_CLOSED || p_230626_ == BlockPathTypes.DOOR_IRON_CLOSED;
    }

    private boolean canReachWithoutCollision(Node p_77625_) {
        AABB aabb = this.vehicle.getBoundingBox();
        Vec3 vec3 = new Vec3((double) p_77625_.x - this.vehicle.getX() + aabb.getXsize() / 2.0D, (double) p_77625_.y - this.vehicle.getY() + aabb.getYsize() / 2.0D, (double) p_77625_.z - this.vehicle.getZ() + aabb.getZsize() / 2.0D);
        int i = Mth.ceil(vec3.length() / aabb.getSize());
        vec3 = vec3.scale((double) (1.0F / (float) i));
        for (int j = 1; j <= i; ++j) {
            aabb = aabb.move(vec3);
            if (this.hasCollisions(aabb)) {
                return false;
            }
        }
        return true;
    }

    protected double getFloorLevel(BlockPos p_164733_) {
        return (this.canFloat() || this.isAmphibious()) && this.level.getFluidState(p_164733_).is(FluidTags.WATER) ? (double) p_164733_.getY() + 0.5D : getFloorLevel(this.level, p_164733_);
    }

    public static double getFloorLevel(BlockGetter p_77612_, BlockPos p_77613_) {
        BlockPos blockpos = p_77613_.below();
        VoxelShape voxelshape = p_77612_.getBlockState(blockpos).getCollisionShape(p_77612_, blockpos);
        return (double) blockpos.getY() + (voxelshape.isEmpty() ? 0.0D : voxelshape.max(Direction.Axis.Y));
    }

    protected boolean isAmphibious() {
        return false;
    }

    @Nullable
    protected Node findAcceptedNode(int p_164726_, int p_164727_, int p_164728_, int p_164729_, double p_164730_, Direction p_164731_, BlockPathTypes p_164732_) {
        Node node = null;
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        double d0 = this.getFloorLevel(blockpos$mutableblockpos.set(p_164726_, p_164727_, p_164728_));
        if (d0 - p_164730_ > this.getMobJumpHeight()) {
            return null;
        } else {
            BlockPathTypes blockpathtypes = this.getCachedBlockType(this.mob, p_164726_, p_164727_, p_164728_);
            float f = this.mob.getPathfindingMalus(blockpathtypes);
            double d1 = (double) this.vehicle.getBbWidth() / 2.0D;
            if (f >= 0.0F) {
                node = this.getNodeAndUpdateCostToMax(p_164726_, p_164727_, p_164728_, blockpathtypes, f);
            }
            if (doesBlockHavePartialCollision(p_164732_) && node != null && node.costMalus >= 0.0F && !this.canReachWithoutCollision(node)) {
                node = null;
            }
            if (blockpathtypes != BlockPathTypes.WALKABLE && (!this.isAmphibious() || blockpathtypes != BlockPathTypes.WATER)) {
                if ((node == null || node.costMalus < 0.0F) && p_164729_ > 0 && (blockpathtypes != BlockPathTypes.FENCE || this.canWalkOverFences()) && blockpathtypes != BlockPathTypes.UNPASSABLE_RAIL && blockpathtypes != BlockPathTypes.TRAPDOOR && blockpathtypes != BlockPathTypes.POWDER_SNOW) {
                    node = this.findAcceptedNode(p_164726_, p_164727_ + 1, p_164728_, p_164729_ - 1, p_164730_, p_164731_, p_164732_);
                    if (node != null && (node.type == BlockPathTypes.OPEN || node.type == BlockPathTypes.WALKABLE) && this.vehicle.getBbWidth() < 1.0F) {
                        double d2 = (double) (p_164726_ - p_164731_.getStepX()) + 0.5D;
                        double d3 = (double) (p_164728_ - p_164731_.getStepZ()) + 0.5D;
                        AABB aabb = new AABB(d2 - d1, this.getFloorLevel(blockpos$mutableblockpos.set(d2, (double) (p_164727_ + 1), d3)) + 0.001D, d3 - d1, d2 + d1, (double) this.vehicle.getBbHeight() + this.getFloorLevel(blockpos$mutableblockpos.set((double) node.x, (double) node.y, (double) node.z)) - 0.002D, d3 + d1);
                        if (this.hasCollisions(aabb)) {
                            node = null;
                        }
                    }
                }
                if (!this.isAmphibious() && blockpathtypes == BlockPathTypes.WATER && !this.canFloat()) {
                    if (this.getCachedBlockType(this.mob, p_164726_, p_164727_ - 1, p_164728_) != BlockPathTypes.WATER) {
                        return node;
                    }
                    while (p_164727_ > this.vehicle.level().getMinBuildHeight()) {
                        --p_164727_;
                        blockpathtypes = this.getCachedBlockType(this.mob, p_164726_, p_164727_, p_164728_);
                        if (blockpathtypes != BlockPathTypes.WATER) {
                            return node;
                        }
                        node = this.getNodeAndUpdateCostToMax(p_164726_, p_164727_, p_164728_, blockpathtypes, this.mob.getPathfindingMalus(blockpathtypes));
                    }
                }
                if (blockpathtypes == BlockPathTypes.OPEN) {
                    int j = 0;
                    int i = p_164727_;
                    while (blockpathtypes == BlockPathTypes.OPEN) {
                        --p_164727_;
                        if (p_164727_ < this.vehicle.level().getMinBuildHeight()) {
                            return this.getBlockedNode(p_164726_, i, p_164728_);
                        }
                        if (j++ >= this.vehicle.getMaxFallDistance()) {
                            return this.getBlockedNode(p_164726_, p_164727_, p_164728_);
                        }
                        blockpathtypes = this.getCachedBlockType(this.mob, p_164726_, p_164727_, p_164728_);
                        f = this.mob.getPathfindingMalus(blockpathtypes);
                        if (blockpathtypes != BlockPathTypes.OPEN && f >= 0.0F) {
                            node = this.getNodeAndUpdateCostToMax(p_164726_, p_164727_, p_164728_, blockpathtypes, f);
                            break;
                        }
                        if (f < 0.0F) {
                            return this.getBlockedNode(p_164726_, p_164727_, p_164728_);
                        }
                    }
                }
                if (doesBlockHavePartialCollision(blockpathtypes) && node == null) {
                    node = this.getNode(p_164726_, p_164727_, p_164728_);
                    node.closed = true;
                    node.type = blockpathtypes;
                    node.costMalus = blockpathtypes.getMalus();
                }
                return node;
            } else {
                return node;
            }
        }
    }

    private double getMobJumpHeight() {
        return Math.max(DEFAULT_MOB_JUMP_HEIGHT, (double) this.vehicle.getStepHeight());
    }

    private Node getNodeAndUpdateCostToMax(int p_230620_, int p_230621_, int p_230622_, BlockPathTypes p_230623_, float p_230624_) {
        Node node = this.getNode(p_230620_, p_230621_, p_230622_);
        node.type = p_230623_;
        node.costMalus = Math.max(node.costMalus, p_230624_);
        return node;
    }

    private Node getBlockedNode(int p_230628_, int p_230629_, int p_230630_) {
        Node node = this.getNode(p_230628_, p_230629_, p_230630_);
        node.type = BlockPathTypes.BLOCKED;
        node.costMalus = -1.0F;
        return node;
    }

    private boolean hasCollisions(AABB p_77635_) {
        return this.collisionCache.computeIfAbsent(p_77635_, (p_192973_) -> {
            return !this.level.noCollision(this.vehicle, p_77635_);
        });
    }

    public BlockPathTypes getBlockPathType(BlockGetter p_265141_, int p_265661_, int p_265757_, int p_265716_, Mob p_265398_) {
        // Water standoff: reject any node whose footprint sits within WATER_MARGIN
        // blocks of water, so routes keep clear of shorelines instead of driving in.
        // Done before the volume scan, a blocked node needs no further classifying.
        if (this.hasWaterWithinMargin(p_265141_, p_265661_, p_265757_, p_265716_)) {
            return BlockPathTypes.BLOCKED;
        }

        // Scans the vehicle's full W×H×D volume like vanilla, but bails out on the
        // first block that rejects the node (fence/rail/negative malus) instead of
        // classifying the entire volume first, a tank footprint is 100+ blocks, so
        // hitting a wall face early saves almost the whole scan.
        BlockPathTypes center = BlockPathTypes.BLOCKED;
        BlockPathTypes worst = BlockPathTypes.BLOCKED;
        float worstMalus = p_265398_.getPathfindingMalus(BlockPathTypes.BLOCKED);
        BlockPos mobPos = p_265398_.blockPosition();
        for (int i = 0; i < this.entityWidth; ++i) {
            for (int j = 0; j < this.entityHeight; ++j) {
                for (int k = 0; k < this.entityDepth; ++k) {
                    BlockPathTypes blockpathtypes = this.getBlockPathType(p_265141_, i + p_265661_, j + p_265757_, k + p_265716_);
                    blockpathtypes = this.evaluateBlockPathType(p_265141_, mobPos, blockpathtypes);
                    if (i == 0 && j == 0 && k == 0) {
                        center = blockpathtypes;
                    }
                    if (blockpathtypes == BlockPathTypes.FENCE || blockpathtypes == BlockPathTypes.UNPASSABLE_RAIL) {
                        return blockpathtypes;
                    }
                    float malus = p_265398_.getPathfindingMalus(blockpathtypes);
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

    // True if any block within WATER_MARGIN of the node footprint (horizontally, at
    // the driving level and one below to catch water under a shoreline ledge) is
    // water. The whole aggregate result is cached per node in pathTypesByPosCache,
    // so this scan runs at most once per unique node per search  bounded far below
    // the vanilla 26-neighbour scan this evaluator otherwise skips.
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

    protected BlockPathTypes evaluateBlockPathType(BlockGetter p_265305_, BlockPos p_265350_, BlockPathTypes p_265551_) {
        boolean flag = this.canPassDoors();
        if (p_265551_ == BlockPathTypes.DOOR_WOOD_CLOSED && this.canOpenDoors() && flag) {
            p_265551_ = BlockPathTypes.WALKABLE_DOOR;
        }
        if (p_265551_ == BlockPathTypes.DOOR_OPEN && !flag) {
            p_265551_ = BlockPathTypes.BLOCKED;
        }
        if (p_265551_ == BlockPathTypes.RAIL && !(p_265305_.getBlockState(p_265350_).getBlock() instanceof BaseRailBlock) && !(p_265305_.getBlockState(p_265350_.below()).getBlock() instanceof BaseRailBlock)) {
            p_265551_ = BlockPathTypes.UNPASSABLE_RAIL;
        }
        return p_265551_;
    }

    protected BlockPathTypes getBlockPathType(Mob p_77573_, BlockPos p_77574_) {
        return this.getCachedBlockType(p_77573_, p_77574_.getX(), p_77574_.getY(), p_77574_.getZ());
    }

    protected BlockPathTypes getCachedBlockType(Mob p_77568_, int p_77569_, int p_77570_, int p_77571_) {
        return this.pathTypesByPosCache.computeIfAbsent(BlockPos.asLong(p_77569_, p_77570_, p_77571_), (p_265015_) -> {
            return this.getBlockPathType(this.level, p_77569_, p_77570_, p_77571_, p_77568_);
        });
    }

    public BlockPathTypes getBlockPathType(BlockGetter p_77576_, int p_77577_, int p_77578_, int p_77579_) {
        // Same block classification as getBlockPathTypeStatic, minus vanilla's
        // 26-neighbour hazard scan (checkNeighbourBlocks): an armored vehicle
        // doesn't route around cactus, fire, or water borders, and that scan is
        // the single most expensive part of evaluating each block in the volume.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(p_77577_, p_77578_, p_77579_);
        BlockPathTypes blockpathtypes = getBlockPathTypeRaw(p_77576_, pos);
        if (blockpathtypes == BlockPathTypes.OPEN && p_77578_ >= p_77576_.getMinBuildHeight() + 1) {
            BlockPathTypes below = getBlockPathTypeRaw(p_77576_, pos.set(p_77577_, p_77578_ - 1, p_77579_));
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

    protected static BlockPathTypes getBlockPathTypeRaw(BlockGetter p_77644_, BlockPos p_77645_) {
        BlockState blockstate = p_77644_.getBlockState(p_77645_);
        BlockPathTypes type = blockstate.getBlockPathType(p_77644_, p_77645_, null);
        if (type != null) return type;
        Block block = blockstate.getBlock();
        if (blockstate.isAir()) {
            return BlockPathTypes.OPEN;
        } else if (!blockstate.is(BlockTags.TRAPDOORS) && !blockstate.is(Blocks.LILY_PAD) && !blockstate.is(Blocks.BIG_DRIPLEAF)) {
            if (blockstate.is(Blocks.POWDER_SNOW)) {
                return BlockPathTypes.POWDER_SNOW;
            } else if (!blockstate.is(Blocks.CACTUS) && !blockstate.is(Blocks.SWEET_BERRY_BUSH)) {
                if (blockstate.is(Blocks.HONEY_BLOCK)) {
                    return BlockPathTypes.STICKY_HONEY;
                } else if (blockstate.is(Blocks.COCOA)) {
                    return BlockPathTypes.COCOA;
                } else if (!blockstate.is(Blocks.WITHER_ROSE) && !blockstate.is(Blocks.POINTED_DRIPSTONE)) {
                    FluidState fluidstate = p_77644_.getFluidState(p_77645_);
                    BlockPathTypes nonLoggableFluidPathType = fluidstate.getBlockPathType(p_77644_, p_77645_, null, false);
                    if (nonLoggableFluidPathType != null) return nonLoggableFluidPathType;
                    if (fluidstate.is(FluidTags.LAVA)) {
                        return BlockPathTypes.LAVA;
                    } else if (isBurningBlock(blockstate)) {
                        return BlockPathTypes.DAMAGE_FIRE;
                    } else if (block instanceof DoorBlock) {
                        DoorBlock doorblock = (DoorBlock) block;
                        if (blockstate.getValue(DoorBlock.OPEN)) {
                            return BlockPathTypes.DOOR_OPEN;
                        } else {
                            return doorblock.type().canOpenByHand() ? BlockPathTypes.DOOR_WOOD_CLOSED : BlockPathTypes.DOOR_IRON_CLOSED;
                        }
                    } else if (block instanceof BaseRailBlock) {
                        return BlockPathTypes.RAIL;
                    } else if (block instanceof LeavesBlock) {
                        return BlockPathTypes.LEAVES;
                    } else if (!blockstate.is(BlockTags.FENCES) && !blockstate.is(BlockTags.WALLS) && (!(block instanceof FenceGateBlock) || blockstate.getValue(FenceGateBlock.OPEN))) {
                        if (!blockstate.isPathfindable(p_77644_, p_77645_, PathComputationType.LAND)) {
                            return BlockPathTypes.BLOCKED;
                        } else {
                            BlockPathTypes loggableFluidPathType = fluidstate.getBlockPathType(p_77644_, p_77645_, null, true);
                            if (loggableFluidPathType != null) return loggableFluidPathType;
                            return fluidstate.is(FluidTags.WATER) ? BlockPathTypes.WATER : BlockPathTypes.OPEN;
                        }
                    } else {
                        return BlockPathTypes.FENCE;
                    }
                } else {
                    return BlockPathTypes.DAMAGE_CAUTIOUS;
                }
            } else {
                return BlockPathTypes.DAMAGE_OTHER;
            }
        } else {
            return BlockPathTypes.TRAPDOOR;
        }
    }

    public static boolean isBurningBlock(BlockState p_77623_) {
        return p_77623_.is(BlockTags.FIRE) || p_77623_.is(Blocks.LAVA) || p_77623_.is(Blocks.MAGMA_BLOCK) || CampfireBlock.isLitCampfire(p_77623_) || p_77623_.is(Blocks.LAVA_CAULDRON);
    }
}