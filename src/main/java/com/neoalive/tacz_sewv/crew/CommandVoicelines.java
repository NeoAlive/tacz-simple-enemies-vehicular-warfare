package com.neoalive.tacz_sewv.crew;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.BattleGroup;

/** RU/US collective-order acknowledgements when a battle group commits a new play. */
public final class CommandVoicelines {

    private CommandVoicelines() {}

    public static void onPlayCommitted(ServerLevel level, BattleGroup group, boolean playChanged) {
        if (!playChanged || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        VehicleEntity hull = speakerHull(level, group);
        if (hull == null) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof RUunitEntity || passenger instanceof USunitEntity) {
                CrewRadio.play(hull, CrewRadio.Line.ORDERS);
                return;
            }
        }
    }

    @Nullable
    private static VehicleEntity speakerHull(ServerLevel level, BattleGroup group) {
        if (group.hasCommander()) {
            VehicleEntity hull = hullOfDriver(level, group.commanderId());
            if (hull != null) return hull;
        }
        for (int memberId : group.memberIds()) {
            VehicleEntity hull = hullOfDriver(level, memberId);
            if (hull != null) return hull;
        }
        return null;
    }

    @Nullable
    private static VehicleEntity hullOfDriver(ServerLevel level, int driverId) {
        Entity entity = level.getEntity(driverId);
        if (!(entity instanceof AbstractUnit unit)) return null;
        if (!(unit.getVehicle() instanceof VehicleEntity hull)) return null;
        if (hull.getFirstPassenger() != unit) return null;
        return hull;
    }
}
