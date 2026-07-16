package com.neoalive.tacz_sewv.procedural.events;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.EmplacementSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

/**
 * An RU or US mortar battery sets up out of sight and shells the player's base.
 *
 * <p>This is the one thing the mortar rules make possible that nothing else does. A mortar
 * shoots ~770 blocks; its crew sees 96. Every other way of giving a crew a target needs
 * someone to have <em>seen</em> it — which is what the radio is for. A standing fire mission
 * on a BlockPos needs nobody to see anything, so an attacker can shell a place off a map
 * reference. That is exactly what artillery is, and it is why
 * {@link com.neoalive.tacz_sewv.bridge.FireMission} exists.
 *
 * <p>The shape of the encounter: shells start landing on your base from somewhere you can't
 * see. The battery works its mission for a rolled 30-60 s and then stands down off its tubes
 * for good — it is bounded by TIME rather than by ammunition, since a crew's issued supply is
 * unlimited. So you can ride it out; what you cannot do is make it stop. The crews and their
 * guards ({@code shellingGuards}) stay where they are as ordinary infantry afterwards, which
 * is the invitation to go and find out where the shells were coming from.
 */
public final class MortarShellingEvent extends DynamicEvent {

    public static final String ID = "mortar_shelling";

    /**
     * Comfortably inside the mortar's ~27-769 block envelope, and far enough out that the
     * battery is well past the 96 blocks its own crew — or yours — could spot it at.
     */
    private static final int MIN_DISTANCE = 120;
    private static final int MAX_DISTANCE = 260;

    /** Tubes are dispersed a little so one lucky counter-battery shot can't take the lot. */
    private static final int TUBE_SPACING = 5;
    private static final int GUARD_SPREAD = 12;

    public MortarShellingEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.SHELLING_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.SHELLING_FAILURE_MULTIPLIER.get();
    }

    @Override
    public int getMinDistance() {
        return MIN_DISTANCE;
    }

    @Override
    public int getMaxDistance() {
        return MAX_DISTANCE;
    }

    @Override
    public boolean canExecute(ServerLevel level, ServerPlayer player) {
        return SewvConfig.SHELLING_EVENTS_ENABLED.get() && baseOf(player, level) != null;
    }

    /**
     * The player's base, or null if they aren't at one.
     *
     * <p>"Base" is their respawn point: a bed or anchor is the one position the game already
     * knows the player chose as home, needs no block scanning to find, and can't be faked by
     * standing still in a cave. Requiring them to be near it is what keeps a barrage from
     * chasing someone across the map — the event should threaten what you built.
     */
    private static BlockPos baseOf(ServerPlayer player, ServerLevel level) {
        BlockPos respawn = player.getRespawnPosition();
        if (respawn == null) return null;
        // A bed in another dimension is not a base you can be shelled at.
        if (!level.dimension().equals(player.getRespawnDimension())) return null;

        int radius = SewvConfig.SHELLING_BASE_RADIUS.get();
        return player.blockPosition().distSqr(respawn) <= (double) radius * radius ? respawn : null;
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        BlockPos base = baseOf(player, level);
        if (base == null) return false; // wandered off between the roll and here
        if (!SpawnHelper.isValidSpawn(level, centerPos)) return false;

        // A battery is one side's, like a convoy. PMC is intentionally not a candidate:
        // these are the player's own units.
        TankSpawner.TankFaction faction = level.random.nextBoolean()
                ? TankSpawner.TankFaction.RU
                : TankSpawner.TankFaction.US;

        // One roll for the whole battery, not per tube: a battery fires its mission and
        // displaces together. Rolling per crew would trail the barrage off tube by tube,
        // which reads as the mortars breaking rather than the fire plan ending.
        FireMission mission = new FireMission(base, level.getGameTime() + rollDuration(level));

        boolean alongX = level.random.nextBoolean();
        int tubes = SewvConfig.SHELLING_MORTARS.get();
        int placed = 0;

        for (int i = 0; i < tubes; i++) {
            int offset = i * TUBE_SPACING;
            BlockPos tubePos = alongX ? centerPos.offset(offset, 0, 0) : centerPos.offset(0, 0, offset);
            tubePos = TankSpawner.adjustHeight(level, tubePos);
            if (EmplacementSpawner.spawn(level, tubePos, EmplacementSpawner.Emplacement.MORTAR,
                    faction, null, mission) != null) {
                placed++;
            }
        }

        // No tubes placed means no event: returning false lets SEM's escalating chance keep
        // climbing and retry, rather than burning the roll on a battery that isn't there.
        if (placed == 0) return false;

        for (int i = 0; i < SewvConfig.SHELLING_GUARDS.get(); i++) {
            spawnGuard(level, centerPos, faction);
        }
        return true;
    }

    /** How long this battery shells for, in game ticks. */
    private static int rollDuration(ServerLevel level) {
        int min = SewvConfig.SHELLING_DURATION_MIN_TICKS.get();
        int max = SewvConfig.SHELLING_DURATION_MAX_TICKS.get();
        // Tolerate a config with the bounds the wrong way round rather than throwing out of
        // an event tick; nextInt needs a positive span.
        if (max <= min) return min;
        return min + level.random.nextInt(max - min + 1);
    }

    private static void spawnGuard(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction) {
        AbstractUnit unit = faction == TankSpawner.TankFaction.RU
                ? new RUunitEntity(ModEntities.RUUNIT.get(), level)
                : new USunitEntity(ModEntities.USUNIT.get(), level);
        unit.setRole(UnitRole.DEFAULT);

        int x = anchor.getX() + level.random.nextInt(GUARD_SPREAD * 2 + 1) - GUARD_SPREAD;
        int z = anchor.getZ() + level.random.nextInt(GUARD_SPREAD * 2 + 1) - GUARD_SPREAD;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        unit.setPos(x + 0.5, y, z + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(unit.blockPosition()), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(unit);
    }
}
