package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueRegistry.SoundCue;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueRegistry.TriggerKind;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Per-hull investigate layer: gunfire a crew can hear ({@link AwarenessCueRegistry}) and contacts its
 * scan saw only through leaves, published as {@link Facts#outerSpotFresh} / {@link Facts.Memory#noteSpot}
 * — never {@code setTarget}.
 *
 * <p>Owned by {@code DriveVehicleGoal}, ticked before {@code TacticalBrain.update} so
 * {@code DISTANT_CONTACT} sees this tick's fields. {@link #clearFacts} must not clear Memory.
 */
public final class AwarenessCues {

    /** Max hear radius across cue kinds — bounds chunk queries on consume. */
    public static final int MAX_SOUND_HEAR_RADIUS = TriggerKind.maxHearRadius();

    /** Strength a foliage-only contact publishes at — the strongest cue there is, something is right there. */
    private static final double FOLIAGE_STRENGTH = 1.0;

    /** Foliage-only contacts from the mounted cylinder scan, keyed by hull id; consumed on the next {@link #tick}. */
    private static final ConcurrentHashMap<Integer, Integer> FOLIAGE_OFFERS = new ConcurrentHashMap<>();

    private int entitySpotId = -1;
    @Nullable
    private BlockPos entitySpotPos;
    private long entitySpotSeen = Long.MIN_VALUE;
    private double entitySpotDist = Double.MAX_VALUE;

    @Nullable
    private BlockPos soundSpotPos;
    private double soundSpotDist = Double.MAX_VALUE;
    private double soundSpotStrength;

    @Nullable
    private VehicleEntity hull;

    public void clear() {
        if (this.hull != null) forget(this.hull.getId());
        this.hull = null;
        dropEntitySpot();
        dropSoundSpot();
    }

    /**
     * Inner-cylinder contact visible only through leaves — not engageable, but something is there.
     * Called from the target scan; the hull picks it up on its next {@link #tick}.
     */
    public static void offerFoliageContact(VehicleEntity hull, LivingEntity contact) {
        if (hull == null || contact == null || !contact.isAlive()) return;
        FOLIAGE_OFFERS.put(hull.getId(), contact.getId());
    }

    /** Drop a pending offer for a hull that left the level without {@link #clear()} running. */
    public static void forget(int hullId) {
        FOLIAGE_OFFERS.remove(hullId);
    }

    /** Merge the foliage contact and the loudest audible gunfire, and publish into Facts. */
    public void tick(AbstractUnit unit, VehicleEntity vehicle, Facts facts) {
        if (this.hull != vehicle) {
            clear();
            this.hull = vehicle;
        }
        Integer foliage = FOLIAGE_OFFERS.remove(vehicle.getId());
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()
                || unit.getTarget() != null || facts.underOrders) {
            clearFacts(facts);
            return;
        }
        long now = unit.level().getGameTime();
        if (foliage != null) takeFoliage(unit, vehicle, foliage, now);
        refreshEntitySpot(unit, vehicle, now);
        consumeSoundCues(unit, vehicle, now);
        publish(unit, facts, now);
    }

    private void takeFoliage(AbstractUnit unit, VehicleEntity vehicle, int id, long now) {
        if (!(unit.level().getEntity(id) instanceof LivingEntity living) || !living.isAlive()) return;
        this.entitySpotId = id;
        this.entitySpotPos = living.blockPosition();
        this.entitySpotDist = Math.sqrt(horizontalDistSq(vehicle.getX(), vehicle.getZ(), living.getX(), living.getZ()));
        this.entitySpotSeen = now;
    }

    /** Validates and refreshes the held foliage spot. */
    private void refreshEntitySpot(AbstractUnit unit, VehicleEntity vehicle, long now) {
        if (this.entitySpotId < 0) return;
        Entity e = unit.level().getEntity(this.entitySpotId);
        if (!(e instanceof LivingEntity living) || !living.isAlive()
                || !unit.level().hasChunk(living.getBlockX() >> 4, living.getBlockZ() >> 4)
                || Facts.ticksSince(this.entitySpotSeen, now) >= Facts.CONTACT_MEMORY_TICKS) {
            dropEntitySpot();
            return;
        }
        this.entitySpotPos = living.blockPosition();
        this.entitySpotDist = Math.sqrt(horizontalDistSq(vehicle.getX(), vehicle.getZ(), living.getX(), living.getZ()));
    }

    private void consumeSoundCues(AbstractUnit unit, VehicleEntity vehicle, long now) {
        // Drop then refill, so an empty scan clears the investigate point.
        dropSoundSpot();
        if (!(unit.level() instanceof ServerLevel level)) return;

        double hx = vehicle.getX();
        double hz = vehicle.getZ();
        int chunkRadius = (MAX_SOUND_HEAR_RADIUS + 15) >> 4;
        int hcx = Mth.floor(hx) >> 4;
        int hcz = Mth.floor(hz) >> 4;

        AwarenessCueRegistry reg = AwarenessCueRegistry.of(level);
        long bestSeen = Long.MIN_VALUE;
        for (int cx = hcx - chunkRadius; cx <= hcx + chunkRadius; cx++) {
            for (int cz = hcz - chunkRadius; cz <= hcz + chunkRadius; cz++) {
                long key = AwarenessCueRegistry.chunkKey(cx, cz);
                List<SoundCue> cues = reg.chunkCues.get(key);
                if (cues == null) continue;
                Iterator<SoundCue> it = cues.iterator();
                while (it.hasNext()) {
                    SoundCue cue = it.next();
                    if (AwarenessCueRegistry.expired(cue, now)) {
                        it.remove();
                        continue;
                    }
                    if (!audible(unit, vehicle, cue, hx, hz)) continue;
                    double dist = Math.sqrt(horizontalDistSq(hx, hz, cue.pos.getX() + 0.5, cue.pos.getZ() + 0.5));
                    if (compareSpots(cue.kind.strength, dist, cue.heardAt,
                            this.soundSpotStrength, this.soundSpotDist, bestSeen) > 0) {
                        this.soundSpotPos = cue.pos;
                        this.soundSpotStrength = cue.kind.strength;
                        this.soundSpotDist = dist;
                        bestSeen = cue.heardAt;
                    }
                }
                if (cues.isEmpty()) reg.chunkCues.remove(key);
            }
        }
    }

    private static boolean audible(AbstractUnit unit, VehicleEntity vehicle, SoundCue cue, double hx, double hz) {
        double radius = cue.kind.hearRadius;
        if (horizontalDistSq(hx, hz, cue.pos.getX() + 0.5, cue.pos.getZ() + 0.5) > radius * radius) return false;
        if (cue.sourceFaction != null && cue.sourceFaction == CrewFacts.factionOfCrew(unit)) return false;
        if (cue.sourceUnitId >= 0 && unit.level().getEntity(cue.sourceUnitId) instanceof LivingEntity src
                && VehicleTargeting.isNonHostile(unit, src)) {
            return false;
        }
        // Deterministic per hull + spot, so a crew that ignored a burst keeps ignoring it.
        return rollTrigger(vehicle.getId(), cue.pos.asLong(), cue.kind, cue.kind.triggerChance);
    }

    private void publish(AbstractUnit unit, Facts facts, long now) {
        boolean entityFresh = this.entitySpotPos != null;
        boolean soundFresh = this.soundSpotPos != null;
        BlockPos aim;
        double strength;
        double dist;
        if (entityFresh && (!soundFresh || compareSpots(FOLIAGE_STRENGTH, this.entitySpotDist, 0,
                this.soundSpotStrength, this.soundSpotDist, 0) >= 0)) {
            aim = this.entitySpotPos;
            strength = FOLIAGE_STRENGTH;
            dist = this.entitySpotDist;
        } else if (soundFresh) {
            aim = this.soundSpotPos;
            strength = this.soundSpotStrength;
            dist = this.soundSpotDist;
        } else {
            clearFacts(facts);
            return;
        }
        facts.outerSpotFresh = true;
        facts.outerSpotDist = dist;
        facts.outerSpotStrength = strength;
        facts.memory.noteSpot(aim, now);
    }

    private void dropEntitySpot() {
        this.entitySpotId = -1;
        this.entitySpotPos = null;
        this.entitySpotSeen = Long.MIN_VALUE;
        this.entitySpotDist = Double.MAX_VALUE;
    }

    private void dropSoundSpot() {
        this.soundSpotPos = null;
        this.soundSpotDist = Double.MAX_VALUE;
        this.soundSpotStrength = 0.0;
    }

    /** Clears outer-spot Facts fields only — never Memory. */
    private static void clearFacts(Facts facts) {
        facts.outerSpotFresh = false;
        facts.outerSpotDist = Double.MAX_VALUE;
        facts.outerSpotStrength = 0.0;
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
        return Math.floorMod(h, 1000) < (int) (chance * 1000);
    }

    /** Stronger wins, then nearer, then fresher. Package-visible for self-check. */
    static int compareSpots(double s1, double d1, long t1, double s2, double d2, long t2) {
        if (s1 != s2) return s1 > s2 ? 1 : -1;
        if (d1 != d2) return d1 < d2 ? 1 : -1;
        return Long.compare(t1, t2);
    }

    // ---- producers ----

    private static void register(ServerLevel level, BlockPos pos, TriggerKind kind,
            @Nullable CrewFacts.Faction sourceFaction, int sourceUnitId) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        AwarenessCueRegistry.of(level).register(pos, kind, sourceFaction, sourceUnitId, level.getGameTime());
    }

    /** SBW cannon fire, from {@code Level.playSound} or {@code SoundTool.playDistantSound}. */
    public static void registerCannon(ServerLevel level, BlockPos pos, @Nullable Entity sender) {
        CrewFacts.Faction faction = null;
        int unitId = -1;
        if (sender instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        } else if (sender instanceof AbstractUnit unit) {
            faction = CrewFacts.factionOfCrew(unit);
            unitId = unit.getId();
        } else if (sender instanceof LivingEntity living) {
            if (living.getVehicle() instanceof VehicleEntity vehicle) faction = CrewFacts.factionOf(vehicle);
            else unitId = living.getId();
        }
        register(level, pos, TriggerKind.VEHICLE_CANNON, faction, unitId);
    }

    /** {@code SoundTool.playDistantSound} — vehicle/emplacement fire that never hits {@code Level.playSound}. */
    public static void registerDistantSound(ServerLevel level, SoundEvent sound, Vec3 pos, @Nullable Entity sender) {
        if (!"superbwarfare".equals(sound.getLocation().getNamespace())) return;
        if (!AwarenessCueSounds.isDistantCannon(sound, sender)) return;
        registerCannon(level, BlockPos.containing(pos), sender);
    }

    /**
     * TaCZ shot — from {@link AwarenessCueEvents} / {@code GunFireEvent}. Registry dedupe is 6 s
     * over a 16-block cell so full-auto does not flood the cue list.
     */
    public static void registerTaczFire(ServerLevel level, LivingEntity shooter) {
        CrewFacts.Faction faction = null;
        int unitId = -1;
        if (shooter instanceof AbstractUnit unit) {
            faction = CrewFacts.factionOfCrew(unit);
            unitId = unit.getId();
        } else if (shooter.getVehicle() instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        }
        register(level, shooter.blockPosition(), TriggerKind.TACZ_FIRE, faction, unitId);
    }
}
