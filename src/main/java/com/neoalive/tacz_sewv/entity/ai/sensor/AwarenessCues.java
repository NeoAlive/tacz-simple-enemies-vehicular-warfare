package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Shared investigate layer for ground crews: merges outer-ring entity spots with audible sound
 * cues, then publishes {@link Facts#outerSpotFresh} / {@link Facts.Memory#noteSpot} — never
 * {@code setTarget}.
 *
 * <p>Per-hull instance owned by {@code DriveVehicleGoal}. Sound cues live in a sparse per-level
 * registry ({@link Registry}).
 */
public final class AwarenessCues {

    private static final Logger LOG = LogUtils.getLogger();

    /** Max hear radius across sound triggers — bounds chunk queries on consume. */
    public static final int MAX_SOUND_HEAR_RADIUS = 256;
    static final int GLANCE_HOLD_TICKS = 40;

    private static final double CELL_SIZE = 4.0;
    private static final int MAX_CUES_PER_CHUNK = 8;
    private static final int[] DEDUPE_INTERVAL = {0, 40, 10, 60}; // index = TriggerKind.ordinal

    public enum TriggerKind {
        OUTER_ENTITY(0, 1.0, 1.0),
        VEHICLE_ENGINE(60, 0.30, 0.35),
        VEHICLE_CANNON(256, 1.0, 0.85),
        CREW_VOICE(40, 0.15, 0.25);

        final int hearRadius;
        final double triggerChance;
        final double strength;

        TriggerKind(int hearRadius, double triggerChance, double strength) {
            this.hearRadius = hearRadius;
            this.triggerChance = triggerChance;
            this.strength = strength;
        }
    }

    private VehicleEntity hull;

    private int entitySpotId = -1;
    @Nullable
    private BlockPos entitySpotPos;
    private long entitySpotSeen = Long.MIN_VALUE;
    private double entitySpotDist = Double.MAX_VALUE;
    private double entitySpotStrength;

    @Nullable
    private BlockPos soundSpotPos;
    private long soundSpotSeen = Long.MIN_VALUE;
    private double soundSpotDist = Double.MAX_VALUE;
    private double soundSpotStrength;

    @Nullable
    private Vec3 glanceBearing;
    private long glanceUntil = Long.MIN_VALUE;

    public void clear() {
        this.hull = null;
        dropEntitySpot();
        dropSoundSpot();
    }

    /**
     * Outer-ring poll or foliage contact. Replaces the held entity spot when strength wins the
     * merge pre-check.
     */
    public void offerEntitySpot(int entityId, BlockPos pos, double dist, double strength, long now,
            @Nullable LivingEntity glanceAt) {
        if (entityId < 0 || pos == null) return;
        if (this.entitySpotId >= 0) {
            if (strength < this.entitySpotStrength) return;
            if (strength == this.entitySpotStrength && dist >= this.entitySpotDist) return;
        }
        this.entitySpotId = entityId;
        this.entitySpotPos = pos;
        this.entitySpotDist = dist;
        this.entitySpotStrength = strength;
        this.entitySpotSeen = now;
        if (glanceAt != null && this.hull != null) {
            armGlance(this.hull, glanceAt, now);
        }
    }

    /** Validates and refreshes the entity spot held from a prior outer-ring poll. */
    public void refreshEntitySpot(AbstractUnit unit, VehicleEntity vehicle, long now) {
        if (this.entitySpotId < 0) return;
        Entity e = unit.level().getEntity(this.entitySpotId);
        if (!(e instanceof LivingEntity living) || !living.isAlive()) {
            dropEntitySpot();
            return;
        }
        BlockPos pos = living.blockPosition();
        if (!unit.level().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            dropEntitySpot();
            return;
        }
        if (unit.getTarget() == living) {
            dropEntitySpot();
            return;
        }
        if (Facts.ticksSince(this.entitySpotSeen, now) >= Facts.CONTACT_MEMORY_TICKS) {
            dropEntitySpot();
            return;
        }
        this.entitySpotPos = pos;
        this.entitySpotDist = Math.sqrt(horizontalDistSq(vehicle, living));
    }

    /**
     * Merge outer-ring + sound cues and publish into Facts. Call after {@link OuterRingAwareness#tick}.
     */
    public void tick(AbstractUnit unit, VehicleEntity vehicle, Facts facts) {
        this.hull = vehicle;
        long now = unit.level().getGameTime();

        if (!SewvConfig.SPEC.isLoaded()
                || (!SewvConfig.OUTER_RING_ENABLED.get() && !SewvConfig.AWARENESS_CUES_ENABLED.get())) {
            clearFacts(facts);
            return;
        }
        if (unit.getTarget() != null || facts.underOrders) {
            clearFacts(facts);
            return;
        }

        refreshEntitySpot(unit, vehicle, now);
        consumeSoundCues(unit, vehicle, now);
        publish(unit, vehicle, facts, now);
    }

    private void consumeSoundCues(AbstractUnit unit, VehicleEntity vehicle, long now) {
        dropSoundSpot();
        if (!SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        if (!(unit.level() instanceof ServerLevel level)) return;

        double hx = vehicle.getX();
        double hz = vehicle.getZ();
        int chunkRadius = (MAX_SOUND_HEAR_RADIUS + 15) >> 4;
        int cx0 = (Mth.floor(hx) >> 4) - chunkRadius;
        int cz0 = (Mth.floor(hz) >> 4) - chunkRadius;
        int cx1 = (Mth.floor(hx) >> 4) + chunkRadius;
        int cz1 = (Mth.floor(hz) >> 4) + chunkRadius;

        BlockPos bestPos = null;
        double bestStrength = -1.0;
        double bestDist = Double.MAX_VALUE;
        long bestSeen = Long.MIN_VALUE;

        Registry reg = Registry.of(level);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<SoundCue> cues = reg.chunkCues.get(Registry.chunkKey(cx, cz));
                if (cues == null || cues.isEmpty()) continue;
                Iterator<SoundCue> it = cues.iterator();
                while (it.hasNext()) {
                    SoundCue cue = it.next();
                    if (Facts.ticksSince(cue.heardAt, now) >= Facts.CONTACT_MEMORY_TICKS) {
                        it.remove();
                        continue;
                    }
                    if (!cueAudible(unit, vehicle, cue, hx, hz, now)) continue;

                    double dist = Math.sqrt(horizontalDistSq(hx, hz, cue.pos));
                    if (cue.strength > bestStrength
                            || (cue.strength == bestStrength && dist < bestDist)
                            || (cue.strength == bestStrength && dist == bestDist && cue.heardAt > bestSeen)) {
                        bestPos = cue.pos;
                        bestStrength = cue.strength;
                        bestDist = dist;
                        bestSeen = cue.heardAt;
                    }
                }
            }
        }

        if (bestPos != null) {
            this.soundSpotPos = bestPos;
            this.soundSpotDist = bestDist;
            this.soundSpotStrength = bestStrength;
            this.soundSpotSeen = bestSeen;
            armGlancePos(vehicle, bestPos, now);
        }
    }

    private boolean cueAudible(AbstractUnit unit, VehicleEntity vehicle, SoundCue cue,
            double hx, double hz, long now) {
        double dx = cue.pos.getX() + 0.5 - hx;
        double dz = cue.pos.getZ() + 0.5 - hz;
        double distSq = dx * dx + dz * dz;
        double radius = cue.kind.hearRadius;
        if (distSq > radius * radius) return false;

        if (cue.sourceFaction != null) {
            CrewFacts.Faction listener = listenerFaction(unit);
            if (listener != null && listener == cue.sourceFaction) return false;
        }
        if (cue.sourceUnitId >= 0) {
            Entity src = unit.level().getEntity(cue.sourceUnitId);
            if (src instanceof LivingEntity living && VehicleTargeting.isNonHostile(unit, living)) {
                return false;
            }
        }
        return rollTrigger(vehicle.getId(), cue.pos.asLong(), cue.kind, cue.kind.triggerChance);
    }

    @Nullable
    private static CrewFacts.Faction listenerFaction(AbstractUnit unit) {
        return CrewFacts.factionOfCrew(unit);
    }

    private void publish(AbstractUnit unit, VehicleEntity vehicle, Facts facts, long now) {
        boolean entityFresh = this.entitySpotId >= 0 && this.entitySpotPos != null
                && Facts.ticksSince(this.entitySpotSeen, now) < Facts.CONTACT_MEMORY_TICKS;
        boolean soundFresh = this.soundSpotPos != null
                && Facts.ticksSince(this.soundSpotSeen, now) < Facts.CONTACT_MEMORY_TICKS;

        double strength = 0.0;
        double dist = Double.MAX_VALUE;
        BlockPos aim = null;

        if (entityFresh && soundFresh) {
            if (this.entitySpotStrength > this.soundSpotStrength
                    || (this.entitySpotStrength == this.soundSpotStrength
                            && this.entitySpotDist <= this.soundSpotDist)) {
                strength = this.entitySpotStrength;
                dist = this.entitySpotDist;
                aim = this.entitySpotPos;
            } else {
                strength = this.soundSpotStrength;
                dist = this.soundSpotDist;
                aim = this.soundSpotPos;
            }
        } else if (entityFresh) {
            strength = this.entitySpotStrength;
            dist = this.entitySpotDist;
            aim = this.entitySpotPos;
        } else if (soundFresh) {
            strength = this.soundSpotStrength;
            dist = this.soundSpotDist;
            aim = this.soundSpotPos;
        }

        boolean fresh = aim != null;
        facts.outerSpotFresh = fresh;
        facts.outerSpotDist = fresh ? dist : Double.MAX_VALUE;
        facts.outerSpotStrength = fresh ? strength : 0.0;

        if (fresh && unit.getTarget() == null) {
            facts.memory.noteSpot(aim, now);
        }

        if (this.glanceBearing != null && now < this.glanceUntil && unit.getTarget() == null) {
            facts.outerGlanceBearing = this.glanceBearing;
            facts.outerGlanceUntil = this.glanceUntil;
        } else {
            this.glanceBearing = null;
            this.glanceUntil = Long.MIN_VALUE;
            facts.outerGlanceBearing = null;
            facts.outerGlanceUntil = Long.MIN_VALUE;
        }
    }

    private void armGlance(VehicleEntity vehicle, LivingEntity spot, long now) {
        if (!vehicle.hasTurret()) return;
        Vec3 aim = new Vec3(
                spot.getX() - vehicle.getX(),
                spot.getEyeY() - vehicle.getEyeY(),
                spot.getZ() - vehicle.getZ());
        if (aim.lengthSqr() < 1.0E-4) return;
        this.glanceBearing = aim.normalize();
        this.glanceUntil = now + GLANCE_HOLD_TICKS;
    }

    private void armGlancePos(VehicleEntity vehicle, BlockPos pos, long now) {
        if (!vehicle.hasTurret()) return;
        Vec3 aim = new Vec3(
                pos.getX() + 0.5 - vehicle.getX(),
                pos.getY() + 1.0 - vehicle.getEyeY(),
                pos.getZ() + 0.5 - vehicle.getZ());
        if (aim.lengthSqr() < 1.0E-4) return;
        this.glanceBearing = aim.normalize();
        this.glanceUntil = now + GLANCE_HOLD_TICKS;
    }

    private void dropEntitySpot() {
        this.entitySpotId = -1;
        this.entitySpotPos = null;
        this.entitySpotSeen = Long.MIN_VALUE;
        this.entitySpotDist = Double.MAX_VALUE;
        this.entitySpotStrength = 0.0;
    }

    private void dropSoundSpot() {
        this.soundSpotPos = null;
        this.soundSpotSeen = Long.MIN_VALUE;
        this.soundSpotDist = Double.MAX_VALUE;
        this.soundSpotStrength = 0.0;
    }

    private static void clearFacts(Facts facts) {
        facts.outerSpotFresh = false;
        facts.outerSpotDist = Double.MAX_VALUE;
        facts.outerSpotStrength = 0.0;
        facts.outerGlanceBearing = null;
        facts.outerGlanceUntil = Long.MIN_VALUE;
    }

    private static double horizontalDistSq(VehicleEntity v, LivingEntity e) {
        return horizontalDistSq(v.getX(), v.getZ(), e.getX(), e.getZ());
    }

    private static double horizontalDistSq(double hx, double hz, BlockPos pos) {
        return horizontalDistSq(hx, hz, pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    private static double horizontalDistSq(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        return dx * dx + dz * dz;
    }

    /** Deterministic per-hull chance roll — stable in multiplayer. Package-visible for self-check. */
    static boolean rollTrigger(int hullId, long posLong, TriggerKind kind, double chance) {
        if (chance >= 1.0) return true;
        if (chance <= 0.0) return false;
        int h = hullId ^ Long.hashCode(posLong) ^ kind.ordinal() * 31;
        int v = Math.floorMod(h, 1000);
        return v < (int) (chance * 1000);
    }

    private static void debug(String msg, Object... args) {
        if (!ModGameRules.server(ModGameRules.OUTER_RING_DEBUG_LOGGING)) return;
        LOG.info("[sewv-cues] " + msg, args);
    }

    // ---- global sound registry ----

    public static void registerSound(ServerLevel level, BlockPos pos, TriggerKind kind,
            @Nullable CrewFacts.Faction sourceFaction, int sourceUnitId) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        Registry.of(level).register(pos, kind, sourceFaction, sourceUnitId, level.getGameTime());
    }

    /**
     * {@code SoundTool.playDistantSound} — vehicle/emplacement fire that never hits
     * {@code Level.playSound}.
     */
    public static void registerDistantSound(ServerLevel level, SoundEvent sound, Vec3 pos,
            @Nullable Entity sender) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        if (!"superbwarfare".equals(sound.getLocation().getNamespace())) return;
        AwarenessCues.TriggerKind kind = AwarenessCueSounds.classifyDistant(sound, sender);
        if (kind == null) return;

        CrewFacts.Faction faction = null;
        int unitId = -1;
        if (sender instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        } else if (sender instanceof AbstractUnit unit) {
            faction = CrewFacts.factionOfCrew(unit);
            unitId = unit.getId();
        } else if (sender instanceof LivingEntity living
                && living.getVehicle() instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        }
        registerSound(level, BlockPos.containing(pos), kind, faction, unitId);
    }

    /** Server-side engine cue for moving hulls (track loop sounds are client-only). */
    public static void registerEngine(ServerLevel level, VehicleEntity vehicle) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        registerSound(level, vehicle.blockPosition(), TriggerKind.VEHICLE_ENGINE,
                CrewFacts.factionOf(vehicle), -1);
    }

    /** Direct path from {@link com.neoalive.tacz_sewv.crew.CrewRadio}. */
    public static void registerCrewVoice(ServerLevel level, AbstractUnit speaker, BlockPos pos) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(speaker);
        Registry.of(level).register(pos, TriggerKind.CREW_VOICE, faction, speaker.getId(),
                level.getGameTime());
    }

    static final class SoundCue {
        final BlockPos pos;
        final TriggerKind kind;
        final double strength;
        @Nullable
        final CrewFacts.Faction sourceFaction;
        final int sourceUnitId;
        final long heardAt;

        SoundCue(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
                int sourceUnitId, long heardAt) {
            this.pos = pos;
            this.kind = kind;
            this.strength = kind.strength;
            this.sourceFaction = sourceFaction;
            this.sourceUnitId = sourceUnitId;
            this.heardAt = heardAt;
        }
    }

    static final class Registry {
        private static final Map<ServerLevel, Registry> LEVELS = new WeakHashMap<>();
        private static final Map<ServerLevel, Registry> LEVELS_SYNC = java.util.Collections
                .synchronizedMap(LEVELS);

        final Map<Long, List<SoundCue>> chunkCues = new ConcurrentHashMap<>();
        private final Map<Long, Long> dedupeDeadline = new ConcurrentHashMap<>();

        static Registry of(ServerLevel level) {
            synchronized (LEVELS_SYNC) {
                return LEVELS.computeIfAbsent(level, ignored -> new Registry());
            }
        }

        void register(BlockPos pos, TriggerKind kind, @Nullable CrewFacts.Faction sourceFaction,
                int sourceUnitId, long now) {
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

        static long chunkKey(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }

        private static long cellKey(BlockPos pos, TriggerKind kind) {
            int x = (int) (pos.getX() / CELL_SIZE);
            int y = (int) (pos.getY() / CELL_SIZE);
            int z = (int) (pos.getZ() / CELL_SIZE);
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
    }

    /** Package-visible merge ordering for self-check. */
    static int compareSpots(double s1, double d1, long t1, double s2, double d2, long t2) {
        if (s1 > s2) return 1;
        if (s1 < s2) return -1;
        if (d1 < d2) return 1;
        if (d1 > d2) return -1;
        if (t1 > t2) return 1;
        if (t1 < t2) return -1;
        return 0;
    }
}
