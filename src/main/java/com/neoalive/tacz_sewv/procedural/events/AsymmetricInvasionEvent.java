package com.neoalive.tacz_sewv.procedural.events;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.EmplacementSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

/**
 * An armoured thrust against a dug-in position: one faction brings two or three vehicles and
 * nothing else, the other has infantry, mortars and TOWs and no vehicles at all.
 *
 * <p>The point is the mismatch. Every other event in this package puts like against like, so
 * whoever the player helps is a matter of taste; here the two sides answer entirely different
 * questions — the attacker has armour that the defender can only beat with the crew-served
 * weapons it was given, and those weapons are exactly the ones this mod's AI knows how to work
 * (a TOW that can actually hit, a mortar that can actually range). Which faction gets which side
 * is a coin flip, so the same event reads differently each time it fires.
 *
 * <p>The defence is placed at the event centre and the armour a good way off it, on one side, so
 * the fight starts at range and the player can arrive to either.
 */
public final class AsymmetricInvasionEvent extends DynamicEvent {

    public static final String ID = "asymmetric_invasion";

    /** How far the armour starts off the defended position. */
    private static final int APPROACH_DISTANCE = 70;
    private static final int VEHICLE_SPACING = 16;

    /** The defence occupies a position rather than a point. */
    private static final int DEFENCE_SCATTER = 14;
    private static final int EMPLACEMENT_SPREAD = 16;

    /** Two or three vehicles, as specified — a thrust, not an army. */
    private static final int MIN_VEHICLES = 2;
    private static final int VEHICLE_VARIANCE = 2;

    public AsymmetricInvasionEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.INVASION_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.INVASION_FAILURE_MULTIPLIER.get();
    }

    @Override
    public int getMinDistance() {
        return 90;
    }

    @Override
    public int getMaxDistance() {
        return 160;
    }

    @Override
    public boolean canExecute(ServerLevel level, ServerPlayer player) {
        return SewvConfig.INVASION_EVENTS_ENABLED.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        if (!SpawnHelper.isValidSpawn(level, centerPos)) return false;

        // The attacker is whoever has armour to attack with; the defender needs no pool at all,
        // which is what lets this event still fire on a server that emptied one side's vehicles.
        TankSpawner.TankFaction attacker = EventSpawns.pickVehicleFaction(level);
        if (attacker == null) return false;
        TankSpawner.TankFaction defender = EventSpawns.opposite(attacker);

        BlockPos position = TankSpawner.adjustHeight(level, centerPos);
        deployDefence(level, position, defender);

        boolean alongX = level.random.nextBoolean();
        int direction = level.random.nextBoolean() ? 1 : -1;
        int wanted = MIN_VEHICLES + level.random.nextInt(VEHICLE_VARIANCE);
        int spawned = 0;
        for (int i = 0; i < wanted; i++) {
            int approach = APPROACH_DISTANCE * direction;
            int abreast = (i - wanted / 2) * VEHICLE_SPACING;
            BlockPos pos = alongX
                    ? centerPos.offset(approach, 0, abreast)
                    : centerPos.offset(abreast, 0, approach);
            if (TankSpawner.spawnTankWithCrew(level, TankSpawner.adjustHeight(level, pos), attacker, null) != null) {
                spawned++;
            }
        }

        // No armour, no invasion — the defence alone is a garrison, which is a different event.
        // The defenders already placed are left where they are: they read as a position that
        // happens to be held, and cleaning them up would cost more than it is worth.
        return spawned > 0;
    }

    /**
     * Infantry holding the position, with the crew-served weapons that give it a chance against
     * armour. The emplacements are spread around the position rather than stacked on it, so a
     * single tank shell cannot take the whole anti-tank capability at once.
     */
    private static void deployDefence(ServerLevel level, BlockPos position, TankSpawner.TankFaction faction) {
        int infantry = SewvConfig.INVASION_DEFENDER_INFANTRY.get();
        for (int i = 0; i < infantry; i++) {
            EventSpawns.infantry(level, position, faction, DEFENCE_SCATTER);
        }

        int tows = SewvConfig.INVASION_DEFENDER_TOWS.get();
        for (int i = 0; i < tows; i++) {
            EmplacementSpawner.spawn(level, TankSpawner.adjustHeight(level, scatter(level, position)),
                    EmplacementSpawner.Emplacement.TOW, faction, null, null);
        }

        int mortars = SewvConfig.INVASION_DEFENDER_MORTARS.get();
        for (int i = 0; i < mortars; i++) {
            // No fire mission: the armour is coming to them, so the crew will have it in sight.
            EmplacementSpawner.spawn(level, TankSpawner.adjustHeight(level, scatter(level, position)),
                    EmplacementSpawner.Emplacement.MORTAR, faction, null, null);
        }
    }

    private static BlockPos scatter(ServerLevel level, BlockPos position) {
        return position.offset(
                level.random.nextInt(EMPLACEMENT_SPREAD * 2 + 1) - EMPLACEMENT_SPREAD, 0,
                level.random.nextInt(EMPLACEMENT_SPREAD * 2 + 1) - EMPLACEMENT_SPREAD);
    }
}
