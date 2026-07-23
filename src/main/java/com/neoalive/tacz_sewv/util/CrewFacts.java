package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Who is crewing a hull, answered from the passenger list alone.
 *
 * <p>Both answers require the crew to be <b>unanimous</b>: an empty hull has no crew to report, a
 * hull with a player aboard is the player's business, and a mixed crew (a vehicle captured
 * mid-fight) is genuinely ambiguous — saying nothing beats picking a side. Note "empty" needs no
 * separate test: an empty list has no first element for the rest to match.
 *
 * <p>This is deliberately readable on <b>both sides</b>. The faction IS the entity class, which
 * vanilla syncs along with the passenger list, and {@code OWNER_UUID} is synched entity data on
 * {@link PmcUnitEntity} — so the client-side team overlay and the server-side map-marker tracker
 * ask the same question of the same data and cannot disagree. A unit hidden by its seat's
 * {@code hidePassenger} is still in the passenger list, only unrendered.
 */
public final class CrewFacts {

    public enum Faction { RU, US, PMC }

    private CrewFacts() {}

    /** The faction crewing this hull, or null if it is empty, mixed, or carrying a player. */
    @Nullable
    public static Faction factionOf(VehicleEntity hull) {
        List<Entity> passengers = hull.getPassengers();
        if (passengers.isEmpty()) return null;
        if (allAre(passengers, RUunitEntity.class)) return Faction.RU;
        if (allAre(passengers, USunitEntity.class)) return Faction.US;
        if (allAre(passengers, PmcUnitEntity.class)) return Faction.PMC;
        return null;
    }

    /**
     * The faction of one unit, for the things that have a crew but no seats — a mortar's crew
     * stands beside the tube, so there is no passenger list to be unanimous about. Deliberately a
     * different name rather than an overload: {@code VehicleEntity} is an {@code Entity}, and an
     * overload would quietly pick whichever one the call site's static type happened to be.
     */
    @Nullable
    public static Faction factionOfCrew(Entity unit) {
        if (unit instanceof RUunitEntity) return Faction.RU;
        if (unit instanceof USunitEntity) return Faction.US;
        if (unit instanceof PmcUnitEntity) return Faction.PMC;
        return null;
    }

    /**
     * The player this hull belongs to: non-null only when every passenger is a PMC unit owned by
     * one and the same player. An ownerless PMC crew (a friendly camp garrison) answers null, which
     * is correct — nobody commands it.
     */
    @Nullable
    public static UUID pmcOwner(VehicleEntity hull) {
        if (factionOf(hull) != Faction.PMC) return null;
        UUID owner = null;
        for (Entity passenger : hull.getPassengers()) {
            UUID crewOwner = ((PmcUnitEntity) passenger).getOwnerUUID();
            if (crewOwner == null) return null;
            if (owner == null) owner = crewOwner;
            else if (!owner.equals(crewOwner)) return null;
        }
        return owner;
    }

    private static boolean allAre(List<Entity> passengers, Class<?> faction) {
        for (Entity passenger : passengers) {
            if (!faction.isInstance(passenger)) return false;
        }
        return true;
    }
}
