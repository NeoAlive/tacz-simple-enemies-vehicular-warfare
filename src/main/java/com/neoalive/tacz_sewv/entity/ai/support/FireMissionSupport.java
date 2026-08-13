package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IDelayedFire;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.compat.AshMissileSupport;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;

/**
 * Designating a target for crews that cannot find their own, shared by
 * {@link com.neoalive.tacz_sewv.item.HandheldRadioItem}, {@link RadioObserverGoal} and the
 * vehicle crews' own Call Mortars / Call TOW / Call CAS actions.
 *
 * <p>Every weapon this serves outranges the eyes behind it. A mortar shoots ~770 blocks and its
 * crew sees SEM's FOLLOW_RANGE of 96 (and only ±4 vertically). A TOW crew is mounted, so it gets
 * {@link VehicleTargetScanGoal}'s cylinder instead — but that is still only
 * {@code vehicleTargetScanRadius} of level ground with line of sight, which is not where you want
 * to be choosing which tank in a column dies first. An aircraft outruns and out-climbs its pilot's
 * eyes entirely. The radio is how a target reaches any of them.
 *
 * <p><b>Delivery differs by faction and has to.</b> SEM gives {@code setOrder} and
 * {@code setAttackTargetId} to {@link PmcUnitEntity} alone, so a PMC crew takes a standing order
 * (which SEM's own priority-0 goal re-forces every 5 ticks, overriding the crew's own scan) while
 * an RU/US crew — which has no order queue for an order to arrive through — is handed the target
 * directly, the same way {@link DroneSupport#broadcastTarget} relays a drone's sighting.
 */
public final class FireMissionSupport {

    private FireMissionSupport() {}

    // How far up/down the fire-mission query reaches, on top of the horizontal range. A plane crew
    // cruises 100+ blocks up, so a symmetric ±range box would drop it whenever the range is smaller
    // than the plane's altitude; the box is made tall and the range itself checked HORIZONTALLY, which
    // is what a top-down radio designation wants anyway.
    private static final double VERTICAL_REACH = 512.0;

    /** Every kind of supporting fire a crew can be asked for. */
    public enum Kind {
        /** Indirect fire from a mortar tube. */
        MORTAR,
        /** A guided missile from a TOW launcher. */
        TOW,
        /** Close air support: an AI-flown aircraft already airborne nearby. */
        CAS,
        /** ASH coordinate ballistic launcher (Sapsan) — stop, arm pod, fire at a mark. */
        MISSILE_SYSTEM,
        /** Self-propelled / bindable artillery ({@link ArtillerySupport}). */
        ARTILLERY
    }

    /** Ask for anything that will answer. */
    public static final Set<Kind> ANY = EnumSet.allOf(Kind.class);

    /** Supporting crews, rebuilt slowly instead of once per caller in each caller's range box. */
    private static final Map<Level, SupportRoster> ROSTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * What this unit is manning, or null if a fire mission would mean nothing to it.
     *
     * <p>A rifleman is deliberately absent: it can see whatever it can shoot, so designating for it
     * would only override the target it already picked for itself.
     */
    @Nullable
    public static Kind kindOf(AbstractUnit unit) {
        if (MortarSupport.hasMortarClaim(unit)) return Kind.MORTAR;
        if (VehicleMortarSupport.isCrewing(unit)) return Kind.MORTAR;
        if (TowSupport.isCrewing(unit)) return Kind.TOW;
        if (AshMissileSupport.isCrewing(unit)) return Kind.MISSILE_SYSTEM;
        if (ArtillerySupport.isCrewing(unit)) return Kind.ARTILLERY;
        if (isPlanePilot(unit)) return Kind.CAS;
        return null;
    }

    private static boolean isPlanePilot(AbstractUnit unit) {
        if (!(unit.getVehicle() instanceof VehicleEntity v) || v.getFirstPassenger() != unit) return false;
        return HullFacts.isPlaneHull(v);
    }

    /**
     * Crews on our side, within {@code range} (horizontal) of {@code origin}, manning one of
     * {@code kinds}.
     *
     * <p>"Our side" is the faction, plus — for PMC only — the same owning player: one player's
     * mortars must not answer another's call, while RU and US have no owners to distinguish.
     *
     * <p>A crew in an unloaded chunk can't be found, which is what mortarChunkLoading keeps from
     * happening.
     */
    public static List<AbstractUnit> crewsInRange(Level level, @Nullable CrewFacts.Faction faction,
                                                  @Nullable UUID owner, Vec3 origin, double range,
                                                  Set<Kind> kinds) {
        if (faction == null || kinds.isEmpty()) return List.of();
        // A PMC call with no owner has nobody to answer it: an ownerless PMC crew (a friendly camp
        // garrison) belongs to no commander, so there is no "our side" to resolve.
        if (faction == CrewFacts.Faction.PMC && owner == null) return List.of();

        List<AbstractUnit> crews = new ArrayList<>();
        for (SupportCrew crew : supportInRange(level, faction, owner, origin, range, kinds)) {
            crews.add(crew.unit);
        }
        return crews;
    }

    /**
     * Which kinds of support could actually be called from here — the doc's Communication state.
     *
     * <p>This is the expensive half of asking for support (it scans the world), so callers gate it
     * on the cheap half first: whether anyone on our side is carrying a radio at all.
     */
    public static Set<Kind> availableSupport(Level level, @Nullable CrewFacts.Faction faction,
                                             @Nullable UUID owner, Vec3 origin, double range) {
        EnumSet<Kind> available = EnumSet.noneOf(Kind.class);
        for (SupportCrew crew : supportInRange(level, faction, owner, origin, range, ANY)) {
            available.add(crew.kind);
        }
        return available;
    }

    /**
     * Puts every matching crew in range onto {@code target}.
     *
     * <p>For a mounted TOW crew the PMC order also stands {@link VehicleTargetScanGoal} down, which
     * yields under ATTACK_THAT_TARGET rather than fight SEM's goal for the TARGET flag.
     *
     * <p>{@link Call#kinds()} is the set of support types that actually answered — the radio ack
     * picks its voiceline from that set, not from what was polled.
     */
    public static Call callFireMission(Level level, @Nullable CrewFacts.Faction faction,
                                       @Nullable UUID owner, Vec3 origin, double range,
                                       LivingEntity target, Set<Kind> kinds) {
        List<SupportCrew> crews = supportInRange(level, faction, owner, origin, range, kinds);
        EnumSet<Kind> triggered = EnumSet.noneOf(Kind.class);
        for (SupportCrew support : crews) {
            AbstractUnit crew = support.unit;
            triggered.add(support.kind);
            // Nothing on this path names ordnance — an observer relaying a contact and a tank
            // crew asking for air are both reporting a target, not choosing how it is attacked.
            // So the aircraft is put back on AUTO rather than left on it: the radio GUI's choice
            // is a standing instruction that lasts the rest of the sortie, so a plane last told
            // BOMB by hand would answer an automatic call with a bombing run at whatever the
            // observer happened to see, including infantry twenty blocks from the observer.
            if (support.kind == Kind.CAS && crew instanceof IHelicopterPilot pilot) {
                pilot.sewv$setPlaneAttackMode(PlaneAttackMode.AUTO);
            }
            if (crew instanceof PmcUnitEntity pmc) {
                pmc.setAttackTargetId(target.getId());
                pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
            } else if (VehicleTargeting.mayAssignTarget(crew, target)) {
                // No order queue on RU/US — hand the target over directly. Honour SEM's
                // faction-friendly toggles so a radio/utility call cannot put a friendly
                // player/PMC onto an RU/US tube.
                crew.setTarget(target);
            }
        }
        return new Call(crews.size(), triggered);
    }

    /** The radio's own call: a player's PMC crews, any kind of weapon. */
    public static Call callFireMission(Level level, @Nullable UUID owner, Vec3 origin,
                                       double range, LivingEntity target) {
        return callFireMission(level, CrewFacts.Faction.PMC, owner, origin, range, target, ANY);
    }

    /** Shell-flight compensation subtracted from the player-chosen delay before stamping crews. */
    private static final int DELAY_FLIGHT_COMPENSATION_SEC = 10;

    /**
     * Radio GUI call: filtered by {@code frequency}, optional coordinated delay on mortar crews,
     * entity or grid designation.
     */
    public static Call callRadioMission(Level level, @Nullable UUID owner, Vec3 origin,
                                        double range, RadioFrequency frequency,
                                        @Nullable LivingEntity entityTarget, @Nullable BlockPos posTarget,
                                        int delaySeconds, PlaneAttackMode planeMode) {
        Set<Kind> kinds = frequency.kinds();
        List<SupportCrew> crews = supportInRange(level, CrewFacts.Faction.PMC, owner, origin, range, kinds);
        if (crews.isEmpty()) return new Call(0, EnumSet.noneOf(Kind.class));

        // The ordnance choice rides with the target designation and is stamped on every aircraft
        // this call reaches, whether or not the target itself resolves — a crew told "bombs" keeps
        // that instruction for the rest of the sortie, which is what makes the button mean
        // anything. Only CAS crews carry it; a mortar has one thing to shoot.
        for (SupportCrew support : crews) {
            if (support.kind == Kind.CAS && support.unit instanceof IHelicopterPilot pilot) {
                pilot.sewv$setPlaneAttackMode(planeMode);
            }
        }

        if (delaySeconds > 0 && frequency.supportsDelay()) {
            applyMortarDelay(level, crews, delaySeconds);
        }

        EnumSet<Kind> triggered = EnumSet.noneOf(Kind.class);
        int ordered = 0;
        if (posTarget != null) {
            for (SupportCrew support : crews) {
                // Mortars and CAS both take a standing grid mark; TOW/artillery need a live lock.
                if (support.kind != Kind.MORTAR && support.kind != Kind.CAS) continue;
                if (!(support.unit instanceof IMortarCrew crew)) continue;
                triggered.add(support.kind);
                ordered++;
                crew.sewv$setFireMission(FireMission.standing(posTarget));
                if (support.unit instanceof PmcUnitEntity pmc) {
                    pmc.setOrder(OrderType.FREE_FIRE);
                    pmc.setAttackTargetId(-1);
                } else {
                    support.unit.setTarget(null);
                }
            }
        } else if (entityTarget != null) {
            for (SupportCrew support : crews) {
                AbstractUnit crew = support.unit;
                // A fresh entity designation replaces any standing grid mark.
                if (crew instanceof IMortarCrew mortar) {
                    mortar.sewv$setFireMission(null);
                }
                triggered.add(support.kind);
                if (crew instanceof PmcUnitEntity pmc) {
                    pmc.setAttackTargetId(entityTarget.getId());
                    pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
                    ordered++;
                } else if (VehicleTargeting.mayAssignTarget(crew, entityTarget)) {
                    crew.setTarget(entityTarget);
                    ordered++;
                }
            }
        }
        return new Call(ordered, triggered);
    }

    private static void applyMortarDelay(Level level, List<SupportCrew> crews, int delaySeconds) {
        int effective = Math.max(1, delaySeconds - DELAY_FLIGHT_COMPENSATION_SEC);
        long deadline = level.getGameTime() + (long) effective * 20L;
        for (SupportCrew support : crews) {
            if (support.kind != Kind.MORTAR) continue;
            if (support.unit instanceof IDelayedFire delayed) {
                delayed.sewv$setFireDelayUntil(deadline);
            }
        }
    }

    /**
     * One PMC radio ack for the kinds that answered. Single-kind calls map 1:1
     * (mortar→pmc_mortar, CAS→pmc_cas, TOW→pmc_tow); a mixed answer picks one of those pools
     * at random — never a generic line.
     *
     * <p>When the call named ordnance, the CAS ack becomes the line for <b>that</b> ordnance —
     * a substitution, not an addition, because two voice clips played at once are just noise and
     * "bombing run" already says everything "aircraft inbound" would. {@code AUTO} keeps the plain
     * CAS line: it picks a weapon per target, so there is no single thing to announce.
     */
    @Nullable
    public static SoundEvent ackFor(Set<Kind> triggered, PlaneAttackMode mode) {
        List<SoundPool> pools = new ArrayList<>(3);
        if (triggered.contains(Kind.MORTAR)) pools.add(ModSounds.PMC_MORTAR);
        if (triggered.contains(Kind.TOW)) pools.add(ModSounds.PMC_TOW);
        if (triggered.contains(Kind.CAS)) pools.add(switch (mode) {
            case BOMB -> ModSounds.PMC_BOMBING;
            case CANNON -> ModSounds.PMC_CANNON;
            case GUIDED -> ModSounds.PMC_ATS;
            case AUTO -> ModSounds.PMC_CAS;
        });
        // Indirect fires share the mortar ack — area fires, not direct-support lines.
        if ((triggered.contains(Kind.MISSILE_SYSTEM) || triggered.contains(Kind.ARTILLERY))
                && !triggered.contains(Kind.MORTAR)) {
            pools.add(ModSounds.PMC_MORTAR);
        }
        if (pools.isEmpty()) return null;
        return pools.get(ThreadLocalRandom.current().nextInt(pools.size())).next();
    }

    /** How many crews took the mission, and which support kinds they were. */
    public record Call(int ordered, Set<Kind> kinds) {
        public boolean empty() {
            return ordered == 0;
        }
    }

    /**
     * Ends the fire mission for every crew in range, and reports how many were on one.
     *
     * <p>Dropping the order is enough on its own: SEM's goal goes false, and its stop() clears the
     * target and the stored id for us — after which the crew is back on its own targeting. RU/US
     * crews were never given an order to drop, so this only concerns PMC.
     */
    public static int standDown(Level level, @Nullable UUID owner, Vec3 origin, double range) {
        int released = 0;
        for (AbstractUnit crew : crewsInRange(level, CrewFacts.Faction.PMC, owner, origin, range, ANY)) {
            boolean hadOrder = crew instanceof PmcUnitEntity pmc
                    && pmc.getOrder() == OrderType.ATTACK_THAT_TARGET;
            boolean hadMark = crew instanceof IMortarCrew mortar
                    && mortar.sewv$getFireMission() != null;
            if (!hadOrder && !hadMark) continue;

            if (crew instanceof PmcUnitEntity pmc) {
                pmc.setOrder(OrderType.FREE_FIRE);
                pmc.setAttackTargetId(-1);
            }
            if (crew instanceof IMortarCrew mortar) {
                mortar.sewv$setFireMission(null);
            }
            if (crew instanceof IDelayedFire delayed) {
                delayed.sewv$setFireDelayUntil(0L);
            }
            released++;
        }
        return released;
    }

    /**
     * True when crews would answer this call but not one of them can see the target — the case
     * worth refusing, because a direct-fire crew given a target behind a hill sits doing nothing.
     *
     * <p>Deliberately answers <b>false</b> when no crew is in range at all: that is a different
     * failure with its own message, and reporting it as "obstructed" would send the player looking
     * for cover that is not the problem.
     */
    public static boolean noCrewCanSee(Level level, @Nullable CrewFacts.Faction faction, @Nullable UUID owner,
                                       Vec3 origin, double range, Set<Kind> kinds, LivingEntity target) {
        boolean any = false;
        for (SupportCrew crew : supportInRange(level, faction, owner, origin, range, kinds)) {
            if (crew.unit.hasLineOfSight(target)) return false;
            any = true;
        }
        return any;
    }

    private static List<SupportCrew> supportInRange(Level level, CrewFacts.Faction faction, UUID owner,
                                                    Vec3 origin, double range, Set<Kind> kinds) {
        if (faction == null || kinds.isEmpty()) return List.of();
        if (faction == CrewFacts.Faction.PMC && owner == null) return List.of();

        SupportRoster roster = ROSTERS.computeIfAbsent(level, ignored -> new SupportRoster());
        long now = level.getGameTime();
        if (now >= roster.nextRefresh) rebuildRoster(level, roster, now);

        List<SupportCrew> crews = new ArrayList<>();
        double rangeSq = range * range;
        for (RosterEntry entry : roster.byFaction.get(faction)) {
            if (!kinds.contains(entry.kind) || !(level.getEntity(entry.id) instanceof AbstractUnit unit)
                    || !unit.isAlive()) {
                continue;
            }
            if (faction == CrewFacts.Faction.PMC
                    && (!(unit instanceof PmcUnitEntity pmc) || !owner.equals(pmc.getOwnerUUID()))) {
                continue;
            }
            if (Math.abs(unit.getY() - origin.y) > range + VERTICAL_REACH) continue;
            double dx = unit.getX() - origin.x;
            double dz = unit.getZ() - origin.z;
            if (dx * dx + dz * dz <= rangeSq) crews.add(new SupportCrew(unit, entry.kind));
        }
        return crews;
    }

    private static void rebuildRoster(Level level, SupportRoster roster, long now) {
        for (List<RosterEntry> entries : roster.byFaction.values()) entries.clear();
        if (!(level instanceof ServerLevel server)) {
            roster.nextRefresh = now + SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get();
            return;
        }
        for (AbstractUnit unit : server.getEntities(EntityTypeTest.forClass(AbstractUnit.class), e -> true)) {
            CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
            Kind kind = faction != null && unit.isAlive() ? kindOf(unit) : null;
            if (kind != null) roster.byFaction.get(faction).add(new RosterEntry(unit.getId(), kind));
        }
        roster.nextRefresh = now + SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get();
    }

    private record SupportCrew(AbstractUnit unit, Kind kind) {}

    private record RosterEntry(int id, Kind kind) {}

    private static final class SupportRoster {
        final EnumMap<CrewFacts.Faction, List<RosterEntry>> byFaction =
                new EnumMap<>(CrewFacts.Faction.class);
        long nextRefresh = Long.MIN_VALUE;

        SupportRoster() {
            for (CrewFacts.Faction faction : CrewFacts.Faction.values()) {
                this.byFaction.put(faction, new ArrayList<>());
            }
        }
    }
}
