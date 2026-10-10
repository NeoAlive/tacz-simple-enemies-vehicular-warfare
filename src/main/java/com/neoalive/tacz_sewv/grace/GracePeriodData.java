package com.neoalive.tacz_sewv.grace;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-global grace period state, stored on the overworld. The deadline is an absolute
 * <b>game time</b> (not day time), so {@code /time set} cannot skip it and it means the same moment
 * after a save/load.
 *
 * <p>{@link #active} is read from every ambient-spawn gate, so it answers off a cached static
 * deadline instead of touching SavedData. The cache is refreshed whenever the data is loaded or
 * changed; one server per JVM makes a static safe (the integrated server rewrites it on every
 * world load via {@link GraceTickHandler}).
 */
public class GracePeriodData extends SavedData {

    public static final long DAY_TICKS = 24000L;
    private static final String DATA_NAME = "tacz_sewv_grace";

    private static volatile long cachedEnd = -1L;

    long endTick = -1L;
    boolean initialized;
    int lastAnnouncedDays = -1;
    final Set<UUID> acknowledged = new HashSet<>();

    @Nullable
    public static GracePeriodData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return overworld.getDataStorage().computeIfAbsent(GracePeriodData::load, GracePeriodData::new, DATA_NAME);
    }

    /** True while grace holds. Cheap: one volatile read and a game-time compare. */
    public static boolean active(Level level) {
        long end = cachedEnd;
        return end >= 0 && level.getGameTime() < end;
    }

    static void refreshCache(@Nullable GracePeriodData data) {
        cachedEnd = data == null ? -1L : data.endTick;
    }

    public static GracePeriodData load(CompoundTag nbt) {
        GracePeriodData data = new GracePeriodData();
        data.endTick = nbt.getLong("end");
        data.initialized = nbt.getBoolean("init");
        data.lastAnnouncedDays = nbt.getInt("announced");
        for (Tag t : nbt.getList("ack", Tag.TAG_INT_ARRAY)) data.acknowledged.add(NbtUtils.loadUUID(t));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        nbt.putLong("end", endTick);
        nbt.putBoolean("init", initialized);
        nbt.putInt("announced", lastAnnouncedDays);
        ListTag ack = new ListTag();
        for (UUID id : acknowledged) ack.add(NbtUtils.createUUID(id));
        nbt.put("ack", ack);
        return nbt;
    }

    /** Whole days left, rounded up; 0 when inactive. */
    public int daysLeft(long now) {
        long left = ticksLeft(now);
        return left <= 0 ? 0 : (int) ((left + DAY_TICKS - 1) / DAY_TICKS);
    }

    public long ticksLeft(long now) {
        return endTick < 0 ? 0 : Math.max(0, endTick - now);
    }

    void start(long now, long ticks) {
        endTick = now + ticks;
        lastAnnouncedDays = daysLeft(now);
        acknowledged.clear();
        setDirty();
        refreshCache(this);
    }

    void stop() {
        endTick = -1L;
        lastAnnouncedDays = -1;
        setDirty();
        refreshCache(this);
    }
}
