package com.neoalive.tacz_sewv.procedural.events;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.WaterSupport;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;

/**
 * Two flotillas fighting it out offshore: four ships a side, drawn from the faction ship pools.
 *
 * <p><b>This is the one event that must not use {@code SpawnHelper.isValidSpawn}</b>, SEM's
 * universal placement gate, because that gate wants a solid block to stand on — open water fails
 * it by definition and the event would never fire once. Its place is taken by two checks of this
 * event's own: the biome has to be a fightable ocean, and the column has to be navigable water
 * ({@link WaterSupport#navigable}), which is the same test the ship AI's own pathfinding uses.
 *
 * <p>Biomes are gated to the temperate and cold oceans, deep or shallow. Frozen ocean is excluded
 * because a surface of ice is not water to sail on, and everything that is not tagged
 * {@code IS_OCEAN} — swamps included — is out already by construction rather than by a list.
 *
 * <p>Four hulls a side rather than the one or two a ground event fields: SuperbWarfare's boats are
 * lightly armoured and trade fire in the open with nothing to take cover behind, so a pair would
 * be over before the player got there.
 */
public final class NavalBattleEvent extends DynamicEvent {

    public static final String ID = "naval_battle";

    /** Far enough to be a battle you sail out to, near enough to hear. */
    private static final int MIN_DISTANCE = 90;
    private static final int MAX_DISTANCE = 190;

    /** The two lines form up this far apart, and the hulls in each are spaced along it. */
    private static final int SEPARATION = 60;
    private static final int SHIP_SPACING = 16;

    public NavalBattleEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.NAVAL_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.NAVAL_FAILURE_MULTIPLIER.get();
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
        return SewvConfig.NAVAL_EVENTS_ENABLED.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        if (!isFightableOcean(level, centerPos)) return false;
        if (!WaterSupport.navigable(level, centerPos.getX(), centerPos.getZ())) return false;
        if (!TankSpawner.hasSpawnableShip(level, TankSpawner.TankFaction.RU)
                || !TankSpawner.hasSpawnableShip(level, TankSpawner.TankFaction.US)) {
            return false;
        }

        boolean alongX = level.random.nextBoolean();
        int perSide = SewvConfig.NAVAL_SHIPS_PER_SIDE.get();

        int ru = deployLine(level, centerPos, TankSpawner.TankFaction.RU, alongX, 1, perSide);
        int us = deployLine(level, centerPos, TankSpawner.TankFaction.US, alongX, -1, perSide);

        // A one-sided flotilla is not a battle. If the water would not take both lines, fail the
        // roll — SEM keeps the accumulated chance, so it simply comes round again somewhere wetter.
        if (ru == 0 || us == 0) return false;
        return true;
    }

    /**
     * One line of ships abreast, {@code facing} blocks off the centre. Each hull is placed through
     * {@link TankSpawner#spawnShipWithCrew}, which spirals for real water around the requested
     * point — so an island or a sandbar in the middle of the line costs that hull, not the event.
     */
    private static int deployLine(ServerLevel level, BlockPos centre, TankSpawner.TankFaction faction,
                                  boolean alongX, int facing, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            int along = (i - count / 2) * SHIP_SPACING;
            int off = SEPARATION * facing;
            BlockPos pos = alongX ? centre.offset(off, 0, along) : centre.offset(along, 0, off);
            Integer surface = WaterSupport.surfaceY(level, pos.getX(), pos.getZ());
            if (surface == null) continue; // that spot is land; the rest of the line may still float
            BlockPos onWater = new BlockPos(pos.getX(), surface, pos.getZ());
            if (TankSpawner.spawnShipWithCrew(level, onWater, faction, null, null) != null) spawned++;
        }
        return spawned;
    }

    /**
     * Ocean, but not one that is frozen over. Every ocean variant carries {@code IS_OCEAN}, so the
     * tag does the including and only the two frozen ones need naming — a list of the wanted
     * biomes would silently miss any the game or a mod adds later.
     */
    private static boolean isFightableOcean(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (!biome.is(BiomeTags.IS_OCEAN)) return false;
        return !biome.is(Biomes.FROZEN_OCEAN) && !biome.is(Biomes.DEEP_FROZEN_OCEAN);
    }
}
