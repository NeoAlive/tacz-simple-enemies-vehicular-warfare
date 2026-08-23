package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * When a player leaves a hull that still has their PMC aboard, move those units
 * into the empty driver / gunned seats.
 *
 * <p>SBW's {@code getFirstPassenger()} is {@code orderedPassengers[0]}, which is
 * null when seat 0 is empty even if other seats are full — so nobody would drive
 * until someone is {@code changeSeat}'d into 0. Passenger-only board orders are
 * ignored: the units are already mounted, and an empty gun is the point.
 *
 * <p>{@link PmcCommanderEntity} stays out of driver/gunner seats, same as
 * {@link CommanderSeating}. Climb seats are not destinations.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class CrewSeatPromotion {

    private CrewSeatPromotion() {}

    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.isMounting()) return;
        if (!(event.getEntityBeingMounted() instanceof VehicleEntity hull)) return;
        if (hull instanceof MortarEntity) return;
        if (!(event.getEntityMounting() instanceof Player player)) return;
        if (hull.level().getServer() == null) return;
        // Defer one tick so the player is already off orderedPassengers.
        UUID ownerId = player.getUUID();
        hull.level().getServer().execute(() -> promote(hull, ownerId));
    }

    private static void promote(VehicleEntity hull, UUID ownerId) {
        if (!hull.isAlive() || hull.isWreck()) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof Player) return;
        }

        List<PmcUnitEntity> pmcs = new ArrayList<>();
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof PmcUnitEntity pmc
                    && !(pmc instanceof PmcCommanderEntity)
                    && ownerId.equals(pmc.getOwnerUUID())) {
                pmcs.add(pmc);
            }
        }
        if (pmcs.isEmpty()) return;

        int seats = Math.max(1, hull.getMaxPassengers());
        boolean[] crew = new boolean[seats];
        for (int i = 0; i < seats; i++) {
            crew[i] = isCrewStation(hull, i);
        }

        Map<PmcUnitEntity, Integer> seatOf = new HashMap<>();
        for (PmcUnitEntity pmc : pmcs) {
            int seat = hull.getSeatIndex(pmc);
            if (seat < 0) seat = hull.getTagSeatIndex(pmc);
            seatOf.put(pmc, seat);
        }

        List<Integer> vacancies = new ArrayList<>();
        if (crew[0] && hull.getNthEntity(0) == null) vacancies.add(0);
        for (int i = 1; i < seats; i++) {
            if (crew[i] && hull.getNthEntity(i) == null) vacancies.add(i);
        }

        for (int dest : vacancies) {
            PmcUnitEntity mover = pickMover(pmcs, seatOf, crew, dest == 0);
            if (mover == null) break;
            if (hull.changeSeat(mover, dest)) {
                seatOf.put(mover, dest);
            }
        }
    }

    /**
     * Prefer a unit sitting in a passenger / Climb seat. Only steal a gunner for
     * the empty driver seat — filling a vacated gun from another gun is a no-op shuffle.
     */
    private static PmcUnitEntity pickMover(List<PmcUnitEntity> pmcs, Map<PmcUnitEntity, Integer> seatOf,
                                           boolean[] crew, boolean forDriver) {
        for (PmcUnitEntity pmc : pmcs) {
            int seat = seatOf.getOrDefault(pmc, -1);
            if (seat < 0 || seat >= crew.length || !crew[seat]) return pmc;
        }
        if (forDriver) {
            for (PmcUnitEntity pmc : pmcs) {
                if (seatOf.getOrDefault(pmc, -1) != 0) return pmc;
            }
        }
        return null;
    }

    private static boolean isCrewStation(VehicleEntity v, int seat) {
        // Driver or a weaponed station; a Climb handhold is not a destination. UNKNOWN (seat
        // past the data's end) stays unpromotable, matching the old null-info read.
        HullFacts.SeatKind kind = HullFacts.seatKind(v, seat);
        return kind == HullFacts.SeatKind.DRIVER || kind == HullFacts.SeatKind.WEAPONED;
    }
}
