package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Level-scoped gunfire-cue store for idle-crew investigation. Register is event-driven; consume
 * walks sparse chunk buckets from {@link AwarenessCues}.
 */
public final class AwarenessCueRegistry {

    private static final double CELL_SIZE = 4.0;
    /** Coarser grid for TaCZ autofire — one cell covers a firefight footprint. */
    private static final double TACZ_CELL_SIZE = 16.0;
    private static final int MAX_CUES_PER_CHUNK = 8;

    /** Gunfire a crew can hear. Hear radius, deterministic trigger chance, merge strength, dedupe ticks. */
    public enum TriggerKind {
        VEHICLE_CANNON(128, 1.0, 0.85, 10),
        /**
         * TaCZ infantry fire — registered from {@code GunFireEvent}, not Level.playSound. The 6 s
         * dedupe is a hard throttle: full-auto posts an event every shot.
         */
        TACZ_FIRE(64, 0.45, 0.55, 120);

        public final int hearRadius;
        public final double triggerChance;
        public final double strength;
        final int dedupeTicks;

        TriggerKind(int hearRadius, double triggerChance, double strength, int dedupeTicks) {
            this.hearRadius = hearRadius;
            this.triggerChance = triggerChance;
            this.strength = strength;
            this.dedupeTicks = dedupeTicks;
        }

        /** Largest hear radius — consume chunk scan bound. */
        public static int maxHearRadius() {
            int max = 0;
            for (TriggerKind k : values()) max = Math.max(max, k.hearRadius);
            return max;
        }
    }

    static final class SoundCue {
        final BlockPos pos;
        final TriggerKind kind;
        @Nullable
        final CrewFacts.Faction sourceFaction;
        final int sourceUnitId;
        final long heardAt;

        SoundCue(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
                int sourceUnitId, long heardAt) {
            this.pos = pos;
            this.kind = kind;
            this.sourceFaction = sourceFaction;
            this.sourceUnitId = sourceUnitId;
            this.heardAt = heardAt;
        }
    }

    private static final Map<ServerLevel, AwarenessCueRegistry> LEVELS = new WeakHashMap<>();

    final Map<Long, List<SoundCue>> chunkCues = new ConcurrentHashMap<>();
    private final Map<Long, Long> dedupeDeadline = new ConcurrentHashMap<>();

    static AwarenessCueRegistry of(ServerLevel level) {
        synchronized (LEVELS) {
            return LEVELS.computeIfAbsent(level, ignored -> new AwarenessCueRegistry());
        }
    }

    void register(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
            int sourceUnitId, long now) {
        this.dedupeDeadline.entrySet().removeIf(e -> e.getValue() <= now);
        long cellKey = cellKey(pos, kind);
        Long next = this.dedupeDeadline.get(cellKey);
        if (next != null && now < next) return;
        this.dedupeDeadline.put(cellKey, now + kind.dedupeTicks);

        long ck = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        List<SoundCue> list = this.chunkCues.computeIfAbsent(ck, ignored -> new ArrayList<>(2));
        if (list.size() >= MAX_CUES_PER_CHUNK) {
            list.remove(0);
        }
        list.add(new SoundCue(pos.immutable(), kind, sourceFaction, sourceUnitId, now));
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
        Long next = this.dedupeDeadline.get(cellKey(pos, kind));
        return next != null && now < next;
    }

    void registerForTest(BlockPos pos, TriggerKind kind, long now) {
        register(pos, kind, null, -1, now);
    }

    static boolean expired(SoundCue cue, long now) {
        return Facts.ticksSince(cue.heardAt, now) >= Facts.CONTACT_MEMORY_TICKS;
    }

    /** Package-visible empty registry for headless self-check (no ServerLevel). */
    static AwarenessCueRegistry forTest() {
        return new AwarenessCueRegistry();
    }

    private AwarenessCueRegistry() {}
}
