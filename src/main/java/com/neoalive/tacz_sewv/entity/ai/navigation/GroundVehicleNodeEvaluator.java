package com.neoalive.tacz_sewv.entity.ai.navigation;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalPosture;

/**
 * Vanilla's walking pathfinder sized for the hull the crewman is driving, not for the
 * crewman. All the structural machinery — start/goal resolution, neighbor expansion, the
 * per-search caches, partial-collision handling — is inherited from
 * {@link WalkNodeEvaluator}; only what actually differs for a vehicle is overridden:
 *
 * <ul>
 * <li><b>Footprint.</b> {@link #prepare} swaps the mob's dimensions for the hull's, so the
 *     whole inherited machinery searches for tank-sized clearance.</li>
 * <li><b>Water.</b> Any water at all is BLOCKED for a non-amphibious hull — no fording, no
 *     already-wet exception. Dry nodes next to water take a soft margin cost, never a hard
 *     block, so the route still avoids hugging a shoreline it isn't crossing.</li>
 * <li><b>Slope.</b> Accepted nodes whose unfloored rise approaches the hull's
 *     {@code maxUpStep} take a smoothstep cost; over that limit they are rejected.</li>
 * <li><b>Flat preference.</b> Local heightmap grade (tilt SBW would apply) adds a soft
 *     {@link GroundMobility#gradeMalus} — preference only, never rejected.</li>
 * <li><b>Road preference.</b> Nodes whose footing is not in {@code #tacz_sewv:preferred_roads}
 *     take an {@link #OFF_ROAD_PENALTY} so a parallel dirt path / gravel / cobble wins
 *     without forbidding off-road cuts.</li>
 * <li><b>Peer spacing.</b> Nodes near wrecks or allied hulls take a soft
 *     {@link VehiclePeerSpacing#PATH_PENALTY} that falls off with distance — preference only,
 *     never {@code BLOCKED}, so a narrow gap stays reachable.</li>
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

    /** Extra path cost per off-road node; roads stay at zero so parallel roads win. */
    private static final float OFF_ROAD_PENALTY = 2.0F;

    public static final TagKey<Block> PREFERRED_ROADS = TagKey.create(
            Registries.BLOCK, new ResourceLocation(TaczSewv.MODID, "preferred_roads"));

    private boolean amphibious;
    private float maxUpStep = 1.0F;
    private int gradeHalfSpan = 1;
    private boolean loggedDeepWaterBlockThisSearch;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    /**
     * Peer hull centres + soft radii, latched once per {@link #prepare}. Empty when not driving.
     * Parallel arrays: {@code peerX[i]}, {@code peerZ[i]}, soft reach {@code peerSoft[i]}.
     */
    private double[] peerX = EMPTY;
    private double[] peerZ = EMPTY;
    private double[] peerSoft = EMPTY;
    private static final double[] EMPTY = new double[0];

    /** Per-search cache of "does this node's footprint contain a fellable tree cell", keyed by
     * {@link BlockPos#asLong}. Populated once, in {@link #getBlockPathType(BlockGetter, int, int,
     * int, Mob)}'s classification pass, which already visits every footprint cell; {@link
     * #footprintHasFellableTree} then just reads it instead of re-walking the same cells a
     * second time. Byte-valued (0/1) with -1 as "no entry" so a miss is a real sentinel, not a
     * boxed null — vanilla always classifies a node before accepting it, so a miss should only
     * happen on the rare early-return paths in that classification loop. */
    private final Long2ByteOpenHashMap footprintTreeCache = newFootprintTreeCache();

    /** Per-search {@link GroundMobility#localGrade} cache, keyed by column ({@code x, 0, z}).
     * Adjacent accepted nodes often share the same grade sample; without this each node pays
     * five heightmap reads. */
    private final Long2DoubleOpenHashMap gradeCache = newGradeCache();

    /** Per-search {@link #nearDeepWater} cache, keyed by node origin. Byte 0/1, -1 = miss. */
    private final Long2ByteOpenHashMap nearDeepWaterCache = newNearDeepWaterCache();

    private static Long2ByteOpenHashMap newFootprintTreeCache() {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        cache.defaultReturnValue((byte) -1);
        return cache;
    }

    private static Long2DoubleOpenHashMap newGradeCache() {
        Long2DoubleOpenHashMap cache = new Long2DoubleOpenHashMap();
        cache.defaultReturnValue(Double.NEGATIVE_INFINITY);
        return cache;
    }

    private static Long2ByteOpenHashMap newNearDeepWaterCache() {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        cache.defaultReturnValue((byte) -1);
        return cache;
    }

    // ponytail: vanilla neighbor expansion still reads step/jump/fall off this.mob (the
    // crewman). Hull maxUpStep is applied as extra cost / reject in findAcceptedNode.
    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.amphibious = false;
        this.maxUpStep = 1.0F;
        this.gradeHalfSpan = 1;
        this.loggedDeepWaterBlockThisSearch = false;
        this.peerX = EMPTY;
        this.peerZ = EMPTY;
        this.peerSoft = EMPTY;
        this.footprintTreeCache.clear();
        this.gradeCache.clear();
        this.nearDeepWaterCache.clear();
        if (mob.getVehicle() instanceof VehicleEntity vehicle) {
            this.amphibious = GroundMobility.isAmphibious(vehicle);
            this.maxUpStep = GroundMobility.maxUpStepOf(vehicle);
            this.gradeHalfSpan = Math.max(1, Mth.floor(vehicle.getBbWidth() * 0.5F));
            this.entityWidth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            this.entityHeight = Mth.floor(vehicle.getBbHeight() + 1.0F);
            this.entityDepth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            if (mob instanceof AbstractUnit unit) {
                latchPeers(vehicle, unit);
            }
            if (SewvDiag.groundPathingVerbose()) {
                SewvDiag.water("prepare vehicle={}#{} amphibious={} maxUpStep={} size={}x{}x{} pos={}",
                        vehicle.getName().getString(), vehicle.getId(), this.amphibious,
                        this.maxUpStep, this.entityWidth, this.entityHeight, this.entityDepth,
                        vehicle.blockPosition());
            }
        }
    }

    /** One entity scan per path search — never per node. */
    private void latchPeers(VehicleEntity self, AbstractUnit unit) {
        double half = self.getBbWidth() * 0.5;
        double reach = VehiclePeerSpacing.SOFT_DISTANCE + half + 4.0;
        AABB search = self.getBoundingBox().inflate(reach, 2.0, reach);
        List<VehicleEntity> found = unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                v -> VehiclePeerSpacing.isPeer(self, unit, v));
        if (found.isEmpty()) return;
        int n = found.size();
        this.peerX = new double[n];
        this.peerZ = new double[n];
        this.peerSoft = new double[n];
        for (int i = 0; i < n; i++) {
            VehicleEntity other = found.get(i);
            this.peerX[i] = other.getX();
            this.peerZ[i] = other.getZ();
            this.peerSoft[i] = other.getBbWidth() * 0.5 + VehiclePeerSpacing.SOFT_DISTANCE;
        }
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        int depth = footprintWaterDepth(level, x, y, z);
        if (GroundMobility.waterBlocked(depth, this.amphibious)) {
            if (SewvDiag.groundPathingVerbose() && !this.loggedDeepWaterBlockThisSearch) {
                this.loggedDeepWaterBlockThisSearch = true;
                SewvDiag.water("water BLOCKED mob={}#{} node={},{},{} depth={} amphibious={}",
                        mob.getClass().getSimpleName(), mob.getId(),
                        x, y, z, depth, this.amphibious);
            }
            return BlockPathTypes.BLOCKED;
        }

        // Read once per call, not once per cell — three config/registry lookups repeated over a
        // 100+ cell footprint would itself be needless waste.
        boolean treeFellingActive = EnhancedFallingTreesCompat.available() && SewvConfig.VEHICLE_TREE_FELLING_ENABLED.get();
        boolean footprintHasTree = false;

        BlockPathTypes center = BlockPathTypes.BLOCKED;
        BlockPathTypes worst = BlockPathTypes.BLOCKED;
        float worstMalus = mob.getPathfindingMalus(BlockPathTypes.BLOCKED);
        BlockPos mobPos = mob.blockPosition();
        for (int i = 0; i < this.entityWidth; ++i) {
            for (int j = 0; j < this.entityHeight; ++j) {
                for (int k = 0; k < this.entityDepth; ++k) {
                    BlockPathTypes blockpathtypes = this.getBlockPathType(level, i + x, j + y, k + z);
                    // Fordable water is walkable; cost is applied in findAcceptedNode.
                    if (blockpathtypes == BlockPathTypes.WATER) {
                        blockpathtypes = BlockPathTypes.WALKABLE;
                    }
                    // A fellable tree log is walkable too — the vehicle drives through and fells
                    // it; the path-preference cost lives in findAcceptedNode, same split as
                    // fording above. Gated on available() first so an absent Enhanced Falling
                    // Trees never pays for the extra state read and a solid log stays BLOCKED
                    // exactly as before this compat existed. Also gated on the config toggle so
                    // disabling tree felling actually removes the pathfinding cost, not just the
                    // felling action — see the same gate in findAcceptedNode below.
                    //
                    // Uses the vanilla #minecraft:logs TAG here, not EnhancedFallingTreesFeller.
                    // isFellable() — that call is a linear scan over EFT's tree registry with no
                    // caching, and this runs per footprint cell per candidate node, i.e. thousands
                    // of times per pathfind in a forest. It was over half of total server tick
                    // time. The tag is an O(1) heuristic ("probably a log"); the exact,
                    // registry-accurate check still gates the real felling in TreeFellingSupport,
                    // so a false positive here just costs a path through a trunk that turns out
                    // not to be felled — recoverable like any other blocked-contact case — and a
                    // false negative just routes around it like before this compat existed.
                    //
                    // Leaves are deliberately NOT remapped: soft-walking the canopy is what let
                    // hulls climb trees through leaf collision. Only the trunk is drive-through.
                    //
                    // Checked unconditionally (not gated on BLOCKED) so this single pass also
                    // answers footprintHasFellableTree's question for the whole footprint — that
                    // used to be a second, separate walk over these same cells in
                    // findAcceptedNode; the result is cached below instead.
                    if (treeFellingActive) {
                        BlockState cellState = level.getBlockState(this.probe.set(i + x, j + y, k + z));
                        if (cellState.is(BlockTags.LOGS)) {
                            footprintHasTree = true;
                            if (blockpathtypes == BlockPathTypes.BLOCKED) {
                                blockpathtypes = BlockPathTypes.WALKABLE;
                            }
                        }
                    }
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
        if (treeFellingActive) {
            this.footprintTreeCache.put(BlockPos.asLong(x, y, z), (byte) (footprintHasTree ? 1 : 0));
        }
        return center == BlockPathTypes.OPEN && worstMalus == 0.0F && this.entityWidth <= 1 ? BlockPathTypes.OPEN : worst;
    }

    private int footprintWaterDepth(BlockGetter level, int x, int y, int z) {
        int max = 0;
        int w = Math.max(1, this.entityWidth);
        int d = Math.max(1, this.entityDepth);
        for (int i = 0; i < w; i++) {
            for (int k = 0; k < d; k++) {
                max = Math.max(max, GroundMobility.waterDepth(level, this.probe, x + i, y, z + k));
            }
        }
        return max;
    }

    private boolean nearDeepWater(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte cached = this.nearDeepWaterCache.get(key);
        if (cached != this.nearDeepWaterCache.defaultReturnValue()) return cached != 0;

        int margin = GroundMobility.DEEP_WATER_MARGIN;
        int minX = x - margin;
        int maxX = x + Math.max(1, this.entityWidth) - 1 + margin;
        int minZ = z - margin;
        int maxZ = z + Math.max(1, this.entityDepth) - 1 + margin;
        boolean near = false;
        scan:
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (GroundMobility.waterDepth(this.level, this.probe, cx, y, cz) > 0) {
                    near = true;
                    break scan;
                }
            }
        }
        this.nearDeepWaterCache.put(key, (byte) (near ? 1 : 0));
        return near;
    }

    private double cachedLocalGrade(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        double cached = this.gradeCache.get(key);
        if (cached != this.gradeCache.defaultReturnValue()) return cached;
        double grade = GroundMobility.localGrade(
                this.mob != null ? this.mob.level() : this.level, x, z, this.gradeHalfSpan);
        this.gradeCache.put(key, grade);
        return grade;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel,
                                    Direction direction, BlockPathTypes pathTypes) {
        Node node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathTypes);
        if (node == null) return null;

        BlockState footing = this.level.getBlockState(this.probe.set(node.x, node.y - 1, node.z));
        if (!footing.is(PREFERRED_ROADS)) {
            node.costMalus += OFF_ROAD_PENALTY;
        }
        node.costMalus += peerSpacingMalus(node);
        if (this.mob != null && this.mob.level() instanceof net.minecraft.server.level.ServerLevel server) {
            double cx = node.x + this.entityWidth * 0.5;
            double cz = node.z + this.entityDepth * 0.5;
            node.costMalus += TacticalPosture.coverPathMalus(this.mob.getId(), cx, cz, server);
        }

        VoxelShape shape = footing.getCollisionShape(this.level, this.probe);
        double top = shape.isEmpty() ? node.y : (node.y - 1) + shape.max(Direction.Axis.Y);
        float slope = GroundMobility.slopeMalus(top - nodeFloorLevel, this.maxUpStep);
        if (Float.isInfinite(slope)) return null;
        node.costMalus += slope;

        node.costMalus += GroundMobility.gradeMalus(cachedLocalGrade(node.x, node.z));

        int depth = footprintWaterDepth(this.level, node.x, node.y, node.z);
        float ford = GroundMobility.fordMalus(depth, this.amphibious);
        if (Float.isInfinite(ford)) return null;
        node.costMalus += ford;
        // Amphibious / already-wet nodes never pay the shoreline soft margin, so skip the
        // (W+6)×(D+6) water scan entirely in those cases.
        if (depth <= 0 && !this.amphibious && nearDeepWater(node.x, node.y, node.z)) {
            node.costMalus += GroundMobility.DEEP_MARGIN_PENALTY;
        }

        // Unconditional rescan — by this point the classification pass above has already
        // remapped every fellable tree cell to WALKABLE, so gating this on "still BLOCKED" the
        // way the classification pass does would silently never fire. Still gated on the config
        // toggle: with felling disabled, the classification pass above never remapped anything to
        // WALKABLE in the first place, so this rescan would find nothing anyway — checking the
        // flag here too skips the (otherwise pointless) footprint walk instead of paying for it.
        if (EnhancedFallingTreesCompat.available() && SewvConfig.VEHICLE_TREE_FELLING_ENABLED.get()
                && footprintHasFellableTree(this.level, node.x, node.y, node.z)) {
            node.costMalus += SewvConfig.VEHICLE_TREE_PATH_MALUS.get();
        }
        return node;
    }

    /** Reads {@link #footprintTreeCache}, filled in by {@link #getBlockPathType(BlockGetter,
     * int, int, int, Mob)}'s classification pass for this exact node — same footprint, same
     * Vanilla always classifies a node before {@code findAcceptedNode} can accept it, so a cache
     * miss should only happen on that classification loop's rare early-return paths; falling
     * back to a direct scan there means correctness never depends on that ordering holding. */
    private boolean footprintHasFellableTree(BlockGetter level, int x, int y, int z) {
        byte cached = this.footprintTreeCache.get(BlockPos.asLong(x, y, z));
        if (cached != this.footprintTreeCache.defaultReturnValue()) return cached != 0;
        return scanFootprintForFellableTree(level, x, y, z);
    }

    /** Same footprint shape as {@link #footprintWaterDepth}, but 3D (a tree log can sit at any
     * height within the hull's box, not just at foot level) and boolean (a preference cost, not
     * a depth to scale it by). Only reached as a fallback — see {@link #footprintHasFellableTree}. */
    private boolean scanFootprintForFellableTree(BlockGetter level, int x, int y, int z) {
        int w = Math.max(1, this.entityWidth);
        int h = Math.max(1, this.entityHeight);
        int d = Math.max(1, this.entityDepth);
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                for (int k = 0; k < d; k++) {
                    BlockState state = level.getBlockState(this.probe.set(x + i, y + j, z + k));
                    if (state.is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private float peerSpacingMalus(Node node) {
        if (this.peerX.length == 0) return 0.0F;
        double cx = node.x + this.entityWidth * 0.5;
        double cz = node.z + this.entityDepth * 0.5;
        float malus = 0.0F;
        for (int i = 0; i < this.peerX.length; i++) {
            double dx = cx - this.peerX[i];
            double dz = cz - this.peerZ[i];
            double dist = Math.sqrt(dx * dx + dz * dz);
            double soft = this.peerSoft[i];
            if (dist >= soft) continue;
            malus += VehiclePeerSpacing.PATH_PENALTY * (float) (1.0 - dist / soft);
        }
        return malus;
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
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
