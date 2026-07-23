package com.neoalive.tacz_sewv.procedural.events;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.EmplacementSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

/**
 * SEM's {@code far_combat} at three times the scale, with armour: two full platoons of infantry
 * with vehicles behind them, and — rarely — a mortar or a TOW dug in on one side.
 *
 * <p>Deliberately its own event rather than a bigger {@code far_combat}: that one is SEM's and is
 * modified through {@code MixinCombatEvent}, whose tank spawns are <em>conditional</em> on it
 * having fired. This one is registered in its own right, so its rate, its enable toggle and its
 * escalation are independent, and a server can run big battles rarely while leaving skirmishes
 * alone.
 *
 * <p>No biome or terrain conditions beyond SEM's universal placement gate — a stand-up fight
 * happens wherever the two sides met.
 *
 * <p>The emplacement roll is <b>per side and deliberately tiny</b>. A mortar or a TOW turns an
 * infantry fight into something the player has to manoeuvre against, which is worth meeting
 * occasionally and tiresome as a fixture; at the default 4% most battles have neither and the
 * ones that do are memorable.
 */
public final class LargeCombatEvent extends DynamicEvent {

    public static final String ID = "large_combat";

    /** Three times far_combat's 3-5, which is the whole brief. */
    private static final int SQUAD_MIN = 9;
    private static final int SQUAD_VARIANCE = 7;

    /** Three times far_combat's 24, so the two sides start out of each other's rifle range. */
    private static final int SEPARATION = 72;
    private static final int INFANTRY_SCATTER = 10;

    /** Vehicles form up behind their own infantry rather than in among it. */
    private static final int VEHICLE_BACKSET = 16;
    private static final int VEHICLE_SPACING = 14;

    /** Off to one flank, where a crew-served weapon would actually be sited. */
    private static final int EMPLACEMENT_FLANK = 22;

    public LargeCombatEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.LARGE_COMBAT_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.LARGE_COMBAT_FAILURE_MULTIPLIER.get();
    }

    @Override
    public int getMinDistance() {
        return 100;
    }

    @Override
    public int getMaxDistance() {
        return 170;
    }

    @Override
    public boolean canExecute(ServerLevel level, ServerPlayer player) {
        return SewvConfig.LARGE_COMBAT_EVENTS_ENABLED.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        if (!SpawnHelper.isValidSpawn(level, centerPos)) return false;
        // Both sides field armour here, so both pools have to be usable — unlike the one-sided
        // events, there is no "pick whichever faction works" fallback that still makes a battle.
        if (!TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.RU)
                || !TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.US)) {
            return false;
        }

        boolean alongX = level.random.nextBoolean();
        BlockPos ruAnchor = TankSpawner.adjustHeight(level,
                alongX ? centerPos.offset(SEPARATION, 0, 0) : centerPos.offset(0, 0, SEPARATION));
        BlockPos usAnchor = TankSpawner.adjustHeight(level,
                alongX ? centerPos.offset(-SEPARATION, 0, 0) : centerPos.offset(0, 0, -SEPARATION));

        int vehicles = 0;
        vehicles += deploySide(level, ruAnchor, TankSpawner.TankFaction.RU, alongX, 1);
        vehicles += deploySide(level, usAnchor, TankSpawner.TankFaction.US, alongX, -1);

        // This is the armoured battle event: infantry with no armour on either side is just
        // far_combat with more people, so a placement that fits no vehicle at all fails the roll
        // and comes round again rather than shipping the wrong event.
        if (vehicles == 0) return false;

        level.playSound(null, centerPos, SoundEvents.GENERIC_EXPLODE, SoundSource.AMBIENT, 3.0F, 0.7F);
        return true;
    }

    /**
     * One side's deployment: riflemen on the line, armour a short way behind them, and the rare
     * crew-served weapon out on a flank. {@code facing} is +1/-1 for which way "behind" is.
     * Answers how many vehicles actually fitted.
     */
    private static int deploySide(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction,
                                  boolean alongX, int facing) {
        int squad = SQUAD_MIN + level.random.nextInt(SQUAD_VARIANCE);
        for (int i = 0; i < squad; i++) {
            EventSpawns.infantry(level, anchor, faction, INFANTRY_SCATTER);
        }

        int wanted = SewvConfig.LARGE_COMBAT_VEHICLES.get();
        int spawned = 0;
        for (int i = 0; i < wanted; i++) {
            int back = VEHICLE_BACKSET * facing;
            int along = (i - wanted / 2) * VEHICLE_SPACING;
            BlockPos pos = alongX ? anchor.offset(back, 0, along) : anchor.offset(along, 0, back);
            if (TankSpawner.spawnTankWithCrew(level, TankSpawner.adjustHeight(level, pos), faction, null) != null) {
                spawned++;
            }
        }

        if (level.random.nextDouble() < SewvConfig.LARGE_COMBAT_EMPLACEMENT_CHANCE.get()) {
            EmplacementSpawner.Emplacement type = level.random.nextBoolean()
                    ? EmplacementSpawner.Emplacement.MORTAR : EmplacementSpawner.Emplacement.TOW;
            int flank = level.random.nextBoolean() ? EMPLACEMENT_FLANK : -EMPLACEMENT_FLANK;
            BlockPos pos = alongX
                    ? anchor.offset(VEHICLE_BACKSET * facing, 0, flank)
                    : anchor.offset(flank, 0, VEHICLE_BACKSET * facing);
            // No fire mission: this crew can see the fight it is part of, so it works its own
            // targets. A mission is for shelling a place nobody has eyes on.
            EmplacementSpawner.spawn(level, TankSpawner.adjustHeight(level, pos), type, faction, null, null);
        }
        return spawned;
    }
}
