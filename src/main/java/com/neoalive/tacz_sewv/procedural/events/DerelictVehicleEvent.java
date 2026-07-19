package com.neoalive.tacz_sewv.procedural.events;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
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
 * A knocked-out RU or US vehicle with its surviving crew camped around it on foot.
 *
 * <p>The counterpart to {@link ConvoyEvent}: something between "a crewed tank comes at you" and
 * "no vehicle at all". The hull is nearly destroyed, has no energy and holds a couple of rounds,
 * so it is <b>salvage, not a threat</b> — the fight is with the survivors, and the prize is a
 * vehicle you can recover if you can repair and refuel it.
 *
 * <h2>Two interlocks that are load-bearing and are NOT coded here</h2>
 * Nothing in this class stops the survivors from simply climbing back into the vehicle, and
 * nothing refuels it. Both fall out of thresholds that already exist elsewhere, and both will
 * break silently if those are changed:
 * <ul>
 *   <li>{@code SeekAbandonedVehicleGoal} refuses any hull below {@code autoBoardMinHealthFraction}
 *       (0.25), and {@code derelictHealthFraction} defaults to 0.15 — <b>under</b> it. Raise it
 *       past that line and the survivors scavenge their own wreck within seconds.
 *   <li>{@code MixinVehicleFactionEnergy} grants infinite energy to any hull whose first passenger
 *       is an RU/US unit. Leaving the crew on the ground is therefore also what keeps the tank
 *       out of fuel — the two are the same fact.
 * </ul>
 * Between them, "damaged" and "unfuelled" are one decision, not two, and the event quietly turns
 * into a plain crewed-tank spawn if either is undone.
 */
public final class DerelictVehicleEvent extends DynamicEvent {

    public static final String ID = "derelict_vehicle";

    /** Far enough to be a find rather than an ambush, near enough to be worth walking to. */
    private static final int MIN_DISTANCE = 60;
    private static final int MAX_DISTANCE = 140;

    /** Survivors camp on the hull rather than patrolling; this is how far they scatter. */
    private static final int GUARD_SCATTER = 8;

    public DerelictVehicleEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.DERELICT_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.DERELICT_FAILURE_MULTIPLIER.get();
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
        return SewvConfig.DERELICT_EVENTS_ENABLED.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        // SEM's universal placement gate. Returning false burns the roll WITHOUT resetting the
        // event's accumulated chance, so a bad location costs us nothing — which is the behaviour
        // we want, and the reason not to try harder to find a spot.
        if (!SpawnHelper.isValidSpawn(level, centerPos)) return false;

        boolean ruAvailable = TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.RU);
        boolean usAvailable = TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.US);
        if (!ruAvailable && !usAvailable) return false;

        TankSpawner.TankFaction faction = ruAvailable && usAvailable
                ? (level.random.nextBoolean() ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US)
                : (ruAvailable ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US);

        BlockPos hullPos = TankSpawner.adjustHeight(level, centerPos);
        // spawnBareVehicle is already exactly right: it omits setEnergy and stockAmmo, and a
        // freshly created VehicleEntity starts at zero energy with an empty container. So the
        // hull arrives unfuelled and empty for free — only its health has to be written, because
        // HEALTH entity data is defined as getMaxHealth() at spawn.
        VehicleEntity hull = TankSpawner.spawnBareVehicle(level, hullPos, faction);
        if (hull == null) return false; // empty pool, unresolvable id, or no room — retry later

        // A derelict has to be something that could be DRIVEN AWAY, and SuperbWarfare marks
        // everything that cannot with EngineType "Fixed" — mk_42, hpj_11, annihilator, tow,
        // mortar. Those are emplacements: "out of fuel" means nothing to a naval gun mount, and
        // there is no vehicle to recover at the end of it, so the whole premise of the event
        // collapses into a broken turret sitting in a field.
        //
        // Read from computed() (the STATIC datapack data, available the instant the entity exists)
        // NOT from getEngineInfo(). That is an entity FIELD, lazily populated inside travel() on the
        // hull's first baseTick — one tick AFTER addFreshEntity — so at spawn time it is null for
        // EVERY hull, a drivable T-90 exactly as much as a Fixed emplacement. Testing it here made
        // the event discard everything and always return false (the "/semevent force always fails"
        // bug). computed().getEngineType() is the same source HullFacts.computeTracked reads.
        //
        // Filtered here rather than in the pool because a Fixed hull is a perfectly good entry for
        // the CREWED spawns those pools mainly exist for. Discarding and failing costs one wasted
        // pick, and returning false burns the roll without resetting the accumulated chance, so
        // the event simply comes round again next cycle.
        try {
            if (hull.computed().getEngineType() == EngineType.FIXED) {
                hull.discard();
                return false;
            }
        } catch (Exception ignored) {
            // Unreadable vehicle data: let a rare odd hull through rather than reintroduce
            // an always-fail. A wrong derelict is a far smaller problem than a dead event.
        }

        hull.setHealth(hull.getMaxHealth() * SewvConfig.DERELICT_HEALTH_FRACTION.get().floatValue());
        TankSpawner.stockTokenAmmo(hull, SewvConfig.DERELICT_AMMO_COUNT.get());

        int survivors = 1 + level.random.nextInt(SewvConfig.DERELICT_GUARDS.get());
        for (int i = 0; i < survivors; i++) {
            spawnSurvivor(level, hullPos, faction);
        }
        return true;
    }

    /**
     * One survivor on foot beside the wreck. Deliberately never {@code startRiding} — see the
     * class doc; a mounted crew would refuel the hull it is sitting in.
     */
    private static void spawnSurvivor(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction) {
        AbstractUnit unit = faction == TankSpawner.TankFaction.RU
                ? new RUunitEntity(ModEntities.RUUNIT.get(), level)
                : new USunitEntity(ModEntities.USUNIT.get(), level);
        unit.setRole(UnitRole.DEFAULT);

        int x = anchor.getX() + level.random.nextInt(GUARD_SCATTER * 2 + 1) - GUARD_SCATTER;
        int z = anchor.getZ() + level.random.nextInt(GUARD_SCATTER * 2 + 1) - GUARD_SCATTER;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        unit.setPos(x + 0.5, y, z + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(unit.blockPosition()), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(unit);
    }
}
