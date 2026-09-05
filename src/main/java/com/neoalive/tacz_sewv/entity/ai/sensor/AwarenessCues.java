package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueRegistry.Reject;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueRegistry.SoundCue;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Per-hull investigate merge/publish layer: outer-ring entity spots + audible sound cues from
 * {@link AwarenessCueRegistry}, then {@link Facts#outerSpotFresh} / {@link Facts.Memory#noteSpot}
 * — never {@code setTarget}.
 *
 * <p>Owned by {@code DriveVehicleGoal}. Tick order (load-bearing): OuterRing offers → this
 * {@link #tick} publishes → {@code TacticalBrain.update}. {@code facts.underOrders} here may be
 * one Facts-refresh stale; that is acceptable. {@link #clearFacts} must not clear Memory.
 */
public final class AwarenessCues {

    private static final Logger LOG = LogUtils.getLogger();

    /** Max hear radius across sound kinds — bounds chunk queries on consume. */
    public static final int MAX_SOUND_HEAR_RADIUS = AwarenessCueRegistry.TriggerKind.maxHearRadius();

    static final int GLANCE_HOLD_TICKS = 40;
    /** Debug summary cadence (game ticks) — not every driver tick. */
    private static final int DEBUG_SUMMARY_INTERVAL = 40;

    /**
     * Nested name kept for mixins / self-check / {@link AwarenessCueSounds}; values delegate to
     * {@link AwarenessCueRegistry.TriggerKind}.
     */
    public enum TriggerKind {
        OUTER_ENTITY(AwarenessCueRegistry.TriggerKind.OUTER_ENTITY),
        VEHICLE_ENGINE(AwarenessCueRegistry.TriggerKind.VEHICLE_ENGINE),
        VEHICLE_CANNON(AwarenessCueRegistry.TriggerKind.VEHICLE_CANNON),
        CREW_VOICE(AwarenessCueRegistry.TriggerKind.CREW_VOICE),
        TACZ_FIRE(AwarenessCueRegistry.TriggerKind.TACZ_FIRE),
        DRONE(AwarenessCueRegistry.TriggerKind.DRONE),
        PLAYER_HURT(AwarenessCueRegistry.TriggerKind.PLAYER_HURT),
        PLAYER_EAT(AwarenessCueRegistry.TriggerKind.PLAYER_EAT);

        final AwarenessCueRegistry.TriggerKind delegate;

        TriggerKind(AwarenessCueRegistry.TriggerKind delegate) {
            this.delegate = delegate;
        }

        AwarenessCueRegistry.TriggerKind unwrap() {
            return this.delegate;
        }

        static TriggerKind wrap(AwarenessCueRegistry.TriggerKind k) {
            return switch (k) {
                case OUTER_ENTITY -> OUTER_ENTITY;
                case VEHICLE_ENGINE -> VEHICLE_ENGINE;
                case VEHICLE_CANNON -> VEHICLE_CANNON;
                case CREW_VOICE -> CREW_VOICE;
                case TACZ_FIRE -> TACZ_FIRE;
                case DRONE -> DRONE;
                case PLAYER_HURT -> PLAYER_HURT;
                case PLAYER_EAT -> PLAYER_EAT;
            };
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

    /** Per-cue chance latch for this hull: cueKey → heard. Cleared when the cue expires. */
    private final Map<Long, Boolean> chanceLatch = new ConcurrentHashMap<>();
    private long nextDebugSummary = Long.MIN_VALUE;

    public void clear() {
        this.hull = null;
        dropEntitySpot();
        dropSoundSpot();
        this.chanceLatch.clear();
        this.glanceBearing = null;
        this.glanceUntil = Long.MIN_VALUE;
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
        if (unit.getTarget() != null) {
            clearFacts(facts);
            debugGate(unit, vehicle, now, Reject.GATED_COMBAT);
            return;
        }
        if (facts.underOrders) {
            clearFacts(facts);
            debugGate(unit, vehicle, now, Reject.GATED_ORDERS);
            return;
        }

        refreshEntitySpot(unit, vehicle, now);
        ConsumeStats stats = consumeSoundCues(unit, vehicle, now);
        publish(unit, vehicle, facts, now, stats);
    }

    private ConsumeStats consumeSoundCues(AbstractUnit unit, VehicleEntity vehicle, long now) {
        ConsumeStats stats = new ConsumeStats();
        // Keep prior sound spot only if still fresh and no better cue wins — drop then refill so
        // an empty scan clears investigate; reject tallies explain why.
        dropSoundSpot();
        if (!SewvConfig.AWARENESS_CUES_ENABLED.get()) return stats;
        if (!(unit.level() instanceof ServerLevel level)) return stats;

        double hx = vehicle.getX();
        double hz = vehicle.getZ();
        int maxHear = AwarenessCueRegistry.TriggerKind.maxHearRadius();
        int chunkRadius = (maxHear + 15) >> 4;
        int cx0 = (Mth.floor(hx) >> 4) - chunkRadius;
        int cz0 = (Mth.floor(hz) >> 4) - chunkRadius;
        int cx1 = (Mth.floor(hx) >> 4) + chunkRadius;
        int cz1 = (Mth.floor(hz) >> 4) + chunkRadius;

        BlockPos bestPos = null;
        double bestStrength = -1.0;
        double bestDist = Double.MAX_VALUE;
        long bestSeen = Long.MIN_VALUE;
        AwarenessCueRegistry.TriggerKind bestKind = null;

        AwarenessCueRegistry reg = AwarenessCueRegistry.of(level);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<SoundCue> cues = reg.chunkCues.get(AwarenessCueRegistry.chunkKey(cx, cz));
                if (cues == null || cues.isEmpty()) {
                    if (cues != null && cues.isEmpty()) {
                        reg.chunkCues.remove(AwarenessCueRegistry.chunkKey(cx, cz));
                    }
                    continue;
                }
                Iterator<SoundCue> it = cues.iterator();
                while (it.hasNext()) {
                    SoundCue cue = it.next();
                    if (AwarenessCueRegistry.expired(cue, now)) {
                        it.remove();
                        this.chanceLatch.remove(cue.cueKey);
                        stats.count(Reject.EXPIRED);
                        continue;
                    }
                    Reject reject = cueAudible(unit, vehicle, cue, hx, hz);
                    if (reject != null) {
                        stats.count(reject);
                        continue;
                    }
                    stats.audible++;

                    double dist = Math.sqrt(horizontalDistSq(hx, hz, cue.pos));
                    if (cue.strength > bestStrength
                            || (cue.strength == bestStrength && dist < bestDist)
                            || (cue.strength == bestStrength && dist == bestDist
                                    && cue.heardAt > bestSeen)) {
                        bestPos = cue.pos;
                        bestStrength = cue.strength;
                        bestDist = dist;
                        bestSeen = cue.heardAt;
                        bestKind = cue.kind;
                    }
                }
                if (cues.isEmpty()) {
                    reg.chunkCues.remove(AwarenessCueRegistry.chunkKey(cx, cz));
                }
            }
        }

        if (bestPos != null) {
            this.soundSpotPos = bestPos;
            this.soundSpotDist = bestDist;
            this.soundSpotStrength = bestStrength;
            this.soundSpotSeen = bestSeen;
            stats.bestKind = bestKind;
            armGlancePos(vehicle, bestPos, now);
        }
        return stats;
    }

    /**
     * @return reject reason, or {@code null} if the cue is audible to this hull
     */
    @Nullable
    private Reject cueAudible(AbstractUnit unit, VehicleEntity vehicle, SoundCue cue,
            double hx, double hz) {
        double dx = cue.pos.getX() + 0.5 - hx;
        double dz = cue.pos.getZ() + 0.5 - hz;
        double distSq = dx * dx + dz * dz;
        double radius = cue.kind.hearRadius;
        if (distSq > radius * radius) return Reject.RANGE;

        if (cue.sourceFaction != null) {
            CrewFacts.Faction listener = CrewFacts.factionOfCrew(unit);
            if (listener != null && listener == cue.sourceFaction) return Reject.SAME_FACTION;
        }
        if (cue.sourceUnitId >= 0) {
            Entity src = unit.level().getEntity(cue.sourceUnitId);
            if (src instanceof LivingEntity living && VehicleTargeting.isNonHostile(unit, living)) {
                return Reject.FRIENDLY_SOURCE;
            }
        }

        Boolean latched = this.chanceLatch.get(cue.cueKey);
        if (latched == null) {
            boolean pass = rollTrigger(vehicle.getId(), cue.pos.asLong(),
                    TriggerKind.wrap(cue.kind), cue.kind.triggerChance);
            this.chanceLatch.put(cue.cueKey, pass);
            latched = pass;
        }
        return latched ? null : Reject.CHANCE;
    }

    private void publish(AbstractUnit unit, VehicleEntity vehicle, Facts facts, long now,
            ConsumeStats stats) {
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

        boolean noted = false;
        if (fresh && unit.getTarget() == null) {
            facts.memory.noteSpot(aim, now);
            noted = true;
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

        debugSummary(unit, vehicle, now, stats, fresh, noted, strength, dist);
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

    /** Clears outer-spot Facts fields only — never Memory. */
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

    /** Package-visible: latch result for a cue key (self-check). */
    Boolean latchGet(long cueKey) {
        return this.chanceLatch.get(cueKey);
    }

    void latchPut(long cueKey, boolean pass) {
        this.chanceLatch.put(cueKey, pass);
    }

    private void debugGate(AbstractUnit unit, VehicleEntity vehicle, long now, Reject gate) {
        if (!ClientConfig.flag(ClientConfig.OUTER_RING_DEBUG_LOGGING)) return;
        if (this.nextDebugSummary != Long.MIN_VALUE && now < this.nextDebugSummary) return;
        this.nextDebugSummary = now + DEBUG_SUMMARY_INTERVAL;
        LOG.info("[sewv-cues] hull=#{} gated={} pos={}", vehicle.getId(), gate, vehicle.blockPosition());
    }

    private void debugSummary(AbstractUnit unit, VehicleEntity vehicle, long now, ConsumeStats stats,
            boolean fresh, boolean noted, double strength, double dist) {
        if (!ClientConfig.flag(ClientConfig.OUTER_RING_DEBUG_LOGGING)) return;
        if (this.nextDebugSummary != Long.MIN_VALUE && now < this.nextDebugSummary) return;
        this.nextDebugSummary = now + DEBUG_SUMMARY_INTERVAL;
        LOG.info(
                "[sewv-cues] hull=#{} audible={} best={} outer={} noted={} str={} dist={} "
                        + "reject={{range={},faction={},friendly={},chance={},expired={}}}",
                vehicle.getId(), stats.audible, stats.bestKind, fresh, noted,
                String.format("%.2f", strength),
                Double.isInfinite(dist) || dist > 1.0E6 ? "-" : String.format("%.0f", dist),
                stats.range, stats.sameFaction, stats.friendly, stats.chance, stats.expired);
    }

    // ---- register facades (mixins / CrewRadio keep calling AwarenessCues) ----

    public static void registerSound(ServerLevel level, BlockPos pos, TriggerKind kind,
            @Nullable CrewFacts.Faction sourceFaction, int sourceUnitId) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        AwarenessCueRegistry.of(level).register(pos, kind.unwrap(), sourceFaction, sourceUnitId,
                level.getGameTime());
    }

    /**
     * {@code SoundTool.playDistantSound} — vehicle/emplacement fire that never hits
     * {@code Level.playSound}.
     */
    public static void registerDistantSound(ServerLevel level, SoundEvent sound, Vec3 pos,
            @Nullable Entity sender) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        if (!"superbwarfare".equals(sound.getLocation().getNamespace())) return;
        TriggerKind kind = AwarenessCueSounds.classifyDistant(sound, sender);
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

    /**
     * TaCZ shot — from {@link AwarenessCueEvents} / {@code GunFireEvent}. Registry dedupe is 6 s
     * over a 16-block cell so full-auto does not flood the cue list.
     */
    public static void registerTaczFire(ServerLevel level, LivingEntity shooter) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        CrewFacts.Faction faction = null;
        int unitId = -1;
        if (shooter instanceof AbstractUnit unit) {
            faction = CrewFacts.factionOfCrew(unit);
            unitId = unit.getId();
        } else if (shooter.getVehicle() instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        }
        registerSound(level, shooter.blockPosition(), TriggerKind.TACZ_FIRE, faction, unitId);
    }

    /**
     * SBW drone (or swarm drone) in motion — engine loops are client-only; this mirrors
     * {@link #registerEngine} with vertical motion counted and a dedicated kind.
     */
    public static void registerDrone(ServerLevel level, VehicleEntity drone) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        registerSound(level, drone.blockPosition(), TriggerKind.DRONE,
                CrewFacts.factionOf(drone), -1);
    }

    /** Direct path from {@link com.neoalive.tacz_sewv.crew.CrewRadio}. */
    public static void registerCrewVoice(ServerLevel level, AbstractUnit speaker, BlockPos pos) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(speaker);
        AwarenessCueRegistry.of(level).register(pos, AwarenessCueRegistry.TriggerKind.CREW_VOICE,
                faction, speaker.getId(), level.getGameTime());
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

    private static final class ConsumeStats {
        int audible;
        int range;
        int sameFaction;
        int friendly;
        int chance;
        int expired;
        @Nullable
        AwarenessCueRegistry.TriggerKind bestKind;

        void count(Reject r) {
            switch (r) {
                case RANGE -> this.range++;
                case SAME_FACTION -> this.sameFaction++;
                case FRIENDLY_SOURCE -> this.friendly++;
                case CHANCE -> this.chance++;
                case EXPIRED -> this.expired++;
                default -> {}
            }
        }
    }
}
