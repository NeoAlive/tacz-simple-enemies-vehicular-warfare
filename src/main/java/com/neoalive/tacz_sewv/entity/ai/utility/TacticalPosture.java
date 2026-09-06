package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IAiFireTracker;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.cover.CoverQuery;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;

/**
 * Per-crew individual tactics posture. Soft biases only — never replaces {@code TASKED_*} /
 * {@link Facts#underOrders} Action selection. Owned by {@code DriveVehicleGoal}.
 */
public final class TacticalPosture {

    public enum Tactic {
        FIRE_AND_MANEUVER,
        COVERING_ADVANCE,
        CORNER_PEEK,
        AMBUSH,
        INFANTRY_COVER,
        /** Break contact toward hard cover after smoke / low confidence (or debug force). */
        SEEK_COVER
    }

    private static final long SCOOT_DURATION = 80;
    /** Hold the cover waypoint ~15s so the hull can path there before re-pick. */
    private static final long SEEK_COVER_DURATION = 300;
    /** How long after a smoke volley still counts as "just screened". */
    private static final long SMOKE_FRESH_TICKS = 120;
    /**
     * Confidence ceiling for natural SEEK_COVER. Scale is 0–100 ({@link Confidence#NEUTRAL}=50);
     * 35 ≈ "rattled" (same band ambush requires as a floor the other way).
     */
    public static final double SEEK_COVER_CONFIDENCE_MAX = Confidence.NEUTRAL - 15.0;
    private static final long RECENT_SHOT_FULL = 40;
    private static final long RECENT_SHOT_FADE = 80;
    private static final double INFANTRY_NEAR = 24.0;
    private static final double INFANTRY_PACE_RANGE = 16.0;
    /** Soft path malus at full exposure — must stay ≤ peer PATH_PENALTY (3). */
    public static final float COVER_PATH_PENALTY = 2.5F;
    /** Fan interest weight for cover — ≤ {@link GroundMobility#PEER_SKIRT_WEIGHT}. */
    public static final float COVER_FAN_WEIGHT = 0.30F;

    /** Unit id → max engage distance while ambush-hold is armed; absent = fire normally. */
    private static final ConcurrentHashMap<Integer, Double> AMBUSH_HOLD = new ConcurrentHashMap<>();

    /** Unit id → threat xz for covering-advance path malus (set while tactic active). */
    private static final ConcurrentHashMap<Integer, long[]> COVER_THREAT = new ConcurrentHashMap<>();

    /** Unit id → fan cover interest (length 7), published each evaluate. */
    private static final ConcurrentHashMap<Integer, float[]> FAN_BIAS = new ConcurrentHashMap<>();

    /** Unit id → game-time deadline for {@code /sewv debug seekCover} (bypasses smoke/conf gates). */
    private static final ConcurrentHashMap<Integer, Long> MANUAL_SEEK_UNTIL = new ConcurrentHashMap<>();

    private final EnumSet<Tactic> active = EnumSet.noneOf(Tactic.class);
    private final float[] coverInterest = new float[GroundMobility.SLOT_COUNT];

    @Nullable
    private Vec3 scootWaypoint;
    private long scootUntil = Long.MIN_VALUE;
    @Nullable
    private Vec3 seekCoverWaypoint;
    private long seekCoverUntil = Long.MIN_VALUE;
    /** Manual / debug arm — fightTick may force {@link Action#RETREAT} to honour the waypoint. */
    private boolean seekCoverForceRetreat;
    @Nullable
    private Vec3 peekWaypoint;
    @Nullable
    private Vec3 infantryShieldPoint;
    private boolean throttleInfantryPace;
    private boolean coveringAdvance;

    public void clear() {
        this.active.clear();
        this.scootWaypoint = null;
        this.scootUntil = Long.MIN_VALUE;
        this.seekCoverWaypoint = null;
        this.seekCoverUntil = Long.MIN_VALUE;
        this.seekCoverForceRetreat = false;
        this.peekWaypoint = null;
        this.infantryShieldPoint = null;
        this.throttleInfantryPace = false;
        this.coveringAdvance = false;
        for (int i = 0; i < this.coverInterest.length; i++) this.coverInterest[i] = 0.0F;
    }

    public EnumSet<Tactic> active() {
        return this.active;
    }

    public boolean coveringAdvance() {
        return this.coveringAdvance;
    }

    public boolean throttleInfantryPace() {
        return this.throttleInfantryPace;
    }

    @Nullable
    public Vec3 scootOverrideDestination(long now) {
        if (this.scootWaypoint == null || now > this.scootUntil) return null;
        return this.scootWaypoint;
    }

    @Nullable
    public Vec3 seekCoverDestination(long now) {
        if (this.seekCoverWaypoint == null || now > this.seekCoverUntil) return null;
        return this.seekCoverWaypoint;
    }

    /** True while a manual/debug SEEK_COVER arm wants {@link Action#RETREAT} forced. */
    public boolean seekCoverForcesRetreat() {
        return this.seekCoverForceRetreat && this.seekCoverWaypoint != null;
    }

    @Nullable
    public Vec3 peekOffset() {
        return this.peekWaypoint;
    }

    /**
     * Op-only: arm SEEK_COVER on {@code unitId} until {@code untilGameTime}, bypassing smoke /
     * confidence gates. Cleared by {@link #clearUnit} or expiry.
     */
    public static void debugArmSeekCover(int unitId, long untilGameTime) {
        MANUAL_SEEK_UNTIL.put(unitId, untilGameTime);
        SewvDiag.seekCoverTemp("MANUAL arm unitId={} until={}", unitId, untilGameTime);
    }

    public static boolean debugSeekCoverArmed(int unitId, long now) {
        Long until = MANUAL_SEEK_UNTIL.get(unitId);
        if (until == null) return false;
        if (now > until) {
            MANUAL_SEEK_UNTIL.remove(unitId, until);
            return false;
        }
        return true;
    }

    @Nullable
    public Vec3 infantryShieldPoint() {
        return this.infantryShieldPoint;
    }

    public static boolean ambushHoldsFire(AbstractUnit unit, LivingEntity target) {
        Double maxDist = AMBUSH_HOLD.get(unit.getId());
        if (maxDist == null) return false;
        return unit.distanceTo(target) > maxDist;
    }

    public static void clearUnit(int unitId) {
        AMBUSH_HOLD.remove(unitId);
        COVER_THREAT.remove(unitId);
        FAN_BIAS.remove(unitId);
        MANUAL_SEEK_UNTIL.remove(unitId);
    }

    /** Server-stop / full eviction. */
    public static void clearAll() {
        AMBUSH_HOLD.clear();
        COVER_THREAT.clear();
        FAN_BIAS.clear();
        MANUAL_SEEK_UNTIL.clear();
    }

    /** Soft cover path malus toward the published threat, or 0. Never BLOCKED. */
    public static float coverPathMalus(int unitId, double nodeX, double nodeZ, ServerLevel level) {
        long[] threat = COVER_THREAT.get(unitId);
        if (threat == null) return 0.0F;
        double tx = Double.longBitsToDouble(threat[0]);
        double tz = Double.longBitsToDouble(threat[1]);
        double exp = CoverQuery.exposure(level, nodeX, nodeZ, tx, tz);
        return COVER_PATH_PENALTY * (float) exp;
    }

    /** Blend published cover interest into the terrain fan (soft). */
    public static void applyPublishedFanBias(int unitId, float[] interest) {
        float[] bias = FAN_BIAS.get(unitId);
        if (bias == null || bias.length != interest.length) return;
        for (int i = 0; i < interest.length; i++) {
            interest[i] = Math.min(1.0F, interest[i] + COVER_FAN_WEIGHT * bias[i]);
        }
    }

    /**
     * Recompute posture after a successful {@link Facts#refresh}. Writes cover / posture fields
     * onto {@code facts} for {@link TacticalBrain#sample}.
     */
    public void evaluate(AbstractUnit unit, VehicleEntity hull, Facts facts, Doctrine doctrine) {
        clearActiveOnly();
        int unitId = unit.getId();
        AMBUSH_HOLD.remove(unitId);
        COVER_THREAT.remove(unitId);

        if (!tacticsEnabled() || !(unit.level() instanceof ServerLevel level)) {
            facts.exposure = 0.0;
            facts.inCover = 0.0;
            facts.keyholeQuality = 0.0;
            facts.recentShot = 0.0;
            facts.alliedInfantryNear = 0.0;
            facts.postureScoot = false;
            facts.postureAmbush = false;
            facts.postureSeekCover = false;
            return;
        }

        CoverVisibilityCacheTouch(level, hull);

        LivingEntity target = facts.target;
        double threatX;
        double threatZ;
        boolean hasThreatBearing;
        if (target != null) {
            threatX = target.getX();
            threatZ = target.getZ();
            hasThreatBearing = true;
        } else if (facts.memory.hasFreshContact(unit.level().getGameTime())
                && facts.memory.lastEnemyPos != null) {
            BlockPos mem = facts.memory.lastEnemyPos;
            threatX = mem.getX() + 0.5;
            threatZ = mem.getZ() + 0.5;
            hasThreatBearing = true;
        } else {
            threatX = threatZ = 0;
            hasThreatBearing = false;
        }

        // Manual seekCover with no contact: invent a threat ahead of the bow so cover is rear/lateral.
        if (!hasThreatBearing && debugSeekCoverArmed(unitId, unit.level().getGameTime())) {
            var fwd = hull.getForwardDirection();
            threatX = hull.getX() + fwd.x() * 40.0;
            threatZ = hull.getZ() + fwd.z() * 40.0;
            hasThreatBearing = true;
        }

        double exposure = 1.0;
        double inCover = 0.0;
        double keyhole = 0.0;
        if (hasThreatBearing) {
            exposure = CoverQuery.exposure(level, hull.getX(), hull.getZ(), threatX, threatZ);
            inCover = 1.0 - exposure;
            keyhole = CoverQuery.keyholeQuality(level, hull.getX(), hull.getZ(), threatX, threatZ);
        }

        double recentShot = recentShotSignal(hull, unit.level().getGameTime());
        double allyInfantry = alliedInfantryNear(unit, hull);

        CrewAssignment.Snapshot assign = CrewAssignment.of(unitId);
        boolean taskedHold = assign != null && (assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.HOLD
                || assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.OVERWATCH
                || assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.RESERVE
                || assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.BASE_OF_FIRE);
        boolean taskedManeuver = assign != null
                && assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.MANEUVER;
        boolean ordered = facts.underOrders || assign != null;
        boolean ambushAllowed = !ordered || taskedHold;

        long now = unit.level().getGameTime();
        if (this.scootWaypoint != null && now > this.scootUntil) {
            this.scootWaypoint = null;
            this.scootUntil = Long.MIN_VALUE;
        }
        if (this.seekCoverWaypoint != null && now > this.seekCoverUntil) {
            this.seekCoverWaypoint = null;
            this.seekCoverUntil = Long.MIN_VALUE;
            this.seekCoverForceRetreat = false;
        }

        boolean manualSeek = debugSeekCoverArmed(unitId, now);
        boolean groundMobile = facts.idleGroundDrivable;

        // ---- Covering advance ----
        if (hasThreatBearing) {
            this.active.add(Tactic.COVERING_ADVANCE);
            this.coveringAdvance = true;
            COVER_THREAT.put(unitId, new long[] {
                    Double.doubleToRawLongBits(threatX),
                    Double.doubleToRawLongBits(threatZ)
            });
            CoverQuery.fillCoverInterest(level, hull.getX(), hull.getZ(), hull.getYRot(),
                    threatX, threatZ, this.coverInterest, GroundMobility.SLOTS_DEG);
        }

        // ---- Seek cover (retreat bias after smoke / low confidence, or debug force) ----
        boolean smokeFresh = Facts.ticksSince(facts.memory.lastSmokeTick, now) <= SMOKE_FRESH_TICKS;
        boolean lowConf = facts.confidence <= SEEK_COVER_CONFIDENCE_MAX;
        boolean wantSeek = manualSeek || (smokeFresh && lowConf && groundMobile);
        if (wantSeek && hasThreatBearing && groundMobile && this.seekCoverWaypoint == null) {
            Vec3 cover = CoverQuery.suggestRetreatCover(level, hull, threatX, threatZ);
            if (cover == null) {
                // Open / unbaked ground: still break contact rearwards (natural + manual).
                if (target != null) {
                    cover = CoverQuery.suggestDisplace(level, hull, target, true);
                }
                if (cover == null) {
                    var fwd = hull.getForwardDirection();
                    cover = new Vec3(hull.getX() - fwd.x * 20.0, hull.getY(), hull.getZ() - fwd.z * 20.0);
                }
            }
            SewvDiag.seekCoverTemp(
                    "eval unit={}#{} hull={}#{} manual={} smokeFresh={} conf={} want={} cover={}",
                    unit.getClass().getSimpleName(), unitId,
                    hull.getName().getString(), hull.getId(),
                    manualSeek, smokeFresh,
                    String.format("%.0f", facts.confidence),
                    wantSeek,
                    cover == null ? "none" : String.format("%.1f,%.1f", cover.x, cover.z));
            if (cover != null) {
                this.active.add(Tactic.SEEK_COVER);
                this.seekCoverWaypoint = cover;
                this.seekCoverUntil = now + SEEK_COVER_DURATION;
                // Force RETREAT so cover routing runs even if the scorer still prefers ATTACK.
                this.seekCoverForceRetreat = true;
                biasFanToward(hull, cover);
            }
        } else if (this.seekCoverWaypoint != null && now <= this.seekCoverUntil) {
            this.active.add(Tactic.SEEK_COVER);
            this.seekCoverForceRetreat = true;
            biasFanToward(hull, this.seekCoverWaypoint);
        }

        // ---- Fire and maneuver (1C) ----
        if (target != null && recentShot > 0.55 && this.scootWaypoint == null
                && this.seekCoverWaypoint == null) {
            boolean breakLos = facts.memory.recentlyHit(now, 100)
                    || facts.confidence < Confidence.NEUTRAL;
            Vec3 scoot = CoverQuery.suggestDisplace(level, hull, target, breakLos);
            if (scoot != null && (taskedManeuver || (facts.underOrders && !taskedHold))) {
                double toThreatBefore = hull.distanceToSqr(target);
                double toThreatAfter = scoot.distanceToSqr(target.position());
                if (toThreatAfter > toThreatBefore + 36.0) {
                    scoot = null;
                }
            }
            if (scoot != null) {
                this.active.add(Tactic.FIRE_AND_MANEUVER);
                this.scootWaypoint = scoot;
                this.scootUntil = now + SCOOT_DURATION;
            }
        }
        if (this.scootWaypoint != null && now <= this.scootUntil) {
            this.active.add(Tactic.FIRE_AND_MANEUVER);
            biasFanToward(hull, this.scootWaypoint);
        }

        // ---- Corner peek ----
        if (target != null && keyhole > 0.45 && this.scootWaypoint == null
                && this.seekCoverWaypoint == null) {
            this.active.add(Tactic.CORNER_PEEK);
            Vec3 peek = CoverQuery.suggestKeyhole(level, hull, target);
            this.peekWaypoint = peek;
            if (peek != null) biasFanToward(hull, peek);
        }

        // ---- Ambush (2A gated) ----
        boolean ambush = false;
        if (ambushAllowed && inCover > 0.55 && (target != null || facts.memory.hasFreshContact(now))
                && facts.confidence >= Confidence.NEUTRAL - 15.0) {
            double closeBand = facts.preferredRange * 0.6;
            double dist;
            if (target != null) {
                dist = unit.distanceTo(target);
            } else if (facts.memory.lastEnemyPos != null) {
                dist = Math.sqrt(hull.distanceToSqr(Vec3.atCenterOf(facts.memory.lastEnemyPos)));
            } else {
                dist = Double.MAX_VALUE;
            }
            if (dist > closeBand && dist < Double.MAX_VALUE / 2) {
                this.active.add(Tactic.AMBUSH);
                ambush = true;
                AMBUSH_HOLD.put(unitId, closeBand);
            }
        }

        // ---- Infantry cover ----
        if (allyInfantry > 0.25
                && facts.targetCategory == com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory.VEHICLE
                && target != null) {
            this.active.add(Tactic.INFANTRY_COVER);
            Vec3 shield = infantryShield(unit, hull, target);
            this.infantryShieldPoint = shield;
            this.throttleInfantryPace = shield != null
                    && hull.distanceToSqr(shield) < INFANTRY_PACE_RANGE * INFANTRY_PACE_RANGE;
            if (shield != null) biasFanToward(hull, shield);
        }

        facts.exposure = exposure;
        facts.inCover = inCover;
        facts.keyholeQuality = keyhole;
        facts.recentShot = recentShot;
        facts.alliedInfantryNear = allyInfantry;
        facts.postureScoot = this.scootWaypoint != null && now <= this.scootUntil;
        facts.postureAmbush = ambush;
        facts.postureSeekCover = this.seekCoverWaypoint != null && now <= this.seekCoverUntil;

        float[] published = FAN_BIAS.computeIfAbsent(unitId, id -> new float[GroundMobility.SLOT_COUNT]);
        System.arraycopy(this.coverInterest, 0, published, 0, this.coverInterest.length);

        if (SewvDiag.individualTacticsVerbose() && !this.active.isEmpty()) {
            SewvDiag.posture(
                    "unit={}#{} hull={}#{} tactics={} exp={} cover={} keyhole={} shot={} allies={} scoot={} ambush={} seekCover={} conf={}",
                    unit.getClass().getSimpleName(), unitId,
                    hull.getName().getString(), hull.getId(),
                    this.active,
                    String.format("%.2f", exposure),
                    String.format("%.2f", inCover),
                    String.format("%.2f", keyhole),
                    String.format("%.2f", recentShot),
                    String.format("%.2f", allyInfantry),
                    facts.postureScoot,
                    ambush,
                    facts.postureSeekCover,
                    String.format("%.0f", facts.confidence));
        }
    }

    private void clearActiveOnly() {
        this.active.clear();
        this.peekWaypoint = null;
        this.infantryShieldPoint = null;
        this.throttleInfantryPace = false;
        this.coveringAdvance = false;
        for (int i = 0; i < this.coverInterest.length; i++) this.coverInterest[i] = 0.0F;
    }

    private void biasFanToward(VehicleEntity hull, Vec3 dest) {
        Vec3 desired = new Vec3(dest.x - hull.getX(), 0.0, dest.z - hull.getZ());
        if (desired.lengthSqr() < 1.0E-6) return;
        desired = desired.normalize();
        var forward = hull.getForwardDirection().normalize();
        double ang = VehicleTargeting.signedAngleTo(forward, desired);
        double angDeg = Math.toDegrees(ang);
        for (int i = 0; i < GroundMobility.SLOT_COUNT; i++) {
            double err = Math.abs(GroundMobility.SLOTS_DEG[i] - angDeg);
            float boost = (float) Mth.clamp(1.0 - err / 75.0, 0.0, 1.0);
            this.coverInterest[i] = Math.max(this.coverInterest[i], boost);
        }
    }

    private static double recentShotSignal(VehicleEntity hull, long now) {
        if (!(hull instanceof IAiFireTracker tracker)) return 0.0;
        long last = tracker.tacz_sewv$getLastAiShotTick();
        if (last == IAiFireTracker.NEVER) return 0.0;
        long since = now - last;
        if (since < 0) return 0.0;
        if (since <= RECENT_SHOT_FULL) return 1.0;
        if (since >= RECENT_SHOT_FADE) return 0.0;
        return 1.0 - (double) (since - RECENT_SHOT_FULL) / (RECENT_SHOT_FADE - RECENT_SHOT_FULL);
    }

    private static double alliedInfantryNear(AbstractUnit unit, VehicleEntity hull) {
        List<AbstractUnit> units = HullLocalScan.unitsInScanBox(hull);
        int n = 0;
        double r2 = INFANTRY_NEAR * INFANTRY_NEAR;
        for (AbstractUnit other : units) {
            if (other == unit || !other.isAlive()) continue;
            if (other.getVehicle() != null) continue;
            if (!VehicleTargeting.isSameFaction(unit, other)) continue;
            if (hull.distanceToSqr(other) <= r2) n++;
        }
        return Math.min(n, 4) / 4.0;
    }

    @Nullable
    private static Vec3 infantryShield(AbstractUnit unit, VehicleEntity hull, LivingEntity threat) {
        List<AbstractUnit> units = HullLocalScan.unitsInScanBox(hull);
        double sx = 0, sz = 0;
        int n = 0;
        double r2 = INFANTRY_NEAR * INFANTRY_NEAR;
        for (AbstractUnit other : units) {
            if (other == unit || !other.isAlive() || other.getVehicle() != null) continue;
            if (!VehicleTargeting.isSameFaction(unit, other)) continue;
            if (hull.distanceToSqr(other) > r2) continue;
            sx += other.getX();
            sz += other.getZ();
            n++;
        }
        if (n == 0) return null;
        sx /= n;
        sz /= n;
        double dx = threat.getX() - sx;
        double dz = threat.getZ() - sz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) return new Vec3(sx, hull.getY(), sz);
        // Stand a few blocks toward the threat from the infantry centroid.
        double stand = 6.0;
        return new Vec3(sx + dx / len * stand, hull.getY(), sz + dz / len * stand);
    }

    private static void CoverVisibilityCacheTouch(ServerLevel level, VehicleEntity hull) {
        com.neoalive.tacz_sewv.entity.ai.cover.CoverVisibilityCache.requestBake(
                level, Mth.floor(hull.getX()), Mth.floor(hull.getZ()));
    }

    private static boolean tacticsEnabled() {
        try {
            return SewvConfig.INDIVIDUAL_TACTICS_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
