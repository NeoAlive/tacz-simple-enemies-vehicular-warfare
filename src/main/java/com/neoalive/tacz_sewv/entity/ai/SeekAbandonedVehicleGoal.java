package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Lets RU/US infantry claim an abandoned vehicle it walks past, so a hull whose crew bailed out
 * — or one a player parked and wandered off from — gets crewed again instead of sitting there
 * as scenery for the rest of the world's life.
 *
 * <p>This goal <b>issues an order and nothing else</b>. It writes the same three
 * {@link IVehicleBoarder} fields {@code PacketBoardVehicle} writes for a player-commanded PMC,
 * and {@link BoardVehicleGoal} — which never asks who set them — does the walking and mounting.
 * That is the whole reason scavenging is this small: the execution half already existed and was
 * only ever gated behind having no autonomous source of orders.
 *
 * <p>It claims <b>no flags</b> and always reports {@code canUse() == false}: it does its work
 * inside the evaluation and then declines to run, so it can never contend for MOVE or LOOK with
 * the goal it just handed the job to.
 *
 * <p>PMC units are deliberately excluded. They have an owner who decides what they board, and a
 * PMC that wandered into the nearest empty hull on its own initiative would be fighting its
 * player for control of the squad.
 */
public class SeekAbandonedVehicleGoal extends Goal {

    /**
     * Goal ticks between scans. The AABB search is the entire cost of this feature and it runs
     * on every idle unit on the map, so it is throttled hard — a hull that has sat abandoned for
     * an hour can wait another two seconds.
     */
    private static final int SCAN_INTERVAL = 40;

    private final AbstractUnit unit;
    private int scanCooldown;

    public SeekAbandonedVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    /**
     * Always false — see the class doc. The scan happens here because {@code canUse} is the only
     * thing the goal selector calls on a goal that never runs, and issuing an order is not
     * "running" in any sense the selector needs to know about.
     */
    @Override
    public boolean canUse() {
        if (!shouldScan()) return false;
        if (this.scanCooldown-- > 0) return false;
        this.scanCooldown = SCAN_INTERVAL;

        VehicleEntity hull = findAbandonedVehicle();
        if (hull != null) {
            IVehicleBoarder boarder = (IVehicleBoarder) this.unit;
            boarder.tacz_sewv$setMountTargetId(hull.getId());
            // Not passenger-only: taking the wheel is the entire point of scavenging a hull that
            // has nobody in it. (That flag exists for a player telling a unit to ride along.)
            boarder.tacz_sewv$setPassengerOnly(false);
            boarder.tacz_sewv$setBoarding(true);
        }
        return false;
    }

    private boolean shouldScan() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.AUTO_BOARD_ENABLED.get()) return false;
        // RU/US only. A PMC takes its orders from its owner.
        if (!(this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity)) return false;
        if (this.unit.isPassenger()) return false;
        // Never break off a fight to go looking for a ride. This is also what keeps the goal
        // from competing with SEM's combat goals — an engaged unit simply never scans.
        if (this.unit.getTarget() != null) return false;
        // An order already stands; let BoardVehicleGoal finish (or time out) before re-scanning.
        return !((IVehicleBoarder) this.unit).tacz_sewv$isBoarding();
    }

    /** The nearest hull worth taking, or null. */
    private VehicleEntity findAbandonedVehicle() {
        double radius = SewvConfig.AUTO_BOARD_SCAN_RADIUS.get();
        List<VehicleEntity> candidates = this.unit.level().getEntitiesOfClass(
                VehicleEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                this::isAbandoned);

        return candidates.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    private boolean isAbandoned(VehicleEntity hull) {
        if (!hull.isAlive() || hull.isWreck()) return false;

        // A mortar is a VehicleEntity with NO seats — nothing can mount it, and a crew works it
        // standing beside it (ManMortarGoal). Boarding one is not a thing that can happen, so
        // filter it here rather than letting BoardVehicleGoal walk over and fail.
        if (hull instanceof MortarEntity) return false;
        if (hull.getMaxPassengers() <= 0) return false;

        // COMPLETELY empty. Not "has a free seat" — a hull with anyone still in it is somebody's,
        // and this is scavenging, not reinforcement. It also happens to be what stops an IFV's
        // dismount squad from climbing straight back into the hull that just put them out: that
        // one still holds its driver and gunner. See DriveVehicleGoal.dismountSquad, where the
        // absence of a recall is deliberate.
        if (!hull.getPassengers().isEmpty()) return false;

        float max = hull.getMaxHealth();
        if (max > 0.0F
                && hull.getHealth() < max * SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION.get().floatValue()) {
            return false; // a burning hull a crew would only bail straight back out of
        }

        // SuperbWarfare has no owner field on a vehicle at all — the last driver is the only
        // ownership signal that exists — so this is the only way to say "leave the player's
        // tank alone". Off by default, which means a hull you have driven even once is
        // permanently off limits; that is the safer failure and the config says so.
        if (!SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES.get()) {
            LivingEntity lastDriver = lastDriverOf(hull);
            if (lastDriver instanceof Player) return false;
        }
        return true;
    }

    /** SBW resolves the last driver by UUID against the level, and answers null if it is gone. */
    private static LivingEntity lastDriverOf(VehicleEntity hull) {
        try {
            return hull.getLastDriver() instanceof LivingEntity living ? living : null;
        } catch (Exception e) {
            return null; // unreadable: treat as unowned rather than crashing the scan
        }
    }
}
