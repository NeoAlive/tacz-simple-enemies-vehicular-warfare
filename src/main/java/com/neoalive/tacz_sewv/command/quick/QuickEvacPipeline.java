package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.BoardOrders;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Quick Evac: every non-full owned PMC heli in range lands toward nearby infantry; on-foot PMCs
 * are split across free seats up front; board orders are issued only after a pad succeeds; each
 * heli takes off once its reserved seats are filled or the timeout elapses.
 *
 * <p>Full hulls are ignored. Tracker re-resolves by network id each poll (stale-safe).
 */
public final class QuickEvacPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        List<ResolvedHeli> helis = findEligibleHelis(issuer, level);
        if (helis.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_evac.no_heli", 0,
                    ChatFormatting.RED);
            return;
        }

        List<PmcUnitEntity> units = resolveBoarders(issuer, level, context.unitIds());
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_evac.no_units", 0,
                    ChatFormatting.RED);
            return;
        }

        // Preemptive seat split: nearest heli with remaining free seats wins each unit.
        Map<Integer, Integer> remaining = new HashMap<>();
        for (ResolvedHeli h : helis) {
            remaining.put(h.hull().getId(), freeSeats(h.hull()));
        }
        Map<Integer, List<Integer>> reserved = new HashMap<>();
        for (ResolvedHeli h : helis) {
            reserved.put(h.hull().getId(), new ArrayList<>());
        }

        List<PmcUnitEntity> sorted = new ArrayList<>(units);
        // Stable-ish: closer units to *any* heli first so local clusters fill nearby airframes.
        sorted.sort(Comparator.comparingDouble(u -> nearestHeliDistSq(u, helis)));

        for (PmcUnitEntity pmc : sorted) {
            ResolvedHeli best = null;
            double bestDist = Double.MAX_VALUE;
            for (ResolvedHeli h : helis) {
                int left = remaining.getOrDefault(h.hull().getId(), 0);
                if (left <= 0) continue;
                double d = pmc.distanceToSqr(h.hull());
                if (d < bestDist) {
                    bestDist = d;
                    best = h;
                }
            }
            if (best == null) break;
            int hid = best.hull().getId();
            reserved.get(hid).add(pmc.getId());
            remaining.put(hid, remaining.get(hid) - 1);
        }

        // Land first — only then issue board orders for legs that actually got a pad.
        Set<Long> claimedPads = new HashSet<>();
        List<QuickEvacTracker.LegSpec> legs = new ArrayList<>();
        int boarded = 0;
        for (ResolvedHeli heli : helis) {
            List<Integer> assignees = reserved.getOrDefault(heli.hull().getId(), List.of());
            BlockPos focus = landFocus(heli, assignees, units, level);
            if (!FlightOrders.landNear(heli.pilot(), heli.hull(), focus, claimedPads)) {
                OrderReport.fail(issuer, OrderFailure.NO_PAD, heli.pilot());
                continue;
            }
            for (int unitId : assignees) {
                Entity e = level.getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
                if (pmc.getVehicle() != null) continue;
                BoardOrders.issueCleared(pmc, heli.hull().getId(), true);
                boarded++;
            }
            legs.add(new QuickEvacTracker.LegSpec(heli.hull().getId(), heli.pilot().getId(),
                    List.copyOf(assignees)));
        }
        if (legs.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_evac.no_heli", 0,
                    ChatFormatting.RED);
            return;
        }

        QuickEvacTracker.start(issuer.getUUID(), legs, level.getGameTime());
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_evac.started",
                Math.max(boarded, units.size()),
                ChatFormatting.GREEN, Math.max(boarded, units.size()));
    }

    /** Owned PMC-crewed helicopters in range that still have at least one free seat. */
    static List<ResolvedHeli> findEligibleHelis(ServerPlayer issuer, ServerLevel level) {
        double radius = SewvConfig.QUICK_EVAC_HELI_SEARCH_RADIUS.get();
        double r2 = radius * radius;
        AABB box = issuer.getBoundingBox().inflate(radius);
        List<ResolvedHeli> out = new ArrayList<>();
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box,
                v -> v.isAlive() && HullFacts.isHelicopterHull(v))) {
            if (hull.distanceToSqr(issuer) > r2) continue;
            if (isFull(hull)) continue;
            if (!(hull.getFirstPassenger() instanceof PmcUnitEntity pilot)) continue;
            if (!OrderAuth.check(issuer, pilot, "QuickEvacHeli")) continue;
            if (!(pilot instanceof IHelicopterPilot)) continue;
            out.add(new ResolvedHeli(hull, pilot));
        }
        return out;
    }

    static boolean isFull(VehicleEntity hull) {
        return freeSeats(hull) <= 0;
    }

    static int freeSeats(VehicleEntity hull) {
        return Math.max(0, hull.getMaxPassengers() - hull.getPassengers().size());
    }

    private static double nearestHeliDistSq(PmcUnitEntity unit, List<ResolvedHeli> helis) {
        double best = Double.MAX_VALUE;
        for (ResolvedHeli h : helis) {
            best = Math.min(best, unit.distanceToSqr(h.hull()));
        }
        return best;
    }

    /** Pad focus: centroid of assignees, else nearest on-foot PMC, else the heli itself. */
    private static BlockPos landFocus(ResolvedHeli heli, List<Integer> assignees,
                                      List<PmcUnitEntity> allUnits, ServerLevel level) {
        if (!assignees.isEmpty()) {
            double sx = 0, sz = 0;
            int n = 0;
            for (int id : assignees) {
                Entity e = level.getEntity(id);
                if (e == null) continue;
                sx += e.getX();
                sz += e.getZ();
                n++;
            }
            if (n > 0) {
                return BlockPos.containing(sx / n, heli.hull().getY(), sz / n);
            }
        }
        PmcUnitEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PmcUnitEntity u : allUnits) {
            double d = u.distanceToSqr(heli.hull());
            if (d < best) {
                best = d;
                nearest = u;
            }
        }
        if (nearest != null) return nearest.blockPosition();
        return heli.hull().blockPosition();
    }

    private static List<PmcUnitEntity> resolveBoarders(ServerPlayer issuer, ServerLevel level,
                                                       List<Integer> unitIds) {
        double radius = SewvConfig.QUICK_EVAC_BOARD_RADIUS.get();
        List<PmcUnitEntity> out = new ArrayList<>();
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, unitIds, radius)) {
            if (pmc.getVehicle() != null) continue;
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (MortarSupport.hasMortarClaim(pmc)) continue;
            if (QuickCommandUnits.refuseFobOrRoute(issuer, pmc)) continue;
            out.add(pmc);
        }
        return out;
    }

    record ResolvedHeli(VehicleEntity hull, PmcUnitEntity pilot) {}
}
