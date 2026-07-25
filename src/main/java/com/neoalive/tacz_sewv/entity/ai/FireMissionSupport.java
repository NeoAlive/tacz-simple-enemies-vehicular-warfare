package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        CAS
    }

    /** Ask for anything that will answer. */
    public static final Set<Kind> ANY = EnumSet.allOf(Kind.class);

    /**
     * What this unit is manning, or null if a fire mission would mean nothing to it.
     *
     * <p>A rifleman is deliberately absent: it can see whatever it can shoot, so designating for it
     * would only override the target it already picked for itself.
     */
    @Nullable
    public static Kind kindOf(AbstractUnit unit) {
        if (MortarSupport.hasMortarClaim(unit)) return Kind.MORTAR;
        if (TowSupport.isCrewing(unit)) return Kind.TOW;
        if (isPlanePilot(unit)) return Kind.CAS;
        return null;
    }

    private static boolean isPlanePilot(AbstractUnit unit) {
        return unit.getVehicle() instanceof VehicleEntity v
                && v.getFirstPassenger() == unit
                && HullFacts.isPlaneHull(v);
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
        AABB box = new AABB(origin, origin).inflate(range, range + VERTICAL_REACH, range);
        double rangeSq = range * range;
        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class, box)) {
            if (!unit.isAlive() || CrewFacts.factionOfCrew(unit) != faction) continue;
            if (faction == CrewFacts.Faction.PMC
                    && !(unit instanceof PmcUnitEntity pmc && owner.equals(pmc.getOwnerUUID()))) {
                continue;
            }
            Kind kind = kindOf(unit);
            if (kind == null || !kinds.contains(kind)) continue;

            double dx = unit.getX() - origin.x;
            double dz = unit.getZ() - origin.z;
            if (dx * dx + dz * dz <= rangeSq) crews.add(unit); // horizontal — altitude-independent
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
        for (AbstractUnit crew : crewsInRange(level, faction, owner, origin, range, ANY)) {
            Kind kind = kindOf(crew);
            if (kind != null) available.add(kind);
        }
        return available;
    }

    /**
     * Puts every matching crew in range onto {@code target}, and reports how many took it.
     *
     * <p>For a mounted TOW crew the PMC order also stands {@link VehicleTargetScanGoal} down, which
     * yields under ATTACK_THAT_TARGET rather than fight SEM's goal for the TARGET flag.
     */
    public static int callFireMission(Level level, @Nullable CrewFacts.Faction faction,
                                      @Nullable UUID owner, Vec3 origin, double range,
                                      LivingEntity target, Set<Kind> kinds) {
        List<AbstractUnit> crews = crewsInRange(level, faction, owner, origin, range, kinds);
        for (AbstractUnit crew : crews) {
            if (crew instanceof PmcUnitEntity pmc) {
                pmc.setAttackTargetId(target.getId());
                pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
            } else {
                // No order queue on RU/US — hand the target over directly. Their own scan may
                // replace it later, which is the same deal a drone's relayed sighting gets.
                crew.setTarget(target);
            }
        }
        return crews.size();
    }

    /** The radio's own call: a player's PMC crews, any kind of weapon. */
    public static int callFireMission(Level level, @Nullable UUID owner, Vec3 origin,
                                      double range, LivingEntity target) {
        return callFireMission(level, CrewFacts.Faction.PMC, owner, origin, range, target, ANY);
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
            if (!(crew instanceof PmcUnitEntity pmc) || pmc.getOrder() != OrderType.ATTACK_THAT_TARGET) {
                continue;
            }
            pmc.setOrder(OrderType.FREE_FIRE);
            pmc.setAttackTargetId(-1);
            released++;
        }
        return released;
    }
}
