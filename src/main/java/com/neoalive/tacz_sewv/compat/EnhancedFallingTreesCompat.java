package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.logging.LogUtils;
import me.adda.enhanced_falling_trees.api.TreeRegistry;
import me.adda.enhanced_falling_trees.api.TreeType;
import me.adda.enhanced_falling_trees.entity.TreeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * Softcompat for Enhanced Falling Trees: lets a ground vehicle fell a whole tree it touches
 * instead of treating every trunk as a wall.
 *
 * <p>No Enhanced Falling Trees class is referenced from anywhere else in this mod — this is the
 * only file that imports {@code me.adda.enhanced_falling_trees.*}, and every one of those
 * imports is public API ({@link TreeRegistry}, {@link TreeType}, {@link TreeEntity}). Deliberately
 * never touches {@code EntityRegistry.TREE} (an Architectury {@code RegistrySupplier}) — resolving
 * that field's declared type would pull Architectury onto this mod's compile classpath for
 * nothing; {@link ForgeRegistries#ENTITY_TYPES} answers the same question without it.
 */
public final class EnhancedFallingTreesCompat {

    public static final String MODID = "efallingtrees";

    private static final ResourceLocation TREE_ENTITY_ID = new ResourceLocation(MODID, "tree");

    /** Vanilla dark oak trees generate 2x2 for their whole height; a coincidence between two
     * ordinary 1x1 trunks essentially never repeats for this many consecutive levels. */
    private static final int GIANT_TRUNK_CHECK_HEIGHT = 4;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Only latch {@code true}; an early false during registry bootstrap must not stick forever. */
    private static boolean resolvedPresent;
    private static boolean available;

    private EnhancedFallingTreesCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean available() {
        resolve();
        return available;
    }

    private static void resolve() {
        if (resolvedPresent && available) return;
        if (!present()) {
            resolvedPresent = true;
            available = false;
            return;
        }
        available = ForgeRegistries.ENTITY_TYPES.containsKey(TREE_ENTITY_ID);
        if (available) {
            resolvedPresent = true;
        }
    }

    /**
     * True when {@code state} is a registered tree base block (respects the user's own Enhanced
     * Falling Trees filter config — no separate hardcoded log-tag check here) and is not part of
     * a multi-block trunk this mod exempts. Safe to call without checking {@link #available()}
     * first, though hot callers should still gate on it to avoid the extra work entirely.
     */
    public static boolean isFellable(BlockGetter level, BlockPos pos, BlockState state) {
        if (!available()) return false;
        try {
            if (TreeRegistry.getTreeType(state).isEmpty()) return false;
            if (SewvConfig.VEHICLE_TREE_FELLING_EXEMPT_GIANT_TRUNKS.get() && isGiantTrunk(level, pos, state)) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            WarnOnce.warn(LOGGER, "isFellable", "Enhanced Falling Trees fellable check failed", t);
            return false;
        }
    }

    /**
     * True for any vanilla leaves block, regardless of the EFT registry. Steering/pathing treat
     * this the same as {@link #isFellable} — a canopy is usually what a hull's probe or footprint
     * actually touches first (it is wider than the trunk), so without this, leaves alone read as
     * an ordinary hard wall and a hull is steered around the whole tree before ever reaching a
     * trunk it could fell. This is why a wide, low canopy on a short trunk (acacia is the
     * clearest vanilla example) could look "never detected" — the vehicle never got close enough
     * to touch the log at all. No registry lookup needed: leaves carry no felling logic of their
     * own, they just get cleared along with the tree once the trunk is actually reached.
     */
    public static boolean isFoliage(BlockState state) {
        if (!available()) return false;
        return state.is(BlockTags.LEAVES);
    }

    /**
     * Fells the whole tree at {@code pos}: gathers every log+leaf block via the tree's own
     * {@link TreeType#blockGatheringAlgorithm}, spawns a decorative {@link TreeEntity} that falls
     * away from {@code owner} (the vehicle), then clears every gathered block to air. Re-checks
     * {@code pos} itself so a second scan that finds an already-felled (now air) tree is a cheap
     * no-op rather than a crash. Returns {@code true} iff a tree was actually felled.
     *
     * <p>This replicates {@code TreeEntity.destroyTree} rather than calling it: that entry point
     * is {@code Player}-typed for tool-durability/food-exhaustion/stat side effects that make no
     * sense for an NPC-crewed vehicle, none of which are needed here.
     */
    public static boolean tryFell(Level level, BlockPos pos, Entity owner) {
        if (!available()) return false;
        try {
            BlockState state = level.getBlockState(pos);
            if (!isFellable(level, pos, state)) return false;
            Optional<TreeType> treeTypeOpt = TreeRegistry.getTreeType(state);
            if (treeTypeOpt.isEmpty()) return false;
            TreeType treeType = treeTypeOpt.get();

            Set<BlockPos> blockPosList = treeType.blockGatheringAlgorithm(pos, level);
            if (blockPosList.isEmpty()) return false;

            EntityType<?> treeEntityType = ForgeRegistries.ENTITY_TYPES.getValue(TREE_ENTITY_ID);
            if (treeEntityType == null) return false;

            TreeEntity treeEntity = new TreeEntity(treeEntityType, level);
            treeEntity.setPos(pos.getCenter().add(0, -0.5, 0));
            treeEntity.setData(blockPosList, pos, treeType, owner, ItemStack.EMPTY);
            level.addFreshEntity(treeEntity);

            // Same two-pass clear as EFT's own destroyTree: remove every block first (deferred
            // shape updates via updateLimit=0), then recompute neighbor shapes once the whole
            // tree is actually gone, so a fence touching multiple tree blocks never sees a
            // half-felled tree mid-update.
            BlockState airState = Blocks.AIR.defaultBlockState();
            List<BlockPos> removedPositions = new ArrayList<>(blockPosList.size());
            List<BlockState> removedOldStates = new ArrayList<>(blockPosList.size());
            for (BlockPos blockPos : blockPosList) {
                removedOldStates.add(level.getBlockState(blockPos));
                removedPositions.add(blockPos);
                level.setBlock(blockPos, airState, Block.UPDATE_ALL, 0);
            }
            int neighborUpdateFlags = Block.UPDATE_ALL & -34;
            for (int i = 0; i < removedPositions.size(); i++) {
                BlockPos blockPos = removedPositions.get(i);
                BlockState oldState = removedOldStates.get(i);
                oldState.updateIndirectNeighbourShapes(level, blockPos, neighborUpdateFlags, 511);
                airState.updateNeighbourShapes(level, blockPos, neighborUpdateFlags, 511);
                airState.updateIndirectNeighbourShapes(level, blockPos, neighborUpdateFlags, 511);
            }
            for (Map.Entry<BlockPos, BlockState> entry : treeEntity.getBlocks().entrySet()) {
                level.sendBlockUpdated(entry.getKey().offset(pos), entry.getValue(), airState, 3);
            }
            return true;
        } catch (Throwable t) {
            WarnOnce.warn(LOGGER, "tryFell", "Enhanced Falling Trees tree fell failed", t);
            return false;
        }
    }

    /**
     * Tests the 4 candidate 2x2 squares containing {@code pos} as one corner, for
     * {@link #GIANT_TRUNK_CHECK_HEIGHT} consecutive Y levels, for the same {@link Block} as
     * {@code state}. A single-level match is not enough evidence — two ordinary trunks sprouting
     * diagonally adjacent in a dense forest can coincidentally match at one Y — so this requires
     * several consecutive levels, which only a real multi-block trunk sustains.
     */
    private static boolean isGiantTrunk(BlockGetter level, BlockPos pos, BlockState state) {
        Block log = state.getBlock();
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                if (squareMatches(level, pos, cx, cz, log)) return true;
            }
        }
        return false;
    }

    private static boolean squareMatches(BlockGetter level, BlockPos pos, int cx, int cz, Block log) {
        for (int dy = 0; dy < GIANT_TRUNK_CHECK_HEIGHT; dy++) {
            if (!cornerMatches(level, pos, cx, dy, cz, log)
                    || !cornerMatches(level, pos, cx + 1, dy, cz, log)
                    || !cornerMatches(level, pos, cx, dy, cz + 1, log)
                    || !cornerMatches(level, pos, cx + 1, dy, cz + 1, log)) {
                return false;
            }
        }
        return true;
    }

    private static boolean cornerMatches(BlockGetter level, BlockPos pos, int dx, int dy, int dz, Block log) {
        return level.getBlockState(pos.offset(dx, dy, dz)).is(log);
    }
}
