package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/** Shared low-level controls and terrain references for helicopter and plane pilots. */
final class AirframeSupport {

    private static final double TERRAIN_SAMPLE_STEP = 8.0;

    private AirframeSupport() {}

    static void releaseInputs(VehicleEntity vehicle) {
        vehicle.setForwardInputDown(false);
        vehicle.setBackInputDown(false);
        vehicle.setLeftInputDown(false);
        vehicle.setRightInputDown(false);
        vehicle.setDownInputDown(false);
        vehicle.setMouseMoveSpeedX(0.0F);
        vehicle.setMouseMoveSpeedY(0.0F);
    }

    static void clearDecoy(VehicleEntity vehicle) {
        vehicle.setDecoyInputDown(false);
    }

    static void updateDecoy(VehicleEntity vehicle, AbstractUnit unit, DecoyEpisode episode,
                            float healthFraction, float chance) {
        float max = vehicle.getMaxHealth();
        if (!(max > 0.0F) || vehicle.getHealth() > max * healthFraction || vehicle.onGround()) {
            clearDecoy(vehicle);
            return;
        }
        if (episode.roll(unit.level().getGameTime(), unit.getRandom(), chance) && vehicle.hasDecoy()) {
            vehicle.setDecoyInputDown(true);
        }
    }

    static void updateChunkLoading(ChunkTicket ticket, VehicleEntity vehicle, boolean enabled) {
        if (enabled) {
            ticket.follow(vehicle);
        } else {
            ticket.release(vehicle);
        }
    }

    static int surfaceBelow(VehicleEntity vehicle) {
        return vehicle.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, vehicle.getBlockX(), vehicle.getBlockZ());
    }

    static double cruiseAltitudeHere(VehicleEntity vehicle, double flightAltitude) {
        return surfaceBelow(vehicle) + flightAltitude;
    }

    static double cruiseAltitudeToward(VehicleEntity vehicle, double toX, double toZ,
                                       double flightAltitude, double lookahead) {
        return highestGroundToward(vehicle, toX, toZ, lookahead) + flightAltitude;
    }

    static int highestGroundToward(VehicleEntity vehicle, double toX, double toZ, double lookahead) {
        int highest = surfaceBelow(vehicle);
        double dx = toX - vehicle.getX();
        double dz = toZ - vehicle.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 1.0E-4) return highest;

        Level level = vehicle.level();
        double nx = dx / distance;
        double nz = dz / distance;
        double reach = Math.min(distance, lookahead);
        for (double d = TERRAIN_SAMPLE_STEP; d <= reach; d += TERRAIN_SAMPLE_STEP) {
            int height = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(vehicle.getX() + nx * d), Mth.floor(vehicle.getZ() + nz * d));
            if (height > highest) highest = height;
        }
        return highest;
    }
}
