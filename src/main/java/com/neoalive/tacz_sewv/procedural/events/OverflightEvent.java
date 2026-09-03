package com.neoalive.tacz_sewv.procedural.events;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * A single-faction RU/US flyover drawn exclusively from plane pools. PMC is never a candidate:
 * {@link TankSpawner#spawnPlaneWithCrew} puts PMC planes on the ground with a takeoff order, which
 * is the opposite of an overflight.
 *
 * <p>Player distance is the packed base below; {@code sewvFarEventSpawns} scales it at SEM's
 * {@code DynamicEventManager} when that gamerule is on.
 */
public final class OverflightEvent extends DynamicEvent {

    public static final String ID = "overflight";

    private static final int MIN_DISTANCE = 90;
    private static final int MAX_DISTANCE = 160;

    /** Same separation {@link com.neoalive.tacz_sewv.mixin.MixinCombatEvent} uses for CAS rolls. */
    private static final int PLANE_SPACING = 32;

    public OverflightEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.OVERFLIGHT_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.OVERFLIGHT_FAILURE_MULTIPLIER.get();
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
        return SewvConfig.OVERFLIGHT_EVENTS_ENABLED.get() && SewvConfig.PLANES_IN_EVENTS.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        if (!EventSpawns.placeable(level, centerPos)) return false;

        TankSpawner.TankFaction faction = EventSpawns.pickPlaneFaction(level);
        if (faction == null) return false;

        int wanted = SewvConfig.OVERFLIGHT_PLANES.get();
        if (wanted <= 0) return false;
        int count = 1 + (wanted > 1 ? level.random.nextInt(wanted) : 0);
        boolean alongX = level.random.nextBoolean();
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            int along = (i - count / 2) * PLANE_SPACING;
            BlockPos pos = alongX ? centerPos.offset(0, 0, along) : centerPos.offset(along, 0, 0);
            VehicleEntity plane = TankSpawner.spawnPlaneWithCrew(
                    level, TankSpawner.adjustHeight(level, pos), faction, null);
            if (plane != null) spawned++;
        }
        return spawned > 0;
    }
}
