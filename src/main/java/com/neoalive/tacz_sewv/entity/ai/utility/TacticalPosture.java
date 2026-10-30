package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.ArrayList;
import java.util.Comparator;
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
import com.neoalive.tacz_sewv.entity.ai.cover.BattlePosition;
import com.neoalive.tacz_sewv.entity.ai.cover.CoverQuery;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;
import com.neoalive.tacz_sewv.entity.ai.sensor.ContactBoard;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressiveFire;
import com.neoalive.tacz_sewv.entity.ai.suppression.InfantryArmorDoctrine;

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
        /** Break contact toward hard cover after smoke / low confidence. */
        SEEK_COVER,
        /** Holding (or driving to) a latched battle position. */
        BATTLE_POSITION
    }

    private static final long SCOOT_DURATION = 120;
    /** Hold the cover waypoint ~15s so the hull can path there before re-pick. */
    private static final long SEEK_COVER_DURATION = 300;
    private static final long PEEK_DURATION = 100;
    private static final long SHIELD_DURATION = 120;
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

    private final EnumSet<Tactic> active = EnumSet.noneOf(Tactic.class);
    private final float[] coverInterest = new float[GroundMobility.SLOT_COUNT];

    @Nullable
    private Vec3 scootWaypoint;
    private long scootUntil = Long.MIN_VALUE;
    @Nullable
    private Vec3 seekCoverWaypoint;
    private long seekCoverUntil = Long.MIN_VALUE;
    /** When set, fightTick forces {@link Action#RETREAT} so cover routing is not optional. */
    private boolean seekCoverForceRetreat;
    @Nullable
    private Vec3 peekWaypoint;
    private long peekUntil = Long.MIN_VALUE;
    @Nullable
    private Vec3 infantryShieldPoint;
    private long shieldUntil = Long.MIN_VALUE;
    private boolean throttleInfantryPace;
    private boolean coveringAdvance;
    private final BattlePosition battle = new BattlePosition();

    public void clear() {
        this.active.clear();
        this.battle.clear();
        this.scootWaypoint = null;
        this.scootUntil = Long.MIN_VALUE;
        this.seekCoverWaypoint = null;
        this.seekCoverUntil = Long.MIN_VALUE;
        this.seekCoverForceRetreat = false;
        this.peekWaypoint = null;
        this.peekUntil = Long.MIN_VALUE;
        this.infantryShieldPoint = null;
        this.shieldUntil = Long.MIN_VALUE;
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

    /** The latched battle position to fight from, or null. */
    @Nullable
    public Vec3 battlePosition() {
        return this.battle.point();
    }

    /**
     * The stalemate breaker fired. True when the crew answers it by moving to its alternate battle position (or is
     * already doing so this episode) instead of orbiting; false when there is no position to stand on.
     */
    public boolean answerBreaker(long now) {
        return this.battle.onBreaker(now);
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

    /** True while SEEK_COVER wants {@link Action#RETREAT} forced. */
    public boolean seekCoverForcesRetreat() {
        return this.seekCoverForceRetreat && this.seekCoverWaypoint != null;
    }

    @Nullable
    public Vec3 peekOffset(long now) {
        if (this.peekWaypoint == null || now > this.peekUntil) return null;
        return this.peekWaypoint;
    }

    @Nullable
    public Vec3 infantryShieldPoint(long now) {
        if (this.infantryShieldPoint == null || now > this.shieldUntil) return null;
        return this.infantryShieldPoint;
    }

    public static void clearUnit(int unitId) {
        AMBUSH_HOLD.remove(unitId);
        COVER_THREAT.remove(unitId);
        FAN_BIAS.remove(unitId);
    }

    /** Server-stop / full eviction. */
    public static void clearAll() {
        AMBUSH_HOLD.clear();
        COVER_THREAT.clear();
        FAN_BIAS.clear();
    }

    public static boolean ambushHoldsFire(AbstractUnit unit, LivingEntity target) {
        Double maxDist = AMBUSH_HOLD.get(unit.getId());
        if (maxDist == null) return false;
        return unit.distanceTo(target) > maxDist;
    }

    /**
     * Soft cover path malus, or 0. Never BLOCKED. The published threats are the crew's own bearing plus up to
     * {@link #MAX_EXTRA_THREATS} anti-armour contacts off the board, and a node costs its WORST exposure among
     * them: a route that hides from the tank ahead but crosses the open in front of a TOW on the flank is not a
     * covered route. Danger stays a cost, so a crew with no safe way still arrives.
     */
    public static float coverPathMalus(int unitId, double nodeX, double nodeZ, ServerLevel level) {
        long[] threat = COVER_THREAT.get(unitId);
        if (threat == null) return 0.0F;
        // The primary threat gets the full read (wrecks included); the extras one table read each, so
        // their cost per node stays a lookup rather than a walk over every wreck in the level.
        double worst = CoverQuery.exposure(level, nodeX, nodeZ,
                Double.longBitsToDouble(threat[0]), Double.longBitsToDouble(threat[1]));
        for (int i = 2; i + 1 < threat.length && worst < 1.0; i += 2) {
            double tx = Double.longBitsToDouble(threat[i]);
            double tz = Double.longBitsToDouble(threat[i + 1]);
            worst = Math.max(worst, CoverQuery.terrainExposure(level, nodeX, nodeZ, tx, tz));
        }
        return COVER_PATH_PENALTY * (float) worst;
    }

    /** Anti-armour contacts routed around in addition to the crew's own threat bearing. */
    static final int MAX_EXTRA_THREATS = 3;
    /** Contacts farther than this from the hull cannot shape its next few hundred path nodes. */
    private static final double EXTRA_THREAT_RANGE = 160.0;

    /**
     * The crew's threat bearing, then the nearest other contacts that can kill armour (a crewed hull, a TOW, a
     * launcher in someone's hands), packed as x,z pairs for {@link #coverPathMalus}.
     */
    private static long[] packThreats(AbstractUnit unit, VehicleEntity hull, @Nullable LivingEntity primary,
                                      double threatX, double threatZ) {
        List<LivingEntity> extra = new ArrayList<>(MAX_EXTRA_THREATS);
        double rangeSq = EXTRA_THREAT_RANGE * EXTRA_THREAT_RANGE;
        for (LivingEntity c : ContactBoard.contactsFor(unit, ContactBoard.Source.RELAYED)) {
            if (c == primary || c.distanceToSqr(hull) > rangeSq) continue;
            if (InfantryArmorDoctrine.tierOf(c) < 0.5f && !SmallArmsSupport.holdsAntiArmor(c)) continue;
            extra.add(c);
        }
        extra.sort(Comparator.comparingDouble(c -> c.distanceToSqr(hull)));
        int n = Math.min(extra.size(), MAX_EXTRA_THREATS);
        long[] out = new long[2 + 2 * n];
        out[0] = Double.doubleToRawLongBits(threatX);
        out[1] = Double.doubleToRawLongBits(threatZ);
        for (int i = 0; i < n; i++) {
            out[2 + 2 * i] = Double.doubleToRawLongBits(extra.get(i).getX());
            out[3 + 2 * i] = Double.doubleToRawLongBits(extra.get(i).getZ());
        }
        return out;
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
            facts.inBattlePosition = false;
            this.battle.clear();
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
        if (this.peekWaypoint != null && now > this.peekUntil) {
            this.peekWaypoint = null;
            this.peekUntil = Long.MIN_VALUE;
        }
        if (this.infantryShieldPoint != null && now > this.shieldUntil) {
            this.infantryShieldPoint = null;
            this.shieldUntil = Long.MIN_VALUE;
            this.throttleInfantryPace = false;
        }

        boolean groundMobile = facts.idleGroundDrivable;

        // ---- Covering advance ----
        if (hasThreatBearing) {
            this.active.add(Tactic.COVERING_ADVANCE);
            this.coveringAdvance = true;
            COVER_THREAT.put(unitId, packThreats(unit, hull, target, threatX, threatZ));
            CoverQuery.fillCoverInterest(level, hull.getX(), hull.getZ(), hull.getYRot(),
                    threatX, threatZ, this.coverInterest, GroundMobility.SLOTS_DEG);
        }

        // ---- Battle position ----
        boolean taskedMove = assign != null
                && (taskedManeuver || assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.WITHDRAW);
        updateBattlePosition(level, unit, hull, facts, target, threatX, threatZ, hasThreatBearing,
                groundMobile && !taskedMove && !VehicleTargeting.holdsOrderedMove(unit)
                        && this.seekCoverWaypoint == null,
                now);
        boolean battleLatched = this.battle.latched();
        if (battleLatched) this.active.add(Tactic.BATTLE_POSITION);

        // ---- Seek cover (retreat bias after smoke / low confidence) ----
        boolean smokeFresh = Facts.ticksSince(facts.memory.lastSmokeTick, now) <= SMOKE_FRESH_TICKS;
        boolean lowConf = facts.confidence <= SEEK_COVER_CONFIDENCE_MAX;
        boolean wantSeek = smokeFresh && lowConf && groundMobile;
        if (wantSeek && hasThreatBearing && groundMobile && this.seekCoverWaypoint == null) {
            Vec3 cover = CoverQuery.suggestRetreatCover(level, hull, threatX, threatZ);
            if (cover == null) {
                // Open / unbaked ground: still break contact rearwards.
                if (target != null) {
                    cover = CoverQuery.suggestDisplace(level, hull, target, true);
                }
                if (cover == null) {
                    var fwd = hull.getForwardDirection();
                    cover = new Vec3(hull.getX() - fwd.x * 20.0, hull.getY(), hull.getZ() - fwd.z * 20.0);
                }
            }
            if (cover != null) {
                this.battle.drop(now);
                battleLatched = false;
                this.active.remove(Tactic.BATTLE_POSITION);
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
        // A battle position replaces the scoot: it moves to its alternate under pressure, not after every shot.
        if (!battleLatched && target != null && recentShot > 0.55 && this.scootWaypoint == null
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
        if (!battleLatched && target != null && keyhole > 0.45 && this.scootWaypoint == null
                && this.seekCoverWaypoint == null && this.peekWaypoint == null) {
            Vec3 peek = CoverQuery.suggestKeyhole(level, hull, target);
            if (peek != null) {
                this.active.add(Tactic.CORNER_PEEK);
                this.peekWaypoint = peek;
                this.peekUntil = now + PEEK_DURATION;
                biasFanToward(hull, peek);
            }
        } else if (this.peekWaypoint != null && now <= this.peekUntil) {
            this.active.add(Tactic.CORNER_PEEK);
            biasFanToward(hull, this.peekWaypoint);
        }

        // ---- Ambush (2A gated) ----
        boolean ambush = false;
        // Never on a battle position: it was picked to shoot from, and ambush holds the gun until the enemy closes to
        // 0.6 of the ring the position sits on. Two sides on facing positions then each waited for the other forever.
        if (!battleLatched && ambushAllowed && inCover > 0.55 && (target != null || facts.memory.hasFreshContact(now))
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
        // A BOLD crew does not babysit its infantry; it fights its own fight.
        if (!battleLatched && Doctrine.Temperament.of(facts.caution) != Doctrine.Temperament.BOLD
                && allyInfantry > 0.25
                && facts.targetCategory == com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory.VEHICLE
                && target != null
                && this.infantryShieldPoint == null) {
            Vec3 shield = infantryShield(unit, hull, target);
            if (shield != null) {
                this.active.add(Tactic.INFANTRY_COVER);
                this.infantryShieldPoint = shield;
                this.shieldUntil = now + SHIELD_DURATION;
                this.throttleInfantryPace =
                        hull.distanceToSqr(shield) < INFANTRY_PACE_RANGE * INFANTRY_PACE_RANGE;
                biasFanToward(hull, shield);
            }
        } else if (this.infantryShieldPoint != null && now <= this.shieldUntil) {
            this.active.add(Tactic.INFANTRY_COVER);
            this.throttleInfantryPace =
                    hull.distanceToSqr(this.infantryShieldPoint) < INFANTRY_PACE_RANGE * INFANTRY_PACE_RANGE;
            biasFanToward(hull, this.infantryShieldPoint);
        }

        facts.exposure = exposure;
        facts.inCover = inCover;
        facts.keyholeQuality = keyhole;
        facts.recentShot = recentShot;
        facts.alliedInfantryNear = allyInfantry;
        facts.postureScoot = this.scootWaypoint != null && now <= this.scootUntil;
        facts.postureAmbush = ambush;
        facts.postureSeekCover = this.seekCoverWaypoint != null && now <= this.seekCoverUntil;
        long lastShot = hull instanceof IAiFireTracker tracker ? tracker.tacz_sewv$getLastAiShotTick() : Long.MIN_VALUE;
        facts.inBattlePosition = battleLatched && target != null
                && BattlePosition.working(now, this.battle.arrivedAt(), lastShot);

        float[] published = FAN_BIAS.computeIfAbsent(unitId, id -> new float[GroundMobility.SLOT_COUNT]);
        System.arraycopy(this.coverInterest, 0, published, 0, this.coverInterest.length);

        if (SewvDiag.individualTacticsVerbose() && !this.active.isEmpty()) {
            SewvDiag.posture(
                    "unit={}#{} hull={}#{} tactics={} exp={} cover={} keyhole={} shot={} allies={} scoot={} ambush={} seekCover={} conf={} bp={} alt={} hullDown={} pressure={} temper={}",
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
                    String.format("%.0f", facts.confidence),
                    !battleLatched ? "NONE" : this.battle.onStation() ? "ON_STATION" : "EN_ROUTE",
                    this.battle.hasAlternate(),
                    this.battle.hullDown(),
                    String.format("%.2f", facts.pressure),
                    Doctrine.Temperament.of(facts.caution));
        }
    }

    /**
     * Pick, keep, swap or drop the battle position. An engagement is fresh-contact memory, not a target lock, so a
     * flickering lock never re-picks; picks are further bounded per hull and per level inside {@link BattlePosition}.
     * The position survives losing the target (a crew suppresses from it), and is only ever picked with one.
     */
    private void updateBattlePosition(ServerLevel level, AbstractUnit unit, VehicleEntity hull, Facts facts,
                                      @Nullable LivingEntity target, double threatX, double threatZ,
                                      boolean hasThreatBearing, boolean allowed, long now) {
        if (!BattlePosition.enabled()) {
            this.battle.clear();
            return;
        }
        // Past the suppress window a lost contact releases the position: the crew should go and look, not hold a
        // spot facing nothing for the rest of the 30 s contact memory.
        boolean engaged = target != null
                || (hasThreatBearing && Facts.ticksSince(facts.memory.lastContactTick, now) <= SuppressiveFire.WINDOW_TICKS);
        if (this.battle.latched()) {
            this.battle.markArrival(hull, now);
            if (hull instanceof IAiFireTracker tracker) this.battle.renew(tracker.tacz_sewv$getLastAiShotTick());
            Vec3 bp = this.battle.point();
            double drift = hasThreatBearing
                    ? BattlePosition.bearingDelta(Math.atan2(threatZ - bp.z, threatX - bp.x), this.battle.pickedBearing())
                    : 0.0;
            double targetDist = target != null ? hull.distanceTo(target) : Double.MAX_VALUE;
            double minDist = target != null
                    ? facts.preferredRange - Facts.rangeDeadband(facts.targetCategory) : 0.0;
            long onStation = this.battle.onStation() ? now - this.battle.arrivedAt() : -1;
            BattlePosition.Verdict verdict = BattlePosition.decide(engaged, !allowed, drift, targetDist, minDist,
                    now, this.battle.leaseUntil(), onStation, facts.pressure, this.battle.hasAlternate());
            if (verdict == BattlePosition.Verdict.SWAP) this.battle.swap(now);
            else if (verdict == BattlePosition.Verdict.DROP) this.battle.drop(now);
            return;
        }
        if (!allowed || !engaged || target == null || !this.battle.mayPick(now)) return;
        if (!BattlePosition.wantsPosition(facts.caution, facts.pressure, facts.forceRatio, facts.health,
                facts.confidence)) return;
        // Already inside the band: the release below would drop any pick on the next refresh, so a pick here only
        // churned (pick, drop, cooldown, pick) and spent its raycasts for nothing.
        if (hull.distanceTo(target) < facts.preferredRange - Facts.rangeDeadband(facts.targetCategory)) return;
        long[] threats = COVER_THREAT.get(unit.getId());
        if (threats == null || !this.battle.acquirePickSlot(level, now)) return;
        CoverQuery.BattlePick pick = CoverQuery.suggestBattlePositions(level, hull, target, threats,
                facts.preferredRange, Facts.rangeDeadband(facts.targetCategory));
        double bearing = pick == null ? 0.0
                : Math.atan2(threatZ - pick.primary().z, threatX - pick.primary().x);
        this.battle.latch(pick, bearing, now, BattlePosition.leaseScale(facts.caution));
        if (pick != null) {
            // Whatever the old tactics had armed would pull the hull off the position it just chose.
            this.scootWaypoint = null;
            this.scootUntil = Long.MIN_VALUE;
            this.peekWaypoint = null;
            this.peekUntil = Long.MIN_VALUE;
            this.infantryShieldPoint = null;
            this.shieldUntil = Long.MIN_VALUE;
        }
    }

    private void clearActiveOnly() {
        this.active.clear();
        // peek / shield / scoot / seekCover waypoints are timed — do not wipe mid-maneuver.
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
