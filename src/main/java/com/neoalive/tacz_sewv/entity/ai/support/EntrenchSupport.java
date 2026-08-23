package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
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
import com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal;

/**
 * ENTRENCHED assignment: emplacements first (mortar claim / TOW board), sandbag seats, then
 * Fisher-Yates trench cells. Reroll when a cell or emplacement slot is invalidated.
 *
 * <p>Sandbag seating is cleared only via {@link #clear} (TDT / map <b>Dismiss</b>, auto-leave) —
 * not via vehicle dismount. A dismounted entrenched unit is walked back and remounted.
 */
public final class EntrenchSupport {

    private static final int REROLL_INTERVAL_TICKS = 20;
    /** Close enough to the cell centre to promote to HOLD / mount a sandbag. */
    private static final double ARRIVE_DIST_SQ = 2.25;
    /** How far from a clicked sandbag to draw additional free bags for a multi-unit order. */
    private static final double SANDBAG_CLUSTER_RADIUS = 16.0;

    /** RU/US auto-entrench dwell before leaving (game ticks). */
    public static final int AUTO_STAY_MIN_TICKS = 30 * 20;
    public static final int AUTO_STAY_MAX_TICKS = 120 * 20;
    /**
     * After leaving, how long before {@code SeekEntrenchmentGoal} may re-assign (game ticks).
     * Long enough that a nearby network cannot bounce the same unit in a leave/seek loop.
     */
    public static final int AUTO_SEEK_COOLDOWN_TICKS = 60 * 20;

    private EntrenchSupport() {}

    public static void clear(AbstractUnit unit) {
        if (!(unit instanceof IEntrenched entrenched)) return;
        if (!entrenched.sewv$isEntrenched()) return;
        boolean leavingSandbag = wasSandbagTask(unit, entrenched);
        BlockPos cell = entrenched.sewv$getEntrenchCell();
        if (leavingSandbag && cell != null && unit.level() instanceof ServerLevel level) {
            SandbagSupport.clearClaimantIf(level, cell, unit);
        }
        MortarSupport.releaseClaim(unit);
        if (unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) {
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
        }
        SandbagSupport.dismountIfSeated(unit);
        entrenched.sewv$clearEntrenched();
        if (leavingSandbag) {
            // Stolen seat / dismiss / auto-leave — same seek gate so RU/US do not re-claim mid-scramble.
            armSeekCooldown(unit);
            BailOutVehicleGoal.requestSandbagScramble(unit);
        }
    }

    /** RU/US only — PMC has no SeekEntrenchmentGoal. */
    private static void armSeekCooldown(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity) return;
        if (!(unit instanceof IEntrenched entrenched)) return;
        if (!(unit.level() instanceof ServerLevel level)) return;
        entrenched.sewv$setEntrenchSeekCooldownUntil(level.getGameTime() + AUTO_SEEK_COOLDOWN_TICKS);
    }

    private static boolean wasSandbagTask(AbstractUnit unit, IEntrenched entrenched) {
        if (SandbagSupport.isRidingSandbag(unit)) return true;
        BlockPos cell = entrenched.sewv$getEntrenchCell();
        return cell != null && unit.level() instanceof ServerLevel level
                && SandbagSupport.isSandbag(level, cell);
    }

    /**
     * Assign selected units into the network / sandbag at {@code hitPos}. Emplacement weapons
     * take units first; sandbags take the next free seats; leftovers draw trench cells.
     *
     * @return how many units accepted the order
     */
    public static int assign(ServerLevel level, List<AbstractUnit> units, BlockPos hitPos) {
        BlockPos sandbagHit = SandbagSupport.resolveHit(level, hitPos);
        if (sandbagHit != null) {
            return assignSandbags(level, units, sandbagHit);
        }

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

        int accepted = 0;
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
            } else if (weapon instanceof Type63Entity type63) {
                if (Type63Support.isClaimed(type63, crew)) {
                    remaining.add(0, crew);
                    continue;
                }
                Type63Support.claim(crew, type63);
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
            accepted++;
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
                accepted++;
            }
        } else {
            // Standalone emplacement network with no trench cells — leftover units get the emp pad.
            BlockPos pad = index;
            for (AbstractUnit unit : remaining) {
                clearConflicting(unit);
                ((IEntrenched) unit).sewv$setEntrenched(network.seed(), pad, null);
                afterAssignStance(unit, pad);
                accepted++;
            }
        }
        return accepted;
    }

    /**
     * Seat units on free sandbags around {@code primary}. One bag per unit; extras with no
     * free bag nearby are skipped.
     */
    public static int assignSandbags(ServerLevel level, List<AbstractUnit> units, BlockPos primary) {
        if (!SandbagSupport.isSandbag(level, primary)) return 0;
        List<BlockPos> bags = new ArrayList<>();
        bags.add(primary.immutable());
        // Gather other free bags near the click for multi-unit orders.
        int r = (int) Math.ceil(SANDBAG_CLUSTER_RADIUS);
        for (BlockPos p : BlockPos.betweenClosed(
                primary.getX() - r, primary.getY() - 2, primary.getZ() - r,
                primary.getX() + r, primary.getY() + 2, primary.getZ() + r)) {
            if (p.equals(primary)) continue;
            if (!SandbagSupport.isSandbag(level, p)) continue;
            if (!SandbagSupport.isSeatAvailable(level, p, null)) continue;
            bags.add(p.immutable());
        }
        Collections.shuffle(bags.subList(1, bags.size()), ThreadLocalRandom.current());

        int accepted = 0;
        int bagIdx = 0;
        for (AbstractUnit unit : units) {
            BlockPos bag = null;
            while (bagIdx < bags.size()) {
                BlockPos candidate = bags.get(bagIdx++);
                if (SandbagSupport.isSeatAvailable(level, candidate, unit)) {
                    bag = candidate;
                    break;
                }
            }
            if (bag == null) break;
            clearConflicting(unit);
            // Seed = bag pos — standalone "network" of one, same shape as a lone emplacement.
            ((IEntrenched) unit).sewv$setEntrenched(bag.asLong(), bag, null);
            SandbagSupport.setClaimant(level, bag, unit);
            afterAssignStance(unit, bag);
            accepted++;
        }
        return accepted;
    }

    private static void clearConflicting(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity pmc) {
            PatrolSupport.clear(pmc);
            GuardSupport.clearReach(pmc);
        }
        // Drop prior entrench / mortar / board without full clear recursion on the new assign.
        if (unit instanceof IEntrenched entrenched && entrenched.sewv$isEntrenched()) {
            BlockPos oldCell = entrenched.sewv$getEntrenchCell();
            if (oldCell != null && unit.level() instanceof ServerLevel level) {
                SandbagSupport.clearClaimantIf(level, oldCell, unit);
            }
            MortarSupport.releaseClaim(unit);
            if (unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) {
                boarder.tacz_sewv$setBoarding(false);
                boarder.tacz_sewv$setMountTargetId(-1);
            }
            SandbagSupport.dismountIfSeated(unit);
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
     * Validate / reroll / promote HOLD / mount sandbags. Called from
     * {@link com.neoalive.tacz_sewv.entity.ai.goal.EntrenchGoal}.
     */
    public static void tick(AbstractUnit unit) {
        if (!(unit instanceof IEntrenched entrenched) || !entrenched.sewv$isEntrenched()) return;
        if (!(unit.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        if (now < entrenched.sewv$getEntrenchRerollAt()) return;
        entrenched.sewv$setEntrenchRerollAt(now + REROLL_INTERVAL_TICKS);

        // RU/US auto-dwell expired — leave and arm seek cooldown (PMC player orders have leaveAt=0).
        long leaveAt = entrenched.sewv$getEntrenchLeaveAt();
        if (leaveAt > 0L && now >= leaveAt) {
            leaveAutoEntrench(unit);
            return;
        }

        BlockPos cell = entrenched.sewv$getEntrenchCell();
        if (cell == null) {
            clear(unit);
            return;
        }

        // Sandbag cell: walk in, mount, hold. Invalid / stolen bag → clear (or remount self).
        if (SandbagSupport.isSandbag(level, cell)) {
            tickSandbag(level, unit, entrenched, cell);
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

    private static void tickSandbag(ServerLevel level, AbstractUnit unit, IEntrenched entrenched,
                                    BlockPos cell) {
        // Single seat: if someone else is on it or already claimed it, drop the task —
        // do not walk to a second bag (that would be a new seek, not this order).
        if (!SandbagSupport.isSeatAvailable(level, cell, unit)) {
            clear(unit);
            return;
        }

        if (SandbagSupport.isRidingThis(unit, cell)) {
            if (unit instanceof PmcUnitEntity pmc && pmc.getOrder() != OrderType.HOLD_POSITION) {
                pmc.setOrder(OrderType.HOLD_POSITION);
            }
            unit.getNavigation().stop();
            return;
        }

        // Off the seat (dismount key / knockback) — walk back and remount. Dismiss is the only
        // stand-down that clears the task.
        double distSq = unit.distanceToSqr(Vec3.atBottomCenterOf(cell));
        if (distSq <= ARRIVE_DIST_SQ) {
            unit.getNavigation().stop();
            if (!SandbagSupport.tryMount(level, cell, unit)) {
                // Lost the race on the single seat — abandon.
                clear(unit);
                return;
            }
            if (unit instanceof PmcUnitEntity pmc && SandbagSupport.isRidingThis(unit, cell)
                    && pmc.getOrder() != OrderType.HOLD_POSITION) {
                pmc.setOrder(OrderType.HOLD_POSITION);
            }
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
        if (weapon instanceof Type63Entity type63) {
            AbstractUnit crew = Type63Support.crewOf(type63, null);
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

    /** Schedule a random dwell for an RU/US unit that just auto-entrenched. */
    public static void scheduleAutoLeave(AbstractUnit unit, long gameTime) {
        if (!(unit instanceof IEntrenched entrenched)) return;
        int span = AUTO_STAY_MAX_TICKS - AUTO_STAY_MIN_TICKS + 1;
        int stay = AUTO_STAY_MIN_TICKS + ThreadLocalRandom.current().nextInt(span);
        entrenched.sewv$setEntrenchLeaveAt(gameTime + stay);
    }

    private static void leaveAutoEntrench(AbstractUnit unit) {
        // Trench/emplacement auto-leave; sandbag clear() arms CD again (same helper, idempotent).
        armSeekCooldown(unit);
        clear(unit);
    }
}
