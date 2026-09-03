package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.UUID;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.IdleSupport;
import com.neoalive.tacz_sewv.entity.ai.support.MarchObjective;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;
import com.neoalive.tacz_sewv.fob.FobResupplySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.invasion.CaptureOrderSupport;
import com.neoalive.tacz_sewv.invasion.InvasionHostility;
import com.neoalive.tacz_sewv.invasion.InvasionTags;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldTargetPriority;

/**
 * Shared "where should this crew go?" resolution for any mounted-vehicle drive
 * goal — ground ({@link DriveVehicleGoal}) or flight ({@link DriveHelicopterGoal}).
 *
 * <p>PmcUnitEntity (player-commandable) drives its destination from the SEM order
 * queue; RUunitEntity/USunitEntity (plain hostiles, no order system) fall back to
 * their current combat target, then to reinforcing a nearby allied crew in combat.
 * The stateless order/formation resolution lives in static methods here; the
 * ally-assist world scan carries per-goal mutable state, so it lives in the
 * {@link AllyAssist} holder each goal owns one of.
 */
public final class VehicleTargeting {

    private static final double FORMATION_ARRIVE_RADIUS = 3.0;

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private VehicleTargeting() {}

    // Ring around an allied crew an idle unit settles into to join its fight.
    private static final double ASSIST_RING_RADIUS = 16.0;
    private static final double ASSIST_RING_DEADBAND = 4.0;
    /**
     * How long an idle crew stays committed to reinforcing one ally before it may peel onto a
     * different one — see {@link AllyAssist#assistTargetPos(AbstractUnit, VehicleEntity,
     * Predicate, int, double)}'s {@code newEngagementCooldown} parameter. Matches
     * {@code PatrolSupport.SEARCH_ASSIST_COOLDOWN}'s already-vetted value for the same doctrine.
     * Traced live: without this, {@code findAllyInCombat}'s "nearest ally currently in combat"
     * re-scan (on its own {@code VEHICLE_TARGET_SCAN_INTERVAL_TICKS} cadence) has nothing to stop
     * it flipping to a DIFFERENT ally every scan whenever two allies' in-combat status alternates
     * (one breaks contact just as the other regains it) — confirmed via a live log trace showing
     * an idle RU crew's destination jumping between two allies' positions ~100 blocks apart,
     * several times a second, with {@code VehicleTargetScanGoal} itself reporting no target of
     * its own the whole time (ruling out the crew's own fight as the cause).
     */
    private static final int ASSIST_ENGAGEMENT_COOLDOWN = 400; // 20s at 20 ticks/s
    // How close a hull parks to an ordinary destination, on top of its own width. Deliberately
    // loose: closing to within a hull length of a combat approach is the last thing armor should
    // do. A formation slot and a player MOVE click need the opposite — see arrivalDistance.
    private static final double STOP_DISTANCE = 8.0;
    /** Player MOVE click: park near the point, not a hull-length short of it. */
    private static final double MOVE_STOP_DISTANCE = 2.0;

    // Resolve where a mounted crew should head. Returns null when there is nowhere
    // to go (holding position, no target, nothing to reinforce). `assist` carries
    // the stateful ally scan and may be null to opt out of mutual support.
    public static BlockPos resolveDestination(AbstractUnit unit, VehicleEntity vehicle, AllyAssist assist) {
        // FOB route beats every other destination — capture, scramble, patrol, SEM orders included.
        if (unit instanceof PmcUnitEntity routePmc && FobSupport.hasRoutePending(routePmc)) {
            Vec3 moveTarget = routePmc.getMoveToTarget();
            if (moveTarget != null && !moveTarget.equals(Vec3.ZERO)) {
                return BlockPos.containing(moveTarget);
            }
        }

        // Invasion capture pipeline — event-spawned AI fleets and PMC. Before chase / idle / SEM
        // orders so a capture commitment cannot be abandoned for FREE_FIRE wander.
        BlockPos capture = CaptureOrderSupport.currentDestination(unit, vehicle);
        if (capture != null) return capture;

        BlockPos entrench = EntrenchSupport.currentCell(unit);
        if (entrench != null) return entrench;

        BlockPos fobResupply = FobResupplySupport.resupplyDestination(unit, vehicle);
        if (fobResupply != null) return fobResupply;

        if (FobResupplySupport.holdingForResupply(unit, vehicle)) return null;

        BlockPos fobPark = FobSupport.parkDestination(unit, vehicle);
        if (fobPark != null) return fobPark;

        if (!(unit instanceof PmcUnitEntity pmc)) {
            LivingEntity target = unit.getTarget();
            if (target != null) return target.blockPosition();
            // A standing march objective (today: a MineColonies armored raid) outranks assisting
            // and idling but not a fight of our own — the crew resumes the march afterwards.
            BlockPos march = MarchObjective.of(vehicle);
            if (march != null) return march;
            // No fight of our own — reinforce a nearby allied crew that has one. Cooldown-gated
            // (see ASSIST_ENGAGEMENT_COOLDOWN) so this doesn't peel onto a different ally every
            // scan interval.
            BlockPos aid = assist != null
                    ? assist.assistTargetPos(unit, vehicle, null, ASSIST_ENGAGEMENT_COOLDOWN, 0.0)
                    : null;
            // Nothing to reinforce either: potter about rather than park like a statue.
            // Ground hybrid idle supplies destinations via IDLE_HOLD / IDLE_TRAVEL in DriveVehicleGoal;
            // ships (and ground when hybrid is off) still use IdleSupport.wanderPos.
            if (aid != null) return aid;
            return IdleSupport.wanderPos(unit, vehicle);
        }

        // An area task (patrol / search & destroy / sweep) is a standing TDT order that outranks
        // the SEM order queue: while it is set the hull works its area. Contact no longer yields
        // the wheel (see DriveVehicleGoal + PatrolSupport.holdsCourseThroughContact) — the crew
        // fights from the ordered ground. Dismount, a formation order, or a finished sector clear it.
        BlockPos areaTask = PatrolSupport.currentWaypoint(pmc, vehicle);
        if (areaTask != null) {
            // Backing up an ally already in contact beats carrying on with our own leg of the area,
            // so it takes precedence over the waypoint. The conditions differ per task — see
            // PatrolSupport.assistPos.
            BlockPos aid = PatrolSupport.assistPos(pmc, vehicle, assist);
            return aid != null ? aid : areaTask;
        }

        OrderType order = pmc.getOrder();

        switch (order) {
            case HOLD_POSITION:
            case CEASE_FIRE:
                // CEASE_FIRE holds ground too; firing is suppressed separately in
                // MixinVehicleFireCooldown so the crew simply sits and doesn't shoot.
                return null;

            case MOVE_TO_POSITION:
                Vec3 moveTarget = pmc.getMoveToTarget();
                return (moveTarget != null && !moveTarget.equals(Vec3.ZERO))
                        ? BlockPos.containing(moveTarget) : null;

            case ATTACK_THAT_TARGET:
                // Player designated the target — no freelancing off to help allies.
                return pmc.getTarget() != null ? pmc.getTarget().blockPosition() : null;

            case FREE_FIRE:
                if (pmc.getTarget() != null) {
                    return pmc.getTarget().blockPosition();
                }
                // Free-firing with nothing to shoot — reinforce an allied crew in combat, and failing
                // that idle about. FREE_FIRE is the only order that idles: every other one is a
                // positional instruction the player expects to be obeyed exactly. Cooldown-gated —
                // see ASSIST_ENGAGEMENT_COOLDOWN.
                BlockPos support = assist != null
                        ? assist.assistTargetPos(unit, vehicle, null, ASSIST_ENGAGEMENT_COOLDOWN, 0.0)
                        : null;
                return support != null ? support : IdleSupport.wanderPos(unit, vehicle);

            case FOLLOW_COMMANDER:
                Player follows = commander(pmc);
                return follows != null ? follows.blockPosition() : null;

            case FORM_WEDGE:
            case FORM_COLUMN: {
                Player leader = commander(pmc);
                IFormationMember member = (IFormationMember) pmc;
                Direction axis = member.sewv$getFormationDirection();
                int slot = pmc.getFormationIndex();
                // No axis means this order did not come through our gate — it is a plain SEM
                // infantry formation that happens to have caught a mounted crew. There is no
                // hull geometry to drive to, so hold.
                if (leader == null || axis == null || slot < 0) return null;
                FormationShape shape = FormationShape.byId(member.sewv$getFormationShape());
                int rowSize = member.sewv$getFormationRowSize();
                // Air-to-air: keep the leader's altitude so a heli wedge does not collapse
                // onto the terrain under the slot. Ground hulls still snap to the surface.
                VehicleEntity leaderHeli = commanderHelicopter(pmc);
                if (leaderHeli != null && HullFacts.isHelicopterHull(vehicle)) {
                    Vec3 anchor = new Vec3(leader.getX(), leaderHeli.getY(), leader.getZ());
                    return VehicleFormation.slotPosAtAltitude(anchor, axis, shape, slot, rowSize);
                }
                return VehicleFormation.slotPos(
                        unit.level(), leader.position(), axis, shape, slot, rowSize);
            }

            default:
                return null;
        }
    }

    /**
     * How close the hull must be to its resolved destination to count as arrived.
     *
     * <p>A formation slot needs a tight arrival that nothing else does. Slots sit
     * vehicleFormationSpacing apart, while the generic answer is a hull width plus
     * {@link #STOP_DISTANCE} — for a 4.62-wide T-90A that is 11.62 blocks, wider than the whole
     * formation, so every hull would read "arrived" from anywhere in it and the wedge would
     * collapse onto the point man. A player MOVE click uses a tighter stop so the hull actually
     * reaches the ordered point.
     */
    public static double arrivalDistance(AbstractUnit unit, VehicleEntity vehicle) {
        if (unit instanceof PmcUnitEntity pmc) {
            OrderType order = pmc.getOrder();
            if (order == OrderType.FORM_WEDGE || order == OrderType.FORM_COLUMN) {
                return FORMATION_ARRIVE_RADIUS;
            }
            if (order == OrderType.MOVE_TO_POSITION) {
                return Math.max(vehicle.getBbWidth() * 0.5, MOVE_STOP_DISTANCE);
            }
        }
        return vehicle.getBbWidth() - 1.0 + STOP_DISTANCE;
    }

    /**
     * Mounted MOVE must keep driving the click through contact — same commitment as an area
     * task / capture approach. Fire assist still runs; only locomotion stays on the order.
     */
    public static boolean holdsOrderedMove(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return false;
        if (FobSupport.holdsRouteThroughContact(pmc)) {
            Vec3 dest = pmc.getMoveToTarget();
            return dest != null && !dest.equals(Vec3.ZERO);
        }
        if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) return false;
        Vec3 dest = pmc.getMoveToTarget();
        return dest != null && !dest.equals(Vec3.ZERO);
    }

    /**
     * The heading a parked crew holds, or null when this order has no heading to hold (which is
     * every order but a formation — there is nothing else to face).
     */
    public static Vec3 formationForward(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return null;
        OrderType order = pmc.getOrder();
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return null;
        Direction axis = ((IFormationMember) pmc).sewv$getFormationDirection();
        return axis == null ? null : VehicleFormation.forward(axis);
    }

    private static Player commander(PmcUnitEntity pmc) {
        UUID ownerId = pmc.getOwnerUUID();
        return ownerId != null ? pmc.level().getPlayerByUUID(ownerId) : null;
    }

    /**
     * The owner's helicopter, or null when they are on foot / in anything else.
     * Follow and formation read this so air crews can match altitude.
     */
    @Nullable
    public static VehicleEntity commanderHelicopter(PmcUnitEntity pmc) {
        Player leader = commander(pmc);
        if (leader == null) return null;
        if (leader.getVehicle() instanceof VehicleEntity v && HullFacts.isHelicopterHull(v)) {
            return v;
        }
        return null;
    }

    // Point at `radius` straight out from the target through the vehicle — the ring
    // the hull should hold (standoff for armor, orbit for a gunship, break-contact
    // beyond it when retreating).
    public static BlockPos computeStandoffPoint(VehicleEntity vehicle, BlockPos targetPos, double radius) {
        return computeStandoffPoint(vehicle, targetPos, radius, 0.0);
    }

    // Same ring, but swung `bearingOffsetRad` around the target from where the hull
    // currently stands. Offset 0 is "hold this bearing, fix the range"; a non-zero
    // offset walks the hull around the ring, which is how a crew that can't get a
    // firing solution from here goes looking for ground it can shoot from.
    public static BlockPos computeStandoffPoint(VehicleEntity vehicle, BlockPos targetPos,
                                                double radius, double bearingOffsetRad) {
        double cx = targetPos.getX() + 0.5;
        double cz = targetPos.getZ() + 0.5;
        double dx = vehicle.getX() - cx;
        double dz = vehicle.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            // Practically on top of the target — flee along the current facing
            Vector3f forward = vehicle.getForwardDirection().normalize();
            dx = forward.x;
            dz = forward.z;
            len = 1.0;
        }
        if (bearingOffsetRad != 0.0) {
            Vec3 swung = rotateY(new Vec3(dx, 0.0, dz), bearingOffsetRad);
            dx = swung.x;
            dz = swung.z;
            // rotateY preserves length, so `len` still holds.
        }
        double scale = radius / len;
        return BlockPos.containing(cx + dx * scale, vehicle.getY(), cz + dz * scale);
    }

    // Rotate a horizontal (y=0) direction about the vertical axis. Shared: the whisker fans
    // swing candidate headings with it, and the ring math above swings bearings with it.
    public static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`. Shared so the
    // steering signs cannot drift apart between the ground and flight goals — the flight
    // goal's yaw stick is fed the negation of this, which is only correct while both goals
    // agree on the convention.
    public static double signedAngleTo(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    /**
     * Mutual support: an idle crew that notices an allied vehicle in combat drives
     * to it and settles inside a ring around the ally — close enough to bring its
     * own guns into the same fight, far enough to not park on the ally's tracks.
     * Holds the cached ally between world scans (the expensive part), rescanning
     * only on the target-scan cadence.
     */
    public static final class AllyAssist {
        private VehicleEntity assistAlly;
        private long lastAssistScanTime = Long.MIN_VALUE;
        private long lastEngagementTime = Long.MIN_VALUE;

        /**
         * Same, but restricted to allies passing {@code allyFilter} (null = any), optionally
         * rate-limited, and optionally scanning out to {@code rangeOverride} instead of the
         * configured assist range ({@code <= 0} keeps the configured one).
         *
         * <p>The rate limit: peeling off onto a DIFFERENT ally counts as a fresh engagement and is
         * refused until {@code newEngagementCooldown} ticks have passed since the last one. Staying
         * with the ally we are already supporting is always free, so the throttle bounds how often a
         * hull can be pulled off its own task — not how long it may help once it has committed.
         */
        public BlockPos assistTargetPos(AbstractUnit unit, VehicleEntity vehicle,
                                 @Nullable Predicate<VehicleEntity> allyFilter,
                                 int newEngagementCooldown, double rangeOverride) {
            VehicleEntity previous = this.assistAlly;
            VehicleEntity ally = findAllyInCombat(unit, vehicle, allyFilter, rangeOverride);
            if (ally == null) return null;

            if (newEngagementCooldown > 0 && ally != previous) {
                long now = unit.level().getGameTime();
                // Long.MIN_VALUE must be tested explicitly — now - MIN_VALUE overflows negative and
                // would read as "too soon" forever, same trap as the scan throttle below.
                if (this.lastEngagementTime != Long.MIN_VALUE
                        && now - this.lastEngagementTime < newEngagementCooldown) {
                    this.assistAlly = previous; // declined — don't let the scan latch the new ally
                    return null;
                }
                this.lastEngagementTime = now;
            }

            double dx = ally.getX() - vehicle.getX();
            double dz = ally.getZ() - vehicle.getZ();
            double arrive = ASSIST_RING_RADIUS + ASSIST_RING_DEADBAND;
            if (dx * dx + dz * dz <= arrive * arrive) return null; // inside the ring — done

            return computeStandoffPoint(vehicle, ally.blockPosition(), ASSIST_RING_RADIUS);
        }

        // Nearest allied crewed vehicle in combat within the configured assist range.
        private VehicleEntity findAllyInCombat(AbstractUnit unit, VehicleEntity vehicle,
                                               @Nullable Predicate<VehicleEntity> allyFilter,
                                               double rangeOverride) {
            // The config value stays the master switch even when a caller supplies its own reach —
            // setting it to 0 means "no mutual support", not "no mutual support except on orders".
            if (SewvConfig.VEHICLE_ALLY_ASSIST_RANGE.get() <= 0.0) return null;
            double range = rangeOverride > 0.0 ? rangeOverride : SewvConfig.VEHICLE_ALLY_ASSIST_RANGE.get();

            // The sentinel must be tested explicitly: now - Long.MIN_VALUE overflows
            // negative, which would make the throttle permanently "too soon" and the
            // scan would never run at all.
            long now = unit.level().getGameTime();
            if (this.lastAssistScanTime != Long.MIN_VALUE
                    && now - this.lastAssistScanTime < SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get()) {
                return validateAssistAlly(unit, vehicle, range, allyFilter);
            }
            this.lastAssistScanTime = now;

            // Same flat-cylinder shape as the target scan: full horizontal reach,
            // capped vertical extent, corners rounded off by the distance filter.
            double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
            AABB bounds = new AABB(
                    vehicle.getX() - range, vehicle.getY() - halfHeight, vehicle.getZ() - range,
                    vehicle.getX() + range, vehicle.getY() + halfHeight, vehicle.getZ() + range);

            VehicleEntity best = null;
            double bestDistSq = range * range;
            for (VehicleEntity ally : unit.level().getEntitiesOfClass(VehicleEntity.class, bounds,
                    v -> v != vehicle && !v.isWreck())) {
                if (!isAlliedCrewInCombat(unit, ally)) continue;
                if (allyFilter != null && !allyFilter.test(ally)) continue;
                double dx = ally.getX() - vehicle.getX();
                double dz = ally.getZ() - vehicle.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq <= bestDistSq) {
                    best = ally;
                    bestDistSq = distSq;
                }
            }
            this.assistAlly = best;
            return best;
        }

        // Between scans: keep the cached ally only while it still needs the help.
        private VehicleEntity validateAssistAlly(AbstractUnit unit, VehicleEntity vehicle, double range,
                                                 @Nullable Predicate<VehicleEntity> allyFilter) {
            VehicleEntity ally = this.assistAlly;
            if (ally == null) return null;
            if (ally.isRemoved() || ally.isWreck() || !isAlliedCrewInCombat(unit, ally)
                    || (allyFilter != null && !allyFilter.test(ally)) // e.g. repaired past the patrol threshold
                    || vehicle.distanceToSqr(ally) > range * range * 2.25) { // >1.5x range — chase abandoned
                this.assistAlly = null;
                return null;
            }
            return ally;
        }

        public void clear() {
            this.assistAlly = null;
        }
    }

    private static boolean isAlliedCrewInCombat(AbstractUnit unit, VehicleEntity ally) {
        if (!(ally.getFirstPassenger() instanceof AbstractUnit driver)) return false;
        if (!isSameFaction(unit, driver)) return false;
        LivingEntity target = driver.getTarget();
        return target != null && target.isAlive();
    }

    // The hard friendly-fire gate: true when `target` is a combat ally. MixinAbstractUnit
    // cancels any setTarget that fails this test, so a stray splash-damage hit can never
    // escalate into an intra-faction firefight.
    //
    // Deliberately NARROW — same SEM faction only, no config, except Stage-4 / invasion
    // ENEMY PMC pairs (same entity class, opposing sides). It gates EVERY setTarget,
    // including retaliation, and that is right for a same-faction hit (never fight a
    // squadmate) but wrong for a player: SEM keeps HurtByTargetGoal on a friendly RU/US
    // unit, so it fights back when shot. Blocking that here would make friendly units
    // pacifist on foot too. The friendly-config exclusion therefore lives in
    // isNonHostile below, which only the PROACTIVE scan goals consult.
    public static boolean isFriendly(AbstractUnit unit, LivingEntity target) {
        return target instanceof AbstractUnit other && isSameFaction(unit, other);
    }

    /**
     * A medic — neutral to every faction. {@link com.neoalive.tacz_sewv.mixin.MixinAbstractUnit}
     * cancels any {@code setTarget} onto one, so no RU/US/PMC unit ever engages it (vanilla monsters
     * already ignore {@code AbstractUnit}s), a nod to the Geneva Convention.
     */
    public static boolean isMedic(LivingEntity entity) {
        return entity instanceof RuMedicEntity || entity instanceof UsMedicEntity;
    }

    public static boolean isEngineer(LivingEntity entity) {
        return entity instanceof RuEngineerEntity || entity instanceof UsEngineerEntity
                || entity instanceof RuCombatEngineerEntity
                || entity instanceof UsCombatEngineerEntity;
    }

    /** Medic or engineer — the support units, which wear headwear only so their skin stays readable. */
    public static boolean isSupportUnit(LivingEntity entity) {
        return isMedic(entity) || isEngineer(entity);
    }

    /**
     * A hull an engineer may repair: empty, or crewed by a same-faction unit. Never enemy-crewed, and
     * never player-crewed (a player is nobody's faction ally). A hull carries no faction of its own —
     * its first passenger defines it, exactly as {@link #isAlliedVehicle} decides line-of-fire.
     */
    public static boolean isFriendlyOrEmptyHull(AbstractUnit unit, VehicleEntity vehicle) {
        Entity driver = vehicle.getFirstPassenger();
        if (driver == null) return true;
        return driver instanceof AbstractUnit crew && isSameFaction(unit, crew);
    }

    /**
     * Whether a mounted crew must not PROACTIVELY acquire {@code target} — the shared exclusion
     * for both auto-target paths ({@link VehicleTargetScanGoal} and {@link CrewTargetPriorityGoal}).
     *
     * <p>Same-faction friends, plus — for an RU/US crew whose SimpleEnemyMod friendly toggle is on
     * — players and PMC units. SEM's {@code usUnitsFriendly}/{@code ruUnitsFriendly} make that
     * faction "friendly with Players and PMC Units", and on foot SEM honours it by simply not
     * installing the player/PMC target goals in {@code setupRoleGoals}. Our vehicle scanners are
     * added on TOP of SEM's selectors, so without this they re-introduce exactly the targeting the
     * flag turned off — a crewed US helicopter opening fire on the player with {@code usUnitsFriendly}
     * true was the reported symptom, and it is not helicopter-specific: every crewed hull scans the
     * same way.
     *
     * <p>Scoped to proactive acquisition on purpose. Retaliation is left to {@link #isFriendly}
     * (which does not shield the player), so a friendly crew still fights back when shot — matching
     * the on-foot unit under the same flag.
     *
     * <p>Also shields a creative/spectator player unconditionally, regardless of faction or the
     * friendly toggle — nothing in this mod's AI should ever proactively engage one, the same
     * convention {@code VehicleTargetScanGoal}/{@code CrewTargetPriorityGoal} already apply inline.
     * Centralized here rather than duplicated a third and fourth time in the engineer's own
     * targeting goals and the recon drone's scan, which is exactly the pair of new call sites that
     * had been missing it — a unit or drone attacking a creative player is the kind of "extremely
     * unusual" behavior a builder/admin testing the map should never see.
     */
    /**
     * Whether a mounted crew must not PROACTIVELY acquire {@code target} — the shared exclusion
     * for both auto-target paths ({@link VehicleTargetScanGoal} and {@link CrewTargetPriorityGoal}).
     *
     * <p>Also shields a creative/spectator player unconditionally, regardless of faction or the
     * friendly toggle — nothing in this mod's AI should ever proactively engage one, the same
     * convention {@code VehicleTargetScanGoal}/{@code CrewTargetPriorityGoal} already apply inline.
     * Centralized here rather than duplicated a third and fourth time in the engineer's own
     * targeting goals and the recon drone's scan, which is exactly the pair of new call sites that
     * had been missing it — a unit or drone attacking a creative player is the kind of "extremely
     * unusual" behavior a builder/admin testing the map should never see.
     *
     * <p>Stage 4: {@link DiplomacyData} {@code ENEMY} is checked <b>before</b> SEM's same-entity-class
     * shortcut ({@link #isFriendly}). Two {@code PmcUnitEntity} crews whose owners are diplomatic
     * enemies are hostile even though they share a class. Combat must not use raw
     * {@link OpenPacCompat#allied} — that stays for Stage 2 map/colour; war/peace is DiplomacyData.
     */
    public static boolean isNonHostile(AbstractUnit unit, LivingEntity target) {
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return true;

        // Explicit enemy list stamped at invasion spawn — allies on our team never count as enemies.
        if (InvasionHostility.isAlly(unit, target)) {
            return true;
        }
        if (InvasionHostility.isEnemy(unit, target)) {
            return false;
        }

        DiplomacyEval dipl = diplomacyEval(unit, target);
        if (dipl.relation == DiplomacyData.Relation.ENEMY) {
            logTargetingDiag(unit, target, dipl, false, isFriendly(unit, target),
                    friendlyFlagShields(unit, target), false, true, "ENEMY");
            return false;
        }

        boolean sameFaction = isFriendly(unit, target);
        boolean friendlyFlag = friendlyFlagShields(unit, target);
        boolean result = sameFaction || friendlyFlag;
        String deciding = sameFaction ? "sameFaction" : friendlyFlag ? "friendlyFlag" : "none";
        logTargetingDiag(unit, target, dipl, false, sameFaction, friendlyFlag, result, false, deciding);
        return result;
    }

    /**
     * Whether {@code player} is a player {@code unit} should help — its own owner, or, when OpenPAC
     * diplomacy is consulted, anyone not flagged {@code ENEMY}. Ownerless PMC crew (FRIENDLY_DEFAULT —
     * village garrisons, berezka structures) default friendly to everyone, same as {@link #isSplashProtected}
     * already treats them.
     *
     * <p>{@link #isNonHostile} is the wrong tool for a PMC asking this: its {@link #friendlyFlagShields}
     * branch only ever answers for an RU/US shooter (built for the RU/US-vs-Player/PMC friendly-toggle
     * case, the only one that existed before PMC auto-revive), so a {@code PmcUnitEntity} target-checking
     * a {@code Player} always fell through to {@code false} there — nothing needed that question until
     * {@code PlayerReviveGoal}. RU/US crews fall back to {@link #isNonHostile}, which already covers the
     * Player case for them via that same toggle.
     */
    public static boolean isFriendlyPlayer(AbstractUnit unit, Player player) {
        if (player.isCreative() || player.isSpectator()) return true;
        if (unit instanceof PmcUnitEntity pmc) {
            UUID owner = pmc.getOwnerUUID();
            if (owner == null) return true;
            if (owner.equals(player.getUUID())) return true;
            DiplomacyEval dipl = diplomacyEval(pmc, player);
            return dipl.consulted && dipl.relation != DiplomacyData.Relation.ENEMY;
        }
        return isNonHostile(unit, player);
    }

    /**
     * Whether SEWV may <b>assign</b> {@code target} to {@code unit} (scan lock, broadcast,
     * fire-mission hand-off, escort inherit). Negation of {@link #isNonHostile} — same SEM
     * faction-friendly rules as on-foot (RU/US↔Player/PMC both ways; PMC↔RU/US when that
     * faction's toggle is on), plus same-faction / creative-spectator and diplomacy ENEMY.
     *
     * <p>Does <b>not</b> replace SEM's own HurtBy retaliation on the unit that was hit; it gates
     * every SEWV-owned path that would spread or invent a lock.
     */
    public static boolean mayAssignTarget(AbstractUnit unit, @Nullable LivingEntity target) {
        return target != null && target.isAlive() && !isNonHostile(unit, target)
                && categoryAllowed(unit, target);
    }

    /**
     * Whether this faction's target-priority allow-list admits {@code e}'s spawn category.
     * Players are not category-gated (callers keep creative/spectator / PMC-never-auto-player).
     * SEM troops ({@link AbstractUnit}) stay politics-only: US units are registered
     * {@code MISC} and RU/PMC {@code MONSTER}, so a category gate would break faction combat.
     * Iron Golems stay explicitly allowed for RU/US even when {@code misc} is excluded.
     */
    public static boolean categoryAllowed(AbstractUnit unit, LivingEntity e) {
        if (e instanceof Player) return true;
        TankFaction faction = factionOf(unit);
        if (faction == null) return false;
        if (e instanceof IronGolem && faction != TankFaction.PMC) return true;
        if (e instanceof AbstractUnit) return true;
        return !WorldTargetPriority.get(unit.level()).isExcluded(faction, e.getType().getCategory().getName());
    }

    /**
     * Whether {@code unit} may proactively acquire {@code e} as a target — the shared gate for
     * every priority-1 auto-target goal ({@link CrewTargetPriorityGoal},
     * {@link com.neoalive.tacz_sewv.entity.ai.goal.SoftEnemyTargetPriorityGoal}). Mirrors
     * {@link VehicleTargetScanGoal}'s faction doctrine, which is SEM's own: PMC crews fight
     * RU/US and hostile mobs but never players or other PMC; RU/US fight players, the opposing
     * faction and hostile mobs. Extracted here (was inline in {@code CrewTargetPriorityGoal})
     * so a second priority-1 targeting goal doesn't have to reduplicate the diplomacy/PMC
     * special-casing and risk drifting from it.
     */
    public static boolean isValidHostileTarget(AbstractUnit unit, LivingEntity e) {
        if (e == unit || !e.isAlive() || !e.isAttackable()) return false;
        if (isNonHostile(unit, e)) return false;
        if (unit.getVehicle() != null && e.getVehicle() == unit.getVehicle()) {
            return false; // riding our own hull — crewmate, or a hugger the tube can't reach
        }

        if (unit instanceof PmcUnitEntity) {
            if (e instanceof PmcUnitEntity) {
                if (isDiplomacyEnemy(unit, e)) {
                    SewvDiag.scan(
                            "VehicleTargeting.isValidHostileTarget ALLOW diplomacyEnemy Pmc "
                                    + "unit={}#{} cand={}#{}",
                            unit.getClass().getSimpleName(), unit.getId(),
                            e.getClass().getSimpleName(), e.getId());
                    return categoryAllowed(unit, e);
                }
                SewvDiag.scan(
                        "VehicleTargeting.isValidHostileTarget REJECT hardPmcExclusion "
                                + "unit={}#{} cand={}#{} isNonHostile=false → DROP (ALLY/NEUTRAL/unresolved)",
                        unit.getClass().getSimpleName(), unit.getId(),
                        e.getClass().getSimpleName(), e.getId());
                return false;
            }
            return categoryAllowed(unit, e);
        }
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator();
        return categoryAllowed(unit, e);
    }

    @Nullable
    private static TankFaction factionOf(AbstractUnit unit) {
        if (unit instanceof RUunitEntity) return TankFaction.RU;
        if (unit instanceof USunitEntity) return TankFaction.US;
        if (unit instanceof PmcUnitEntity) return TankFaction.PMC;
        return null;
    }

    /**
     * True when Stage 4 diplomacy <b>or</b> invasion opposite-team polarity says these two are
     * enemies. Used by {@code MixinAbstractUnit}'s setTarget veto so an enemy pair is not
     * cancelled by the SEM same-class friendly gate, and by PMC's player-target keep so SEM
     * cannot null out an opposing player.
     */
    public static boolean isDiplomacyEnemy(AbstractUnit unit, LivingEntity target) {
        if (InvasionHostility.isEnemy(unit, target)) return true;
        return diplomacyEval(unit, target).relation == DiplomacyData.Relation.ENEMY;
    }

    /** Scoreboard / invasion-tag team on an entity, or empty if untagged. */
    @Nullable
    public static String invasionTeamOf(Entity entity) {
        if (entity == null) return null;
        String team = entity.getPersistentData().getString(InvasionTags.TEAM);
        return team == null || team.isEmpty() ? null : team;
    }

    /** Target's team is on this unit's stamped invasion enemy list. */
    public static boolean isInvasionEnemy(AbstractUnit unit, LivingEntity target) {
        return InvasionHostility.isEnemy(unit, target);
    }

    private record DiplomacyEval(
            @Nullable DiplomacyData.Relation relation,
            boolean consulted,
            @Nullable String selfFaction,
            @Nullable String otherFaction,
            @Nullable UUID owner,
            @Nullable UUID otherOwner,
            @Nullable String otherKind) {
        static final DiplomacyEval NONE = new DiplomacyEval(null, false, null, null, null, null, null);
    }

    /**
     * Resolve OpenPAC faction names for both sides and read {@link DiplomacyData}. Does not use
     * {@link OpenPacCompat#allied}. Returns {@link DiplomacyEval#NONE} when diplomacy cannot apply
     * (non-PMC, missing owners/names, client side).
     */
    private static DiplomacyEval diplomacyEval(AbstractUnit unit, LivingEntity target) {
        if (!(unit instanceof PmcUnitEntity pmc)) return DiplomacyEval.NONE;
        UUID owner = pmc.getOwnerUUID();
        if (owner == null || unit.level().isClientSide || unit.getServer() == null) {
            return DiplomacyEval.NONE;
        }

        UUID otherOwner;
        String otherKind;
        if (target instanceof Player player) {
            otherOwner = player.getUUID();
            otherKind = "Player";
        } else if (target instanceof PmcUnitEntity otherPmc) {
            otherOwner = otherPmc.getOwnerUUID();
            otherKind = "PmcUnit";
            if (otherOwner == null) return DiplomacyEval.NONE;
        } else {
            return DiplomacyEval.NONE;
        }

        String selfFaction = OpenPacCompat.factionName(unit.getServer(), owner);
        String otherFaction = OpenPacCompat.factionName(unit.getServer(), otherOwner);
        if (selfFaction == null || otherFaction == null) {
            return new DiplomacyEval(null, false, selfFaction, otherFaction, owner, otherOwner, otherKind);
        }
        DiplomacyData.Relation rel = DiplomacyData.get(unit.level()).relation(selfFaction, otherFaction);
        return new DiplomacyEval(rel, true, selfFaction, otherFaction, owner, otherOwner, otherKind);
    }

    /** Throttle key → last gameTime logged. */
    private static final java.util.Map<String, Long> TARGET_DIAG_LAST = new java.util.concurrent.ConcurrentHashMap<>();

    private static void logTargetingDiag(AbstractUnit unit, LivingEntity target, DiplomacyEval dipl,
                                         boolean openPacShieldUnused, boolean sameFaction,
                                         boolean friendlyFlag, boolean finalNonHostile,
                                         boolean usesDiplomacyForDecision, String decidingFactor) {
        if (dipl.owner == null || dipl.otherOwner == null) return;

        long now = unit.level().getGameTime();
        String key = dipl.owner + ">" + dipl.otherOwner;
        Long last = TARGET_DIAG_LAST.get(key);
        if (last != null && now - last < 40) return;
        TARGET_DIAG_LAST.put(key, now);

        boolean openPacAllied = unit.getServer() != null
                && OpenPacCompat.allied(unit.getServer(), dipl.owner, dipl.otherOwner);

        String diplomacyNote = dipl.consulted
                ? "DiplomacyData.relation(" + dipl.selfFaction + "," + dipl.otherFaction + ")=" + dipl.relation
                : "DiplomacyData NOT applied (self=" + dipl.selfFaction + " other=" + dipl.otherFaction + ")";

        SewvDiag.targeting(
                "isNonHostile unit={}#{} owner={} target={}#{} otherOwner={} otherKind={} "
                        + "openPacAllied={} (NOT used for combat decision) openPacShieldUnused={} "
                        + "sameFaction(isFriendly)={} friendlyFlagShield={} "
                        + "FINAL_isNonHostile={} USES_DIPLOMACY_DATA_FOR_DECISION={} decidingFactor={} "
                        + "openPacFactionSelf={} openPacFactionOther={} {} gameTime={}",
                unit.getClass().getSimpleName(), unit.getId(), dipl.owner,
                target.getClass().getSimpleName(), target.getId(), dipl.otherOwner, dipl.otherKind,
                openPacAllied, openPacShieldUnused,
                sameFaction, friendlyFlag,
                finalNonHostile, usesDiplomacyForDecision, decidingFactor,
                dipl.selfFaction, dipl.otherFaction, diplomacyNote, now);
    }

    /**
     * Cached copies of SimpleEnemyMod's per-faction "friendly with Players and PMC Units" toggles.
     *
     * <p><b>Why cached and not read live.</b> {@code ForgeConfigSpec.ConfigValue.get()} asserts
     * {@code spec.childConfig != null} and <b>throws</b> if the config has not been baked yet.
     * {@link #friendlyFlagShields} runs from a per-tick AI target scan, and in a heavy modpack —
     * ModernFix and friends reorder and defer startup work — the load order can land such that a
     * crew scans before SEM's config is ready. That is a race, not a logic error, which is exactly
     * why it never reproduced in a lighter instance. Caching removes the per-tick read entirely, so
     * the hot path cannot be exposed to it at all.
     *
     * <p>Defaults match SEM's own ({@code false} = hostile), so a read before the first refresh is
     * both crash-free and the behaviour that predates the friendly-flag feature.
     *
     * <p>{@code volatile} because the refresh and the AI reads are not guaranteed to be the same
     * thread across integrated/dedicated servers; the cost is a plain load on any real CPU.
     */
    private static volatile boolean ruUnitsFriendly = false;
    private static volatile boolean usUnitsFriendly = false;

    /**
     * Where SimpleEnemyMod keeps its per-faction friendly toggles — <b>which class has MOVED between
     * SEM versions</b>. 0.1.5-beta has {@code config.common.FactionsConfig}; older builds have
     * {@code config.CommonConfig}. Field names and types are identical in both, only the owning
     * class differs, so the lookup is by class name, newest first.
     *
     * <p>This is why the reference cannot be a compile-time one: it binds to whichever layout the
     * jar in {@code libs/} happened to have and then dies with {@code NoSuchFieldError} on every
     * other SEM build. Add new homes to the front as SEM moves them again.
     */
    private static final String[] FRIENDLY_CONFIG_CLASSES = {
            "net.nekoyuni.SimpleEnemyMod.config.common.FactionsConfig",
            "net.nekoyuni.SimpleEnemyMod.config.CommonConfig",
    };

    /**
     * Re-read SEM's friendly toggles into the cache. Called on server start — after every mod's
     * config has been baked, and before any entity can tick.
     *
     * <p>Deliberately <b>not</b> driven by {@code ModConfigEvent}: that is dispatched to the event
     * bus of the mod that <em>owns</em> the config, and this config is SimpleEnemyMod's, so our bus
     * never sees it.
     *
     * <p>Consequence worth knowing: editing SEM's toggle <em>while the server runs</em> is not
     * picked up until the next world load. Restarting the world applies it.
     */
    public static void refreshFactionFriendlyFlags() {
        Boolean ru = readSemFriendlyFlag("RU_UNITS_FRIENDLY");
        Boolean us = readSemFriendlyFlag("US_UNITS_FRIENDLY");
        ruUnitsFriendly = ru != null && ru;
        usUnitsFriendly = us != null && us;

        if (ru == null || us == null) {
            // Loud on purpose: silently defaulting to hostile would make a player's
            // usUnitsFriendly=true look like OUR bug rather than a SEM layout change.
            LOGGER.warn("SimpleEnemyMod's faction-friendly toggles were not found in any known config"
                            + " class {}. Treating RU/US crews as hostile to players and PMC units."
                            + " SEM has most likely moved them again — add the new class to"
                            + " VehicleTargeting.FRIENDLY_CONFIG_CLASSES.",
                    String.join(", ", FRIENDLY_CONFIG_CLASSES));
        }
    }

    /**
     * The toggle's value, or {@code null} when this SEM build does not expose it anywhere we know.
     *
     * <p>{@code Throwable} — not {@code Exception} — is the correct catch here, and the distinction
     * is the whole bug: a version mismatch surfaces as {@code NoSuchFieldError}, which extends
     * {@code LinkageError} extends {@code Error}, so an earlier {@code catch (Exception)} around
     * this let it straight through and took the server down at {@code ServerAboutToStart}. The same
     * catch also absorbs the unrelated {@code IllegalStateException} that {@code ConfigValue.get()}
     * throws while a config is still unbaked, so both failure modes degrade to "not friendly".
     */
    private static Boolean readSemFriendlyFlag(String fieldName) {
        for (String className : FRIENDLY_CONFIG_CLASSES) {
            try {
                Object holder = Class.forName(className).getField(fieldName).get(null);
                if (holder instanceof ForgeConfigSpec.ConfigValue<?> value
                        && value.get() instanceof Boolean flag) {
                    return flag;
                }
            } catch (Throwable ignored) {
                // Not this SEM layout (ClassNotFound/NoSuchField), or the config is not baked yet
                // (IllegalState) — try the next known home.
            }
        }
        return null;
    }

    // SEM's per-faction "friendly with Players and PMC Units" toggle — see refreshFactionFriendlyFlags.
    // Both directions: RU/US shooters skip Player/PMC when their flag is on; PMC shooters skip that
    // faction when its flag is on (SEM's on-foot PmcUnitEntity only installs RU/US target goals
    // when the flag is off — VehicleTargetScanGoal used to miss that and keep locking via Enemy).
    private static boolean friendlyFlagShields(AbstractUnit unit, LivingEntity target) {
        if (unit instanceof RUunitEntity) {
            return ruUnitsFriendly && (target instanceof Player || target instanceof PmcUnitEntity);
        }
        if (unit instanceof USunitEntity) {
            return usUnitsFriendly && (target instanceof Player || target instanceof PmcUnitEntity);
        }
        if (unit instanceof PmcUnitEntity) {
            if (target instanceof RUunitEntity) return ruUnitsFriendly;
            if (target instanceof USunitEntity) return usUnitsFriendly;
        }
        return false;
    }

    /**
     * Raw read of {@code unit}'s own faction friendly toggle (false for PMC/anything else) — the
     * same cache {@link #friendlyFlagShields} reads. Lets a caller decide UPFRONT whether Player/PMC
     * targeting should exist at all, mirroring how SEM's own {@code RUunitEntity}/{@code USunitEntity}
     * only ever INSTALL their player-targeting goal when the toggle is off, rather than installing it
     * unconditionally and trusting a runtime predicate alone to keep it from firing.
     */
    public static boolean isFactionFriendly(AbstractUnit unit) {
        if (unit instanceof RUunitEntity) return ruUnitsFriendly;
        if (unit instanceof USunitEntity) return usUnitsFriendly;
        return false;
    }

    /**
     * Whether a crew may pick its own fights. PMC crews obey the SEM order queue: CEASE_FIRE
     * must not pick any, and ATTACK_THAT_TARGET leaves targeting to SEM's specific-target
     * goal (the radio, which outranks every scan). RU/US crews have no orders — they always
     * fight. Shared by {@link VehicleTargetScanGoal} and {@link CrewTargetPriorityGoal},
     * which must never disagree on it.
     */
    public static boolean ordersAllowAutoTargets(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return true;
        OrderType order = pmc.getOrder();
        return order != OrderType.CEASE_FIRE && order != OrderType.ATTACK_THAT_TARGET;
    }

    /**
     * Whether where this crew drives is the player's decision rather than the crew's.
     *
     * <p>True under any standing instruction — an area task, or any order but FREE_FIRE, which is
     * the one that means "do as you see fit". Read by the utility AI, which may not choose a
     * destination of its own while an order stands: {@link #resolveDestination} already reads the
     * area task and the order switch ahead of everything else, and a crew that wandered off under
     * MOVE_TO_POSITION would simply be disobeying.
     *
     * <p>Always false for RU/US, which have no order queue for an order to arrive through.
     */
    public static boolean underStandingOrder(AbstractUnit unit) {
        // Invasion capture is a standing commitment — same hard-gate as a PMC order so the
        // utility layer cannot REGROUP/SEARCH off the pipeline. Command-tier TASKED_* still
        // scores inside fightTick when the coordinator has assigned a play (DriveVehicleGoal).
        if (CaptureOrderSupport.holdsCourseThroughContact(unit)) return true;
        if (EntrenchSupport.isEntrenched(unit)) return true;
        if (unit instanceof PmcUnitEntity pmc) {
            if (FobSupport.holdsRouteThroughContact(pmc)) return true;
            if (FobSupport.blocksOrders(pmc)) return true;
            return ((IVehiclePatrol) pmc).sewv$isPatrolling() || pmc.getOrder() != OrderType.FREE_FIRE;
        }
        return false;
    }

    // Safety margin (blocks) added around a friendly hull's hitbox when testing
    // whether a shot would pass through it — covers near-grazes and shell blast.
    private static final double FRIENDLY_FIRE_MARGIN = 1.0;

    /**
     * True when a same-faction vehicle straddles the muzzle→aimpoint segment, so an
     * AI crew's shot would punch through friendly armor. SBW's fire path never asks
     * what allied hulls are in the way, so without this a crew hoses whatever ally
     * happens to sit between it and its target. Only vehicles crewed by a unit of
     * {@code shooter}'s faction count — the target's own (enemy) hull, empty hulls
     * and wrecks are ignored — and the test is the exact vanilla ray-vs-AABB clip
     * against each candidate, bounded to the shot corridor like {@link SmokeVision}.
     */
    public static boolean alliedVehicleInLineOfFire(AbstractUnit shooter, VehicleEntity self, Vec3 from, Vec3 to) {
        AABB corridor = new AABB(from, to).inflate(FRIENDLY_FIRE_MARGIN);
        for (VehicleEntity v : self.level().getEntitiesOfClass(VehicleEntity.class, corridor,
                veh -> veh != self && !veh.isWreck())) {
            if (!isAlliedVehicle(shooter, v)) continue;
            if (v.getBoundingBox().inflate(FRIENDLY_FIRE_MARGIN).clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a protected Player or PMC sits within {@code radius} of {@code point} —
     * the danger-close hold for splash weapons. Radius {@code <= 0} disables the check.
     * Does not treat the shooter's current enemy target as protected.
     */
    public static boolean friendlyNearPoint(AbstractUnit shooter, Vec3 point, double radius) {
        if (radius <= 0.0) return false;
        double r2 = radius * radius;
        AABB box = new AABB(point, point).inflate(radius);
        LivingEntity target = shooter.getTarget();
        for (LivingEntity e : shooter.level().getEntitiesOfClass(LivingEntity.class, box,
                living -> living.isAlive() && living != shooter && living != target)) {
            if (e.distanceToSqr(point) > r2) continue;
            if (isSplashProtected(shooter, e)) return true;
        }
        return false;
    }

    /**
     * Who a splash hold must not kill: the PMC's owning player (and non-ENEMY diplomacy
     * players when OpenPAC resolves), non-hostile PMC units, and — for RU/US when SEM's
     * friendly toggle is on — Player/PMC via {@link #friendlyFlagShields}.
     */
    private static boolean isSplashProtected(AbstractUnit shooter, LivingEntity ally) {
        if (ally instanceof Player player) {
            if (player.isSpectator()) return false;
            if (shooter instanceof PmcUnitEntity pmc) {
                UUID owner = pmc.getOwnerUUID();
                if (owner != null && owner.equals(player.getUUID())) return true;
                DiplomacyEval dipl = diplomacyEval(pmc, player);
                return dipl.consulted && dipl.relation != DiplomacyData.Relation.ENEMY;
            }
            return friendlyFlagShields(shooter, player);
        }
        if (ally instanceof PmcUnitEntity pmc) {
            return isNonHostile(shooter, pmc);
        }
        return false;
    }

    // A hull counts as friendly when any occupant is a same-faction unit. A hull is
    // not a LivingEntity and carries no faction of its own, so its crew defines it.
    private static boolean isAlliedVehicle(AbstractUnit shooter, VehicleEntity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof AbstractUnit crew && isSameFaction(shooter, crew)) return true;
        }
        return false;
    }

    // Package-visible: the obstacle filters in DriveVehicleGoal (hulls) and
    // DriveHelicopterGoal (airframes) define "ally" with this same test, so assist
    // doctrine and collision doctrine can't diverge.
    //
    // PMC is one SEM class, but Stage-4 diplomacy / invasion lists split it: two
    // PmcUnitEntity whose owners are ENEMY are not allies. Treating them as one
    // faction made enemy tanks count as friendly hulls (cannon held for "allied LOF",
    // force-ratio counted them as friends, HurtBy refused to retaliate).
    public static boolean isSameFaction(AbstractUnit unit, AbstractUnit other) {
        if (unit == other) return true;
        if (unit instanceof RUunitEntity) return other instanceof RUunitEntity;
        if (unit instanceof USunitEntity) return other instanceof USunitEntity;
        if (!(unit instanceof PmcUnitEntity) || !(other instanceof PmcUnitEntity)) return false;
        return !isDiplomacyEnemy(unit, other);
    }
}
