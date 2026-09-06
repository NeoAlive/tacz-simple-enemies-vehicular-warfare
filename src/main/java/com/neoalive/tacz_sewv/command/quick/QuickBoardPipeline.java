package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.support.BoardOrders;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.ai.support.Type63Support;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Quick Board: radius PMCs split across free seats / unclaimed mortars & Type63s and walk to mount.
 * Enemy (RU/US) hulls are ignored; other players' owned PMC crews are ignored.
 */
public final class QuickBoardPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        List<PmcUnitEntity> units = QuickCommandUnits.onFootOwned(issuer, level, context.unitIds());
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_board.no_units", 0,
                    ChatFormatting.RED);
            return;
        }

        List<BoardSlot> slots = collectSlots(issuer, level);
        if (slots.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_board.no_vehicle", 0,
                    ChatFormatting.RED);
            return;
        }

        Map<BoardSlot, Integer> remaining = new HashMap<>();
        for (BoardSlot slot : slots) {
            remaining.put(slot, slot.capacity());
        }

        units.sort(Comparator.comparingDouble(u -> nearestSlotDistSq(u, slots)));
        int ordered = 0;
        for (PmcUnitEntity pmc : units) {
            BoardSlot best = null;
            double bestDist = Double.MAX_VALUE;
            for (BoardSlot slot : slots) {
                if (remaining.getOrDefault(slot, 0) <= 0) continue;
                double d = pmc.distanceToSqr(slot.x(), slot.y(), slot.z());
                if (d < bestDist) {
                    bestDist = d;
                    best = slot;
                }
            }
            if (best == null) break;
            if (!best.assign(pmc)) continue;
            remaining.put(best, remaining.get(best) - 1);
            ordered++;
        }

        if (ordered == 0) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_board.no_vehicle", 0,
                    ChatFormatting.RED);
            return;
        }
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_board.started", ordered,
                ChatFormatting.GREEN, ordered);
    }

    private static List<BoardSlot> collectSlots(ServerPlayer issuer, ServerLevel level) {
        double radius = SewvConfig.QUICK_LAND_RADIUS.get();
        double r2 = radius * radius;
        AABB box = issuer.getBoundingBox().inflate(radius);
        List<BoardSlot> out = new ArrayList<>();

        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box,
                v -> v.isAlive() && !v.isWreck())) {
            if (hull.distanceToSqr(issuer) > r2) continue;
            if (hull instanceof MortarEntity mortar) {
                if (MortarSupport.isMortarClaimed(mortar, null)) continue;
                out.add(BoardSlot.mortar(mortar));
                continue;
            }
            if (hull instanceof Type63Entity type63) {
                if (Type63Support.isClaimed(type63, null)) continue;
                out.add(BoardSlot.type63(type63));
                continue;
            }
            if (isEnemyCrew(hull)) continue;
            if (isForeignPmc(hull, issuer)) continue;
            int free = freeSeats(hull);
            if (free <= 0) continue;
            boolean empty = hull.getPassengers().isEmpty();
            // Same player-hull lock as RU/US auto-board: lastDriver Player means off-limits unless
            // the steal toggle is on (issuer's own lastDriver still counts as "player-driven").
            if (empty && !SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES.get()
                    && hull.getLastDriver() instanceof Player) {
                continue;
            }
            out.add(BoardSlot.vehicle(hull, free, empty));
        }
        return out;
    }

    private static boolean isEnemyCrew(VehicleEntity hull) {
        for (Entity p : hull.getPassengers()) {
            if (p instanceof RUunitEntity || p instanceof USunitEntity) return true;
        }
        CrewFacts.Faction f = CrewFacts.factionOf(hull);
        return f == CrewFacts.Faction.RU || f == CrewFacts.Faction.US;
    }

    private static boolean isForeignPmc(VehicleEntity hull, Player issuer) {
        java.util.UUID owner = CrewFacts.pmcOwner(hull);
        return owner != null && !owner.equals(issuer.getUUID());
    }

    private static int freeSeats(VehicleEntity hull) {
        return Math.max(0, hull.getMaxPassengers() - hull.getPassengers().size());
    }

    private static double nearestSlotDistSq(PmcUnitEntity unit, List<BoardSlot> slots) {
        double best = Double.MAX_VALUE;
        for (BoardSlot s : slots) {
            best = Math.min(best, unit.distanceToSqr(s.x(), s.y(), s.z()));
        }
        return best;
    }

    private sealed interface BoardSlot {
        double x();
        double y();
        double z();
        int capacity();
        boolean assign(PmcUnitEntity pmc);

        static BoardSlot vehicle(VehicleEntity hull, int free, boolean empty) {
            return new VehicleSlot(hull, free, empty);
        }

        static BoardSlot mortar(MortarEntity mortar) {
            return new MortarSlot(mortar);
        }

        static BoardSlot type63(Type63Entity launcher) {
            return new Type63Slot(launcher);
        }
    }

    private static final class VehicleSlot implements BoardSlot {
        private final VehicleEntity hull;
        private final int capacity;
        private boolean empty;

        VehicleSlot(VehicleEntity hull, int capacity, boolean empty) {
            this.hull = hull;
            this.capacity = capacity;
            this.empty = empty;
        }

        @Override
        public double x() { return hull.getX(); }
        @Override
        public double y() { return hull.getY(); }
        @Override
        public double z() { return hull.getZ(); }
        @Override
        public int capacity() { return capacity; }

        @Override
        public boolean assign(PmcUnitEntity pmc) {
            if (empty) {
                BoardOrders.issue(pmc, hull.getId(), false);
                empty = false;
            } else {
                BoardOrders.issueCleared(pmc, hull.getId(), true);
            }
            return true;
        }
    }

    private record MortarSlot(MortarEntity mortar) implements BoardSlot {
        @Override
        public double x() { return mortar.getX(); }
        @Override
        public double y() { return mortar.getY(); }
        @Override
        public double z() { return mortar.getZ(); }
        @Override
        public int capacity() { return 1; }

        @Override
        public boolean assign(PmcUnitEntity pmc) {
            if (MortarSupport.isMortarClaimed(mortar, pmc)) return false;
            MortarSupport.claim(pmc, mortar);
            clearConflictingOrders(pmc);
            return true;
        }
    }

    private record Type63Slot(Type63Entity launcher) implements BoardSlot {
        @Override
        public double x() { return launcher.getX(); }
        @Override
        public double y() { return launcher.getY(); }
        @Override
        public double z() { return launcher.getZ(); }
        @Override
        public int capacity() { return 1; }

        @Override
        public boolean assign(PmcUnitEntity pmc) {
            if (Type63Support.isClaimed(launcher, pmc)) return false;
            Type63Support.claim(pmc, launcher);
            clearConflictingOrders(pmc);
            return true;
        }
    }

    private static void clearConflictingOrders(PmcUnitEntity pmc) {
        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(pmc);
    }
}
