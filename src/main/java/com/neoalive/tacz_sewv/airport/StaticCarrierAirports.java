package com.neoalive.tacz_sewv.airport;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.compat.NeoArmsCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Live STATIC Neo Arms carriers as airport pads. Not SavedData — the strip is recomputed from the
 * entity while it stays locked. DYNAMIC carriers are invisible here.
 */
public final class StaticCarrierAirports {

    private StaticCarrierAirports() {}

    /**
     * A cleared land strip plus optional carrier, whichever touchdown is closer within radius.
     */
    @Nullable
    public static AirportRegistry.Airport nearest(ServerLevel level, BlockPos landPos, double radius) {
        AirportRegistry.Airport land = AirportRegistry.get(level).nearest(landPos, radius);
        NeoArmsCarrierAccess.Strip carrier = nearestCarrierStrip(level, landPos, radius);
        if (carrier == null) return land;
        AirportRegistry.Airport fromCarrier = toAirport(carrier);
        if (land == null) return fromCarrier;
        double landD = land.touchdown().distToCenterSqr(landPos.getX(), landPos.getY(), landPos.getZ());
        double carD = carrier.threshold().distanceToSqr(landPos.getX() + 0.5, landPos.getY(), landPos.getZ() + 0.5);
        return carD < landD ? fromCarrier : land;
    }

    @Nullable
    public static NeoArmsCarrierAccess.Strip nearestCarrierStrip(ServerLevel level, BlockPos landPos,
                                                                  double radius) {
        if (!NeoArmsCompat.present() || radius <= 0.0) return null;
        double r2 = radius * radius;
        NeoArmsCarrierAccess.Strip best = null;
        double bestD = Double.MAX_VALUE;
        AABB box = new AABB(landPos).inflate(radius);
        for (Entity entity : level.getEntities((Entity) null, box, NeoArmsCarrierAccess::isCarrier)) {
            NeoArmsCarrierAccess.Strip strip = NeoArmsCarrierAccess.deckStrip(entity);
            if (strip == null) continue;
            double d = strip.threshold().distanceToSqr(
                    landPos.getX() + 0.5, landPos.getY(), landPos.getZ() + 0.5);
            if (d <= r2 && d < bestD) {
                bestD = d;
                best = strip;
            }
        }
        return best;
    }

    public static AirportRegistry.Airport toAirport(NeoArmsCarrierAccess.Strip strip) {
        double slot = SewvConfig.AIRPORT_SLOT_SIZE_FACTOR.get();
        double buffer = SewvConfig.AIRPORT_SLOT_BUFFER_FACTOR.get();
        double extra = SewvConfig.AIRPORT_EXTRA_TAKEOFF_FACTOR.get();
        // Preserve deck altitude: BlockPos.containing floors Y onto the walkable surface band.
        BlockPos threshold = BlockPos.containing(
                strip.threshold().x, strip.threshold().y, strip.threshold().z);
        return AirportRegistry.Airport.of(
                threshold,
                strip.headingDeg(),
                strip.length(),
                strip.width(),
                slot, buffer, extra);
    }
}
