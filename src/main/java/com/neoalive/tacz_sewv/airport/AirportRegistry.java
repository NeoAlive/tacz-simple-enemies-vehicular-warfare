package com.neoalive.tacz_sewv.airport;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension map of cleared airports. Stores the strip's geometry so a land order can resolve
 * one — and taxi into a parking slot on it — without loading the runway block's chunk.
 */
public class AirportRegistry extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_airports";

    /**
     * A cleared strip. Only the measured numbers and the runway's own segmentation settings are
     * stored; the touchdown point and the parking slots are derived from them and built once,
     * here, so nothing downstream has to re-derive geometry per aircraft per tick.
     */
    public record Airport(BlockPos threshold, float headingDeg, int length, int width,
                          RunwaySlots slots) {

        public static Airport of(BlockPos threshold, float headingDeg, int length, int width,
                                 double slotFactor, double bufferFactor, double extraFactor) {
            return new Airport(threshold, headingDeg, length, width,
                    RunwaySlots.of(threshold, headingDeg, length, width,
                            slotFactor, bufferFactor, extraFactor));
        }

        public BlockPos touchdown() {
            return this.slots.touchdown();
        }
    }

    private final Map<Long, Airport> airports = new HashMap<>();

    public AirportRegistry() {}

    public static AirportRegistry load(CompoundTag nbt) {
        AirportRegistry data = new AirportRegistry();
        ListTag list = nbt.getList("Airports", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            // An entry written before the strip was measured carries no geometry to segment. Drop
            // it rather than guess: the runway block re-notes itself the moment its chunk loads.
            if (!entry.contains("Length")) continue;
            data.airports.put(entry.getLong("Pos"), Airport.of(
                    BlockPos.of(entry.getLong("Threshold")),
                    entry.getFloat("Heading"),
                    entry.getInt("Length"),
                    entry.getInt("Width"),
                    entry.getDouble("SlotFactor"),
                    entry.getDouble("BufferFactor"),
                    entry.getDouble("ExtraFactor")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Airport> e : airports.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", e.getKey());
            entry.putLong("Threshold", e.getValue().threshold().asLong());
            entry.putFloat("Heading", e.getValue().headingDeg());
            entry.putInt("Length", e.getValue().length());
            entry.putInt("Width", e.getValue().width());
            RunwaySlots slots = e.getValue().slots();
            entry.putDouble("SlotFactor", slots.slotFactor());
            entry.putDouble("BufferFactor", slots.bufferFactor());
            entry.putDouble("ExtraFactor", slots.extraFactor());
            list.add(entry);
        }
        nbt.put("Airports", list);
        return nbt;
    }

    public static AirportRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(AirportRegistry::load, AirportRegistry::new, DATA_NAME);
    }

    public void note(BlockPos runwayPos, Airport airport) {
        airports.put(runwayPos.asLong(), airport);
        setDirty();
    }

    public void forget(BlockPos runwayPos) {
        if (airports.remove(runwayPos.asLong()) != null) setDirty();
    }

    /** Closest cleared airport whose touchdown is within {@code radius}; {@code radius <= 0} disables. */
    @Nullable
    public Airport nearest(BlockPos landPos, double radius) {
        if (radius <= 0.0 || airports.isEmpty()) return null;
        double r2 = radius * radius;
        Airport best = null;
        double bestD = Double.MAX_VALUE;
        for (Airport airport : airports.values()) {
            double d = airport.touchdown().distToCenterSqr(landPos.getX(), landPos.getY(), landPos.getZ());
            if (d <= r2 && d < bestD) {
                bestD = d;
                best = airport;
            }
        }
        return best;
    }
}
