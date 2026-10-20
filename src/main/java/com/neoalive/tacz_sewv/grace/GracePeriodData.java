package com.neoalive.tacz_sewv.grace;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-global grace period state, stored on the overworld. Grace is a <b>countdown of remaining
 * ticks</b>, not a deadline: {@link GraceTickHandler} spends it by whichever clock moved further,
 * game time or day time, so {@code /time add} and sleeping skip it forward, {@code /time set} back
 * cannot buy it time, and a frozen daylight cycle still runs it out.
 *
 * <p>{@link #active} is read from every ambient-spawn gate, so it answers off a cached static flag
 * instead of touching SavedData. The cache is refreshed whenever the data is loaded or changed; one
 * server per JVM makes a static safe (the integrated server rewrites it on every world load via
 * {@link GraceTickHandler}).
 */
public class GracePeriodData extends SavedData {

    public static final long DAY_TICKS = 24000L;
    private static final String DATA_NAME = "tacz_sewv_grace";

    private static volatile boolean cachedActive;

    long remaining;
    boolean initialized;
    int lastAnnouncedDays = -1;
    final Set<UUID> acknowledged = new HashSet<>();

    @Nullable
    public static GracePeriodData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(GracePeriodData::new, (tag, registries) -> GracePeriodData.load(tag)), DATA_NAME);
    }

    /** True while grace holds. Cheap: one volatile read. The level is unused, kept for call sites. */
    public static boolean active(Level level) {
        return cachedActive;
    }

    static void refreshCache(@Nullable GracePeriodData data) {
        cachedActive = data != null && data.remaining > 0;
    }

    public static GracePeriodData load(CompoundTag nbt) {
        GracePeriodData data = new GracePeriodData();
        data.remaining = nbt.getLong("remaining");
        data.initialized = nbt.getBoolean("init");
        data.lastAnnouncedDays = nbt.getInt("announced");
        for (Tag t : nbt.getList("ack", Tag.TAG_INT_ARRAY)) data.acknowledged.add(NbtUtils.loadUUID(t));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putLong("remaining", remaining);
        nbt.putBoolean("init", initialized);
        nbt.putInt("announced", lastAnnouncedDays);
        ListTag ack = new ListTag();
        for (UUID id : acknowledged) ack.add(NbtUtils.createUUID(id));
        nbt.put("ack", ack);
        return nbt;
    }

    /** Whole days left, rounded up; 0 when inactive. */
    public int daysLeft() {
        return remaining <= 0 ? 0 : (int) ((remaining + DAY_TICKS - 1) / DAY_TICKS);
    }

    public long ticksLeft() {
        return Math.max(0, remaining);
    }

    /** Spends {@code ticks} of grace; true when this ran it out. */
    boolean spend(long ticks) {
        if (remaining <= 0 || ticks <= 0) return false;
        remaining -= ticks;
        setDirty();
        if (remaining > 0) return false;
        remaining = 0;
        refreshCache(this);
        return true;
    }

    void start(long ticks) {
        remaining = ticks;
        lastAnnouncedDays = daysLeft();
        acknowledged.clear();
        setDirty();
        refreshCache(this);
    }

    void stop() {
        remaining = 0;
        lastAnnouncedDays = -1;
        setDirty();
        refreshCache(this);
    }
}
