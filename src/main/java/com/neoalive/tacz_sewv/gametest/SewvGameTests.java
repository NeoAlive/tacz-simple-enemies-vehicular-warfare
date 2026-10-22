package com.neoalive.tacz_sewv.gametest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
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
import com.neoalive.tacz_sewv.invasion.InvasionHostility;
import com.neoalive.tacz_sewv.invasion.InvasionTags;
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

    /**
     * Invasion: a PMC of team A, stamped hostile to team B, acquires a survival player on team B.
     * Own batch: tests in a batch run side by side, and a neighbour's units are a distraction.
     */
    @GameTest(template = TEMPLATE, batch = "invasion_targeting", timeoutTicks = 200)
    public static void invasionPmcTargetsEnemyPlayer(GameTestHelper helper) {
        invasionPmcVsPlayer(helper, false);
    }

    /**
     * The reported bug: a PMC already engaged with another hostile never takes an enemy player.
     * SEM's catch-all target goal and the diplomacy goal shared priority 2, and an equal priority
     * never preempts a held target. An enemy player in reach must win over the RU it holds.
     */
    @GameTest(template = TEMPLATE, batch = "invasion_targeting_busy", timeoutTicks = 200)
    public static void invasionPmcSwitchesToEnemyPlayer(GameTestHelper helper) {
        invasionPmcVsPlayer(helper, true);
    }

    private static void invasionPmcVsPlayer(GameTestHelper helper, boolean busy) {
        Scoreboard board = helper.getLevel().getScoreboard();
        String suffix = Integer.toHexString(helper.getLevel().getRandom().nextInt(0xFFFF));
        PlayerTeam teamA = board.addPlayerTeam("sewvA" + suffix);
        PlayerTeam teamB = board.addPlayerTeam("sewvB" + suffix);

        // Everything inside the 3x3 template: the framework walls each test area in barriers.
        PmcUnitEntity pmc = spawnUnit(helper, ModEntities.PMCUNIT.get(), new BlockPos(0, 1, 0));
        pmc.setOwner(UUID.randomUUID());
        stampInvasion(pmc, teamA.getName(), teamB.getName());

        ServerPlayer[] player = new ServerPlayer[1];
        Runnable addPlayer = () -> {
            player[0] = survivalPlayer(helper, new BlockPos(2, 1, 2));
            board.addPlayerToTeam(player[0].getScoreboardName(), teamB);
            helper.assertTrue(InvasionHostility.isEnemy(pmc, player[0]),
                    "the player does not read as an invasion enemy");
        };
        Runnable cleanUp = () -> {
            if (player[0] != null) removePlayer(helper, player[0]);
            board.removePlayerTeam(teamA);
            board.removePlayerTeam(teamB);
        };
        if (busy) {
            // SEM's own target goal must be the one holding the lock, so let it acquire the RU.
            RUunitEntity ru = spawnUnit(helper, ModEntities.RUUNIT.get(), new BlockPos(0, 1, 2));
            ru.setNoAi(true);
            ru.setInvulnerable(true);
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(pmc.getTarget() == ru, "PMC has not locked the RU"))
                    .thenExecute(addPlayer)
                    .thenWaitUntil(() -> helper.assertTrue(pmc.getTarget() == player[0],
                            "PMC kept " + pmc.getTarget() + " over the enemy player"))
                    .thenExecute(cleanUp)
                    .thenSucceed();
            return;
        }
        addPlayer.run();
        helper.succeedWhen(() -> {
            helper.assertTrue(pmc.getTarget() == player[0], "PMC target is " + pmc.getTarget());
            cleanUp.run();
        });
    }

    private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }

    private static void stampInvasion(Entity entity, String team, String enemyTeam) {
        entity.getPersistentData().putString(InvasionTags.TEAM, team);
        InvasionHostility.stampEnemies(entity, List.of(enemyTeam));
    }

    /**
     * A survival player in {@code level.players()} (what player targeting scans), without a login.
     * GameTestHelper's mock is creative, which nothing targets, and a real login fails: SBW and TaCZ
     * send mod payloads that an unnegotiated embedded connection refuses. Its packet listener
     * drops every send instead.
     */
    private static ServerPlayer survivalPlayer(GameTestHelper helper, BlockPos rel) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "sewv-test-" + helper.getLevel().getRandom().nextInt(1000)), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player, cookie) {
            @Override
            public void send(Packet<?> packet) {
            }

            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            }
        };
        BlockPos at = helper.absolutePos(rel);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        player.setGameMode(GameType.SURVIVAL);
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    private static boolean hasDriveGoal(AbstractUnit unit) {
        for (WrappedGoal goal : unit.goalSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof DriveVehicleGoal) return true;
        }
        return false;
    }
}
