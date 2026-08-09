package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.EmplacementSupport;
import com.neoalive.tacz_sewv.block.TrenchNetworks;
import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;

/**
 * ENTRENCHED assignment: emplacements first (mortar claim / TOW board), then Fisher-Yates
 * trench cells. Reroll when a cell or emplacement slot is invalidated.
 */
public final class EntrenchSupport {

    private static final int REROLL_INTERVAL_TICKS = 20;
    /** Close enough to the cell centre to promote to HOLD. */
    private static final double ARRIVE_DIST_SQ = 2.25;

    private EntrenchSupport() {}

    public static void clear(AbstractUnit unit) {
        if (!(unit instanceof IEntrenched entrenched)) return;
        if (!entrenched.sewv$isEntrenched()) return;
        MortarSupport.releaseClaim(unit);
        if (unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) {
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
        }
        entrenched.sewv$clearEntrenched();
    }

    /**
     * Assign selected units into the network containing {@code hitPos}. Emplacement weapons
     * take units first; leftovers draw trench cells (wrap if more units than cells).
     *
     * @return how many units accepted the order
     */
    public static int assign(ServerLevel level, List<AbstractUnit> units, BlockPos hitPos) {
        BlockPos index = TrenchNetworks.indexPos(hitPos, level.getBlockState(hitPos));
        TrenchNetworks data = TrenchNetworks.get(level);
        TrenchNetworks.NetworkDetail network = data.networkContaining(index);
        if (network == null) {
            network = data.findNearbyNetwork(hitPos, 8);
        }
        if (network == null) return 0;

        List<AbstractUnit> remaining = new ArrayList<>(units);
        List<BlockPos> empSlots = new ArrayList<>();
        for (long emp : network.emplacements()) {
            empSlots.add(BlockPos.of(emp));
        }
        Collections.shuffle(empSlots, ThreadLocalRandom.current());

        for (BlockPos empPos : empSlots) {
            if (remaining.isEmpty()) break;
            VehicleEntity weapon = EmplacementSupport.findWeaponAbove(level, empPos);
            if (weapon == null || !weapon.isAlive() || weapon.isWreck()) continue;

            AbstractUnit crew = remaining.remove(0);
            clearConflicting(crew);
            if (weapon instanceof MortarEntity mortar) {
                if (MortarSupport.isMortarClaimed(mortar, crew)) {
                    remaining.add(0, crew);
                    continue;
                }
                MortarSupport.claim(crew, mortar);
                ((IEntrenched) crew).sewv$setEntrenched(network.seed(), empPos, empPos);
            } else if (weapon instanceof TowEntity || isFixedMount(weapon)) {
                if (!weapon.getPassengers().isEmpty()) {
                    remaining.add(0, crew);
                    continue;
                }
                IVehicleBoarder boarder = (IVehicleBoarder) crew;
                boarder.tacz_sewv$setMountTargetId(weapon.getId());
                boarder.tacz_sewv$setPassengerOnly(false);
                boarder.tacz_sewv$setBoarding(true);
                ((IEntrenched) crew).sewv$setEntrenched(network.seed(), empPos, empPos);
            } else {
                remaining.add(0, crew);
                continue;
            }
            afterAssignStance(crew, empPos);
        }

        List<BlockPos> cells = new ArrayList<>(network.cells().size());
        for (int i = 0; i < network.cells().size(); i++) {
            cells.add(BlockPos.of(network.cells().getLong(i)));
        }
        if (!cells.isEmpty()) {
            Collections.shuffle(cells, ThreadLocalRandom.current());
            int draw = 0;
            for (AbstractUnit unit : remaining) {
                BlockPos cell = cells.get(draw % cells.size());
                draw++;
                clearConflicting(unit);
                ((IEntrenched) unit).sewv$setEntrenched(network.seed(), cell, null);
                afterAssignStance(unit, cell);
            }
        } else {
            // Standalone emplacement network with no trench cells — leftover units get the emp pad.
            BlockPos pad = index;
            for (AbstractUnit unit : remaining) {
                clearConflicting(unit);
                ((IEntrenched) unit).sewv$setEntrenched(network.seed(), pad, null);
                afterAssignStance(unit, pad);
            }
        }
        return units.size();
    }

    private static void clearConflicting(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity pmc) {
            PatrolSupport.clear(pmc);
            GuardSupport.clearReach(pmc);
        }
        // Drop prior entrench / mortar / board without full clear recursion on the new assign.
        if (unit instanceof IEntrenched entrenched && entrenched.sewv$isEntrenched()) {
            MortarSupport.releaseClaim(unit);
            if (unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) {
                boarder.tacz_sewv$setBoarding(false);
                boarder.tacz_sewv$setMountTargetId(-1);
            }
            entrenched.sewv$clearEntrenched();
        }
    }

    private static void afterAssignStance(AbstractUnit unit, BlockPos cell) {
        if (unit instanceof PmcUnitEntity pmc) {
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(Vec3.atBottomCenterOf(cell));
        }
    }

    private static boolean isFixedMount(VehicleEntity weapon) {
        try {
            return weapon.computed().getEngineType()
                    == com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType.FIXED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Destination for mounted crews under ENTRENCHED, or null. */
    @Nullable
    public static BlockPos currentCell(AbstractUnit unit) {
        if (!(unit instanceof IEntrenched entrenched) || !entrenched.sewv$isEntrenched()) {
            return null;
        }
        return entrenched.sewv$getEntrenchCell();
    }

    /**
     * Validate / reroll / promote HOLD. Called from {@link com.neoalive.tacz_sewv.entity.ai.goal.EntrenchGoal}.
     */
    public static void tick(AbstractUnit unit) {
        if (!(unit instanceof IEntrenched entrenched) || !entrenched.sewv$isEntrenched()) return;
        if (!(unit.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        if (now < entrenched.sewv$getEntrenchRerollAt()) return;
        entrenched.sewv$setEntrenchRerollAt(now + REROLL_INTERVAL_TICKS);

        BlockPos cell = entrenched.sewv$getEntrenchCell();
        if (cell == null) {
            clear(unit);
            return;
        }

        TrenchNetworks data = TrenchNetworks.get(level);
        BlockPos emp = entrenched.sewv$getEntrenchEmplacement();
        if (emp != null && !emplacementSlotValid(level, data, unit, emp)) {
            entrenched.sewv$clearEntrenchEmplacement();
            MortarSupport.releaseClaim(unit);
            if (unit instanceof IVehicleBoarder boarder) {
                boarder.tacz_sewv$setBoarding(false);
                boarder.tacz_sewv$setMountTargetId(-1);
            }
            rerollCell(level, data, unit, entrenched);
            return;
        }

        if (emp == null && !cellValid(level, data, cell)) {
            rerollCell(level, data, unit, entrenched);
            return;
        }

        // Emplacement crews: boarding / mortar goals own locomotion.
        if (emp != null) return;
        if (unit instanceof IMortarCrew mortarCrew
                && mortarCrew.sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR) {
            return;
        }
        if (unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) {
            return;
        }
        if (unit.isPassenger()) return;

        cell = entrenched.sewv$getEntrenchCell();
        if (cell == null) return;
        double distSq = unit.distanceToSqr(Vec3.atBottomCenterOf(cell));
        if (distSq <= ARRIVE_DIST_SQ) {
            if (unit instanceof PmcUnitEntity pmc && pmc.getOrder() != OrderType.HOLD_POSITION) {
                pmc.setOrder(OrderType.HOLD_POSITION);
            }
            unit.getNavigation().stop();
        } else if (unit instanceof PmcUnitEntity pmc) {
            if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) {
                pmc.setOrder(OrderType.MOVE_TO_POSITION);
            }
            pmc.setMoveToTarget(Vec3.atBottomCenterOf(cell));
        } else {
            unit.getNavigation().moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 1.0);
        }
    }

    private static boolean cellValid(ServerLevel level, TrenchNetworks data, BlockPos cell) {
        if (!data.cells().contains(cell.asLong())) return false;
        return TrenchNetworks.isTrackedCell(level.getBlockState(cell));
    }

    private static boolean emplacementSlotValid(ServerLevel level, TrenchNetworks data,
                                               AbstractUnit unit, BlockPos emp) {
        if (!data.emplacements().contains(emp.asLong())) return false;
        if (!(level.getBlockState(emp).getBlock() instanceof com.neoalive.tacz_sewv.block.EmplacementBlock)) {
            return false;
        }
        VehicleEntity weapon = EmplacementSupport.findWeaponAbove(level, emp);
        if (weapon == null || !weapon.isAlive() || weapon.isWreck()) return false;
        if (weapon instanceof MortarEntity mortar) {
            AbstractUnit crew = MortarSupport.crewOf(mortar, null);
            return crew == null || crew == unit;
        }
        if (!weapon.getPassengers().isEmpty()) {
            return weapon.getPassengers().contains(unit);
        }
        return true;
    }

    private static void rerollCell(ServerLevel level, TrenchNetworks data,
                                   AbstractUnit unit, IEntrenched entrenched) {
        TrenchNetworks.NetworkDetail network = data.networkBySeed(entrenched.sewv$getEntrenchNetworkSeed());
        if (network == null || network.cells().isEmpty()) {
            // Try recover from current cell neighbourhood.
            BlockPos old = entrenched.sewv$getEntrenchCell();
            if (old != null) {
                network = data.networkContaining(old);
            }
        }
        if (network == null || network.cells().isEmpty()) {
            clear(unit);
            return;
        }
        int idx = ThreadLocalRandom.current().nextInt(network.cells().size());
        BlockPos next = BlockPos.of(network.cells().getLong(idx));
        entrenched.sewv$setEntrenched(network.seed(), next, null);
        afterAssignStance(unit, next);
    }

    public static boolean isEntrenched(Entity entity) {
        return entity instanceof IEntrenched e && e.sewv$isEntrenched();
    }
}
