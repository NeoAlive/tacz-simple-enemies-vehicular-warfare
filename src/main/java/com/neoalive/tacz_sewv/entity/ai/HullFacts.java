package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Drivetrain facts about the hull a crew is riding, cached for its lifetime.
 *
 * <p>Both are read from {@code data().compute()}, which is far too expensive to call from
 * an AI tick, and neither can change mid-drive — so they are resolved once per hull and
 * held. {@link #attach} is keyed on entity identity, so calling it every tick costs a
 * reference comparison and re-boarding the same hull reuses the answers.
 *
 * <p>Reads are defensive: unreadable hull data must never crash the AI tick, and each
 * fact falls back to the answer whose behaviour is the safe one.
 */
public final class HullFacts {

    private VehicleEntity vehicle;
    private boolean helicopter;
    private boolean tracked;
    private boolean ship;
    private boolean ifv;
    private Set<Integer> crewSeats = Set.of(0);
    private Set<Integer> climbSeats = Set.of();

    void attach(VehicleEntity v) {
        if (this.vehicle == v) return;
        this.vehicle = v;
        this.helicopter = computeHelicopter(v);
        this.tracked = computeTracked(v);
        this.ship = computeShip(v);
        this.ifv = computeIfv(v);
        this.crewSeats = this.ifv ? computeCrewSeats(v) : Set.of(0);
        this.climbSeats = SewvConfig.TANK_RIDER_DISMOUNT_ENABLED.get() ? computeClimbSeats(v) : Set.of();
    }

    /** Helicopters and fixed-wing (which subclass {@link EngineInfo.Helicopter}) fly, not drive. */
    boolean isHelicopter() {
        return this.helicopter;
    }

    /** Tracked hulls pivot in place; wheeled ones must roll through a turn. */
    boolean isTracked() {
        return this.tracked;
    }

    /**
     * Ships ({@link EngineInfo.Ship}) drive through {@link DriveVehicleGoal} the same as any
     * ground hull — same input flags, same combat doctrine, same terrain sensor (already
     * amphibious-aware, see {@code GroundTerrainSensor.computeAmphibious}) — the only thing this
     * fact changes is which {@code PathFinder}/node evaluator pair {@code recomputePath} picks,
     * since a hull that floats needs {@code ShipVehicleNodeEvaluator}'s water-surface search
     * instead of {@code GroundVehicleNodeEvaluator}'s land one (which explicitly rejects water).
     * {@code isTracked()} is already correctly {@code false} for a ship (no {@code TrackRotSpeed}
     * in its data), so the existing wheeled "roll through the turn" steering branch already
     * matches a ship's own physics (turn rate scales with speed — it can't pivot at a standstill)
     * with no changes needed there either.
     */
    boolean isShip() {
        return this.ship;
    }

    /**
     * Carries a dismount squad rather than just a crew, so it puts its infantry on the ground when
     * the shooting starts — see {@link DriveVehicleGoal#dismountSquad}.
     */
    boolean isIfv() {
        return this.ifv;
    }

    /**
     * The seats that stay buttoned up on an IFV: the <b>driver</b> and the <b>turret</b>. Everyone
     * else is a dismount.
     *
     * <p>Deliberately not "every seat that has a weapon", which is the obvious rule and does not
     * survive the actual data: SuperbWarfare's own {@code bmp_2} arms all six of its rear seats
     * with firing-port MGs, so that rule would empty nothing at all from one of the commonest RU
     * hulls, while {@code t_90a} — a tank — has a weaponless seat that rule would empty.
     * Counting weapons finds the turret wherever the pack put it, which matters because that is
     * not a convention either: SBW and MCSP hulls put the turret in seat 0, while FCP's BMPs put a
     * <em>weaponless driver</em> there and the turret in seat 1. Seat 0 is always the driver
     * regardless — SuperbWarfare's driver is simply the first passenger.
     */
    Set<Integer> crewSeats() {
        return this.crewSeats;
    }

    /**
     * Seats posed as a tank-rider handhold (SuperbWarfare's {@code Pose == "Climb"}) rather than a
     * real crew station — {@code DriveVehicleGoal}'s dismount-utilization feature empties these out
     * in combat and {@code SeekAbandonedVehicleGoal} lets an idle unit refill them once quiet.
     *
     * <p>Deliberately NOT "seats with no weapon", the same trap {@link #crewSeats} already avoids
     * for IFVs: {@code m_1a_2}/{@code ztz_99a} both carry a SECOND weaponless seat (a loader/extra
     * crewman) alongside their two actual {@code Climb} seats, and a "no weapon" rule would empty
     * that seat too — exactly the "commander walks off the tank" bug this is written to not have.
     * {@code Pose} is a real, SBW-rendered animation state (see {@code HumanoidModelMixin}, which
     * special-cases {@code "Climb"}/{@code "Pilot"}/{@code "Tow"}/{@code "Stand"} for how a
     * passenger is drawn), not a loose convention — any addon hull wanting a rider visibly clinging
     * to the exterior has to use the same value to get that rendering, so this holds up on
     * MCSP/FCP/ashvehicles hulls the same way it does on SuperbWarfare's own.
     */
    Set<Integer> climbSeats() {
        return this.climbSeats;
    }

    private static Set<Integer> computeClimbSeats(VehicleEntity v) {
        try {
            int seats = Math.max(1, v.getMaxPassengers());
            Set<Integer> result = new HashSet<>();
            for (int seat = 0; seat < seats; seat++) {
                SeatInfo info = v.getSeat(seat);
                if (info != null && "Climb".equals(info.pose)) result.add(seat);
            }
            return result;
        } catch (Exception ignored) {
            return Set.of(); // unreadable seats: nothing to dismount rather than guessing
        }
    }

    /**
     * True when {@code v} has at least one unoccupied seat and EVERY unoccupied seat is a
     * {@link #climbSeats} seat — the signal {@code SeekAbandonedVehicleGoal} uses to let a
     * still-crewed tank-rider hull count as worth boarding without reintroducing the IFV recall
     * ({@code DriveVehicleGoal#dismountSquad}'s troop-compartment seats are never {@code Climb}-posed,
     * so they never match this and stay excluded exactly as before). A vacant DRIVER/gunner/commander
     * seat does not qualify — this is refilling a hitchhiker slot, not reinforcing a partial crew.
     *
     * <p>Static, not part of the per-instance cache above: this evaluates arbitrary CANDIDATE hulls
     * during a scavenging scan, not the one hull a drive goal is attached to for its whole tick.
     */
    static boolean hasFreeClimbSeat(VehicleEntity v) {
        if (!SewvConfig.TANK_RIDER_DISMOUNT_ENABLED.get()) return false;
        try {
            int seats = Math.max(1, v.getMaxPassengers());
            Set<Integer> occupied = new HashSet<>();
            for (Entity passenger : v.getPassengers()) {
                int seat = v.getSeatIndex(passenger);
                if (seat >= 0) occupied.add(seat);
            }
            boolean foundFreeClimb = false;
            for (int seat = 0; seat < seats; seat++) {
                if (occupied.contains(seat)) continue;
                SeatInfo info = v.getSeat(seat);
                if (info == null || !"Climb".equals(info.pose)) return false; // a non-climb seat is free
                foundFreeClimb = true;
            }
            return foundFreeClimb;
        } catch (Exception ignored) {
            return false; // unreadable: don't treat as board-worthy
        }
    }

    private static Set<Integer> computeCrewSeats(VehicleEntity v) {
        int turret = 0;
        int most = -1;
        try {
            int seats = Math.max(1, v.getMaxPassengers());
            for (int seat = 0; seat < seats; seat++) {
                SeatInfo info = v.getSeat(seat);
                List<String> weapons = info == null ? null : info.weapons();
                int count = weapons == null ? 0 : weapons.size();
                if (count > most) {
                    most = count;
                    turret = seat;
                }
            }
        } catch (Exception ignored) {
            return Set.of(0); // unreadable seats: keep everyone aboard rather than empty the hull
        }
        // Set.of rejects duplicates, and on most hulls the driver IS the turret.
        return turret == 0 ? Set.of(0) : Set.of(0, turret);
    }

    /**
     * Matched on the registry id rather than read from the hull data, because <b>nothing in the
     * vehicle data says "IFV"</b>. Seat counts don't separate them (a T-90A has a spare seat too)
     * and neither do weapons (SuperbWarfare's BMP-2 arms all six of its rear seats with
     * firing-port MGs). The id is the only signal that actually tracks the vehicle class, which
     * is why the clue list is config — an addon's hull is caught without this mod knowing it.
     */
    /**
     * The same IFV test, for callers with no crew to attach a {@link HullFacts} to (the map
     * markers, which classify a hull they only ever look at). Cheap — a registry-id string match,
     * no vehicle-data compute.
     */
    public static boolean isIfvHull(VehicleEntity v) {
        return computeIfv(v);
    }

    private static boolean computeIfv(VehicleEntity v) {
        if (!SewvConfig.IFV_DISMOUNTS_ENABLED.get()) return false;
        try {
            String id = ForgeRegistries.ENTITY_TYPES.getKey(v.getType()).toString().toLowerCase(Locale.ROOT);
            for (String clue : SewvConfig.IFV_NAME_CLUES.get()) {
                if (!clue.isBlank() && id.contains(clue.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean computeHelicopter(VehicleEntity v) {
        try {
            return v.getEngineInfo() instanceof EngineInfo.Helicopter;
        } catch (Exception ignored) {
            return false;
        }
    }

    // computed() (static datapack data), NOT getEngineInfo() — that field is populated lazily on
    // the hull's first travel(), so a crew whose goal evaluates on the spawn tick would read null
    // and, because attach() caches per hull identity, keep that wrong answer for the hull's life.
    /**
     * The same ship test for callers with no crew to attach a {@link HullFacts} to — the radio,
     * which needs to know whether the hull under a speaker is a boat to pick the right voice pool.
     */
    public static boolean isShipHull(VehicleEntity v) {
        return computeShip(v);
    }

    private static boolean computeShip(VehicleEntity v) {
        try {
            return v.computed().getEngineType() == EngineType.SHIP;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean computeTracked(VehicleEntity v) {
        try {
            var data = v.data().compute();
            if (data != null) {
                var trackRotSpeed = data.getEngineInfo().get("TrackRotSpeed");
                return trackRotSpeed != null && trackRotSpeed.getAsInt() > 0;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
