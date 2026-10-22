package com.neoalive.tacz_sewv.gametest;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesFeller;
import com.neoalive.tacz_sewv.crew.UnitInventory;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveVehicleGoal;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * NeoForge port: in-world checks for the seams the headless self-checks cannot reach — SEM entities,
 * SBW hulls and this mod's mixins running together. Dev only: registered when the tacz_sewv gametest
 * namespace is enabled (the run configs do). Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(TaczSewv.MODID)
@PrefixGameTestTemplate(false)
public class SewvGameTests {

    private static final String TEMPLATE = "gametest_empty";
    /** A common SBW hull with a driver seat, a turret seat and rear seats. */
    private static final String HULL = "superbwarfare:bmp_2";

    private static <T extends AbstractUnit> T spawnUnit(GameTestHelper helper, EntityType<T> type, BlockPos rel) {
        T unit = helper.spawn(type, rel);
        unit.finalizeSpawn(helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(unit.blockPosition()),
                MobSpawnType.MOB_SUMMONED, null);
        return unit;
    }

    /** The PMC-only inventory rule: on NeoForge RU/US answer the capability with a 6-slot wrapper. */
    @GameTest(template = TEMPLATE)
    public static void unitInventoryIsPmcOnly(GameTestHelper helper) {
        RUunitEntity ru = spawnUnit(helper, ModEntities.RUUNIT.get(), new BlockPos(0, 1, 0));
        PmcUnitEntity pmc = spawnUnit(helper, ModEntities.PMCUNIT.get(), new BlockPos(1, 1, 0));
        helper.assertTrue(UnitInventory.of(ru) == null, "an RU unit must read as having no inventory");
        IItemHandler pmcInventory = UnitInventory.of(pmc);
        helper.assertTrue(pmcInventory != null && pmcInventory.getSlots() == 18,
                "a PMC must expose SEM's 18-slot inventory");
        helper.succeed();
    }

    /** MixinAbstractUnit: a same-faction setTarget is cancelled at the source. */
    @GameTest(template = TEMPLATE)
    public static void sameFactionTargetVetoed(GameTestHelper helper) {
        RUunitEntity a = spawnUnit(helper, ModEntities.RUUNIT.get(), new BlockPos(0, 1, 0));
        RUunitEntity b = spawnUnit(helper, ModEntities.RUUNIT.get(), new BlockPos(2, 1, 0));
        a.setTarget(b);
        helper.assertTrue(a.getTarget() == null, "an RU unit targeted another RU unit");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void ruCrewMountsAndDrives(GameTestHelper helper) {
        crewMountsAndDrives(helper, TankFaction.RU);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void usCrewMountsAndDrives(GameTestHelper helper) {
        crewMountsAndDrives(helper, TankFaction.US);
    }

    /** TankSpawner end to end: SBW hull spawned, a crew in every seat, the drive goal on the driver. */
    private static void crewMountsAndDrives(GameTestHelper helper, TankFaction faction) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        VehicleEntity hull = TankSpawner.spawnGroundWithCrewFromPoolAt(
                helper.getLevel(), at, faction, null, HULL, List.of(HULL));
        helper.assertTrue(hull != null, "TankSpawner did not spawn " + HULL + " for " + faction);
        helper.succeedWhen(() -> {
            List<Entity> crew = hull.getPassengers();
            helper.assertTrue(!crew.isEmpty(), "no crew mounted");
            for (Entity e : crew) {
                helper.assertTrue(e instanceof AbstractUnit, "a non-unit passenger: " + e);
            }
            helper.assertTrue(hull.getFirstPassenger() instanceof AbstractUnit driver && hasDriveGoal(driver),
                    "the driver has no DriveVehicleGoal");
        });
    }

    /**
     * Enhanced Falling Trees: a hull fells a whole tree through the feller — every gathered block goes
     * to air and EFT's decorative falling-tree entity is spawned. Passes trivially when EFT is absent
     * (run with -PwithEnhancedFallingTrees to exercise it).
     */
    @GameTest(template = TEMPLATE)
    public static void eftFellsTree(GameTestHelper helper) {
        if (!EnhancedFallingTreesCompat.present()) {
            helper.succeed();
            return;
        }
        BlockPos base = new BlockPos(2, 1, 2);
        for (int y = 0; y < 5; y++) {
            helper.setBlock(base.above(y), Blocks.OAK_LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 3; y <= 5; y++) {
                    BlockPos p = base.offset(dx, y, dz);
                    if (dx == 0 && dz == 0 && y < 5) continue;
                    helper.setBlock(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
                }
            }
        }
        Entity owner = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(0, 1, 2));
        boolean felled = EnhancedFallingTreesFeller.tryFell(helper.getLevel(), helper.absolutePos(base), owner);
        helper.assertTrue(felled, "the feller did not fell an oak");
        helper.assertBlockPresent(Blocks.AIR, base.above(2));
        AABB around = new AABB(helper.absolutePos(base)).inflate(4);
        helper.assertTrue(!helper.getLevel().getEntitiesOfClass(Entity.class, around,
                        e -> EnhancedFallingTreesCompat.MODID.equals(
                                BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace())).isEmpty(),
                "no EFT falling-tree entity was spawned");
        helper.succeed();
    }

    private static boolean hasDriveGoal(AbstractUnit unit) {
        for (WrappedGoal goal : unit.goalSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof DriveVehicleGoal) return true;
        }
        return false;
    }
}
