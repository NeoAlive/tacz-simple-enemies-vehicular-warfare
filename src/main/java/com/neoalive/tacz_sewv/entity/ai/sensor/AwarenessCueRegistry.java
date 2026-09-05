package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Level-scoped sound-cue store for idle-crew investigation. Register is event-driven; consume
 * walks sparse chunk buckets from {@link AwarenessCues}.
 */
public final class AwarenessCueRegistry {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double CELL_SIZE = 4.0;
    /** Coarser grid for TaCZ autofire — one cell covers a firefight footprint. */
    private static final double TACZ_CELL_SIZE = 16.0;
    private static final int MAX_CUES_PER_CHUNK = 8;
    /**
     * Index = {@link TriggerKind#ordinal()}. TaCZ fire uses a very long interval (6 s) because
     * full-auto posts {@code GunFireEvent} every shot.
     */
    private static final int[] DEDUPE_INTERVAL = {
            0,   // OUTER_ENTITY
            40,  // VEHICLE_ENGINE
            10,  // VEHICLE_CANNON
            60,  // CREW_VOICE
            120, // TACZ_FIRE — 6 s hard throttle
            60,  // DRONE
            40,  // PLAYER_HURT
            80   // PLAYER_EAT
    };

    /**
     * Sound / outer triggers. Hear radii match the investigate plan; {@link #maxHearRadius()}
     * bounds the consume chunk window.
     */
    public enum TriggerKind {
        OUTER_ENTITY(0, 1.0, 1.0),
        VEHICLE_ENGINE(30, 0.30, 0.35),
        VEHICLE_CANNON(128, 1.0, 0.85),
        CREW_VOICE(20, 0.15, 0.25),
        /** TaCZ infantry fire — registered from {@code GunFireEvent}, not Level.playSound. */
        TACZ_FIRE(64, 0.45, 0.55),
        DRONE(48, 0.50, 0.40),
        PLAYER_HURT(24, 0.55, 0.50),
        PLAYER_EAT(16, 0.30, 0.20);

        public final int hearRadius;
        public final double triggerChance;
        public final double strength;

        TriggerKind(int hearRadius, double triggerChance, double strength) {
            this.hearRadius = hearRadius;
            this.triggerChance = triggerChance;
            this.strength = strength;
        }

        /** Largest hear radius among sound kinds — consume chunk scan bound. */
        public static int maxHearRadius() {
            int max = 0;
            for (TriggerKind k : values()) {
                if (k != OUTER_ENTITY) max = Math.max(max, k.hearRadius);
            }
            return max;
        }
    }

    /** Why a cue was not audible to a hull this tick (debug tallies). */
    public enum Reject {
        EXPIRED,
        RANGE,
        SAME_FACTION,
        FRIENDLY_SOURCE,
        CHANCE,
        GATED_COMBAT,
        GATED_ORDERS
    }

    static final class SoundCue {
        final BlockPos pos;
        final TriggerKind kind;
        final double strength;
        @Nullable
        final CrewFacts.Faction sourceFaction;
        final int sourceUnitId;
        final long heardAt;
        /** Stable identity for per-hull chance latch: pos + kind. */
        final long cueKey;

        SoundCue(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
                int sourceUnitId, long heardAt) {
            this.pos = pos;
            this.kind = kind;
            this.strength = kind.strength;
            this.sourceFaction = sourceFaction;
            this.sourceUnitId = sourceUnitId;
            this.heardAt = heardAt;
            this.cueKey = pos.asLong() ^ ((long) kind.ordinal() << 48);
        }
    }

    private static final Map<ServerLevel, AwarenessCueRegistry> LEVELS = new WeakHashMap<>();
    private static final Map<ServerLevel, AwarenessCueRegistry> LEVELS_SYNC =
            java.util.Collections.synchronizedMap(LEVELS);

    final Map<Long, List<SoundCue>> chunkCues = new ConcurrentHashMap<>();
    private final Map<Long, Long> dedupeDeadline = new ConcurrentHashMap<>();

    static AwarenessCueRegistry of(ServerLevel level) {
        synchronized (LEVELS_SYNC) {
            return LEVELS.computeIfAbsent(level, ignored -> new AwarenessCueRegistry());
        }
    }

    void register(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
            int sourceUnitId, long now) {
        pruneExpiredDedupe(now);
        long cellKey = cellKey(pos, kind);
        int interval = kind.ordinal() < DEDUPE_INTERVAL.length ? DEDUPE_INTERVAL[kind.ordinal()] : 0;
        if (interval > 0) {
            Long next = this.dedupeDeadline.get(cellKey);
            if (next != null && now < next) return;
            this.dedupeDeadline.put(cellKey, now + interval);
        }

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long ck = chunkKey(cx, cz);
        List<SoundCue> list = this.chunkCues.computeIfAbsent(ck, ignored -> new ArrayList<>(2));
        if (list.size() >= MAX_CUES_PER_CHUNK) {
            list.remove(0);
        }
        list.add(new SoundCue(pos.immutable(), kind, sourceFaction, sourceUnitId, now));
        debug("register kind={} pos={} faction={}", kind, pos, sourceFaction);
    }

    private void pruneExpiredDedupe(long now) {
        this.dedupeDeadline.entrySet().removeIf(e -> e.getValue() <= now);
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long cellKey(BlockPos pos, TriggerKind kind) {
        double size = kind == TriggerKind.TACZ_FIRE ? TACZ_CELL_SIZE : CELL_SIZE;
        int x = (int) (pos.getX() / size);
        int y = (int) (pos.getY() / size);
        int z = (int) (pos.getZ() / size);
        return ((long) kind.ordinal() << 60)
                | ((long) x & 0xFFFFF) << 40
                | ((long) y & 0xFFFFF) << 20
                | ((long) z & 0xFFFFF);
    }

    /** Package-visible for self-check dedupe tests. */
    boolean wouldDedupe(BlockPos pos, TriggerKind kind, long now) {
        long cellKey = cellKey(pos, kind);
        int interval = kind.ordinal() < DEDUPE_INTERVAL.length ? DEDUPE_INTERVAL[kind.ordinal()] : 0;
        if (interval <= 0) return false;
        Long next = this.dedupeDeadline.get(cellKey);
        return next != null && now < next;
    }

    void registerForTest(BlockPos pos, TriggerKind kind, long now) {
        register(pos, kind, null, -1, now);
    }

    /** Drop expired cues while iterating; remove empty chunk buckets. */
    static boolean expired(SoundCue cue, long now) {
        return Facts.ticksSince(cue.heardAt, now) >= Facts.CONTACT_MEMORY_TICKS;
    }

    private static void debug(String msg, Object... args) {
        if (!ClientConfig.flag(ClientConfig.OUTER_RING_DEBUG_LOGGING)) return;
        LOG.info("[sewv-cues] " + msg, args);
    }

    /** Package-visible empty registry for headless self-check (no ServerLevel). */
    static AwarenessCueRegistry forTest() {
        return new AwarenessCueRegistry();
    }

    private AwarenessCueRegistry() {}
}
