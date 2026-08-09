package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.block.ContainerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.FoxholeBlock;
import com.neoalive.tacz_sewv.block.TrenchNetworks;
import com.neoalive.tacz_sewv.block.TrenchTracker;

/**
 * Shared foxhole structure placement for Combat Engineers — debug command and autonomous goal.
 */
public final class DigFoxholeSupport {

    public static final String TAG_HAS_DUG = "sewv:hasDugFoxhole";
    public static final ResourceLocation STRUCTURE_ID =
            new ResourceLocation(TaczSewv.MODID, "grass_trench_1");

    /** Autonomous dig refuses if any fortification is within this XZ radius (blocks). */
    public static final int CLEARANCE_RADIUS = 6;

    /**
     * How many blocks below standing surface the structure origin sits. Surface =
     * {@code blockPosition().below()}; one further step sinks the dig two blocks underground.
     */
    private static final int DIG_DEPTH = 2;

    /**
     * Proxy footprint for {@link ContainerBlock#canOpen}. Ravager dimensions (~2×2.2) give a
     * mid-size occlusion volume approximating the 2×4×8 foxhole template for this MVP gate.
     */
    private static final EntityType<?> GROUND_ELIGIBILITY_PROXY = EntityType.RAVAGER;

    private DigFoxholeSupport() {}

    public static boolean hasDug(Entity unit) {
        return unit.getPersistentData().getBoolean(TAG_HAS_DUG);
    }

    public static void markDug(Entity unit) {
        unit.getPersistentData().putBoolean(TAG_HAS_DUG, true);
    }

    /**
     * TODO: replace with a dedicated ground-eligibility class.
     * <p>{@link ContainerBlock#canOpen} returns {@code true} when the footprint volume is
     * <b>CLEAR</b> of occluding blocks (safe to deploy a vehicle). Dig eligibility
     * <b>inverts</b> that sense: eligible when {@code canOpen} is {@code false} (volume has
     * occluding coverage / occupied ground).
     */
    public static boolean isGroundEligible(ServerLevel level, BlockPos footing) {
        return !ContainerBlock.canOpen(level, footing, GROUND_ELIGIBILITY_PROXY, null);
    }

    /** True if a trench/foxhole network (or tracked cell) exists within {@code radius} of {@code pos}. */
    public static boolean hasNearbyFortification(ServerLevel level, BlockPos pos, int radius) {
        if (TrenchNetworks.get(level).findNearbyNetwork(pos, radius) != null) return true;
        int r = radius;
        for (BlockPos p : BlockPos.betweenClosed(
                pos.getX() - r, pos.getY() - 2, pos.getZ() - r,
                pos.getX() + r, pos.getY() + 2, pos.getZ() + r)) {
            if (TrenchNetworks.isTrackedCell(level.getBlockState(p))) return true;
        }
        return false;
    }

    /**
     * Place {@code grass_trench_1} sunk {@link #DIG_DEPTH} blocks under the surface footing.
     * Marks {@link #TAG_HAS_DUG} only on success. Refreshes {@link TrenchTracker} because
     * {@code placeInWorld} skips {@code setPlacedBy}.
     *
     * @return true if the template was placed
     */
    public static boolean place(ServerLevel level, AbstractUnit unit) {
        StructureTemplateManager manager = level.getStructureManager();
        StructureTemplate template = manager.get(STRUCTURE_ID).orElse(null);
        if (template == null) {
            template = manager.getOrCreate(STRUCTURE_ID);
        }
        if (template.getSize().getX() == 0) return false;

        BlockPos surface = unit.blockPosition().below();
        Vec3i size = template.getSize();
        // Centre XZ on the unit; sink origin so the dig reaches DIG_DEPTH under the surface.
        BlockPos origin = new BlockPos(
                surface.getX() - size.getX() / 2,
                surface.getY() - (DIG_DEPTH - 1),
                surface.getZ() - size.getZ() / 2);

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKeepLiquids(false);
        RandomSource random = level.getRandom();
        boolean ok = template.placeInWorld(level, origin, origin, settings, random, Block.UPDATE_ALL);
        if (!ok) return false;

        refreshFoxholeTopology(level, origin, size);
        markDug(unit);
        return true;
    }

    private static void refreshFoxholeTopology(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof FoxholeBlock) {
                        TrenchTracker.onTopologyChanged(level, pos);
                    }
                }
            }
        }
    }

    @Nullable
    public static AbstractUnit findNearestCombatEngineer(ServerLevel level, BlockPos near, double radius) {
        AbstractUnit best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class,
                new net.minecraft.world.phys.AABB(near).inflate(radius),
                DigFoxholeSupport::isCombatEngineer)) {
            double d = unit.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = unit;
            }
        }
        return best;
    }

    public static boolean isCombatEngineer(Entity entity) {
        return entity instanceof com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity
                || entity instanceof com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
    }
}
