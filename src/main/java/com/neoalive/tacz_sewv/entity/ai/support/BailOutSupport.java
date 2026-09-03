package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal;
import com.neoalive.tacz_sewv.order.OrderGuard;

/**
 * Shared vehicle bail execution for auto-bail ({@link BailOutVehicleGoal}) and player-triggered
 * bail ({@link com.neoalive.tacz_sewv.network.PacketBailOutVehicle}).
 */
public final class BailOutSupport {

    /** Player-requested bail — {@link BailOutVehicleGoal} picks this up on the next tick. */
    public static final String TAG_MANUAL_BAIL = "sewv:manual_bail";

    private static final String PARACHUTE_SLOT = "back";
    private static final int PARACHUTE_MIN_HEIGHT = 8;

    private static final double MIN_CLEARANCE = 2.0;
    private static final double MAX_CLEARANCE = 6.0;
    private static final int ESCAPE_CANDIDATES = 12;
    private static final int MAX_ESCAPE_ELEVATION = 8;

    private BailOutSupport() {}

    public static void requestManualBail(AbstractUnit unit) {
        unit.getPersistentData().putBoolean(TAG_MANUAL_BAIL, true);
    }

    public static boolean hasManualBail(AbstractUnit unit) {
        return unit.getPersistentData().getBoolean(TAG_MANUAL_BAIL);
    }

    public static void clearManualBail(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG_MANUAL_BAIL);
    }

    /**
     * Expand seed unit ids to every owned PMC passenger on those hulls that pass {@code who},
     * and queue a manual bail for each. Returns how many units were queued.
     *
     * <p>{@code who} is the seat filter: bail-out passes everyone; paratroop reuses
     * {@link RappelSupport#isRappelEligible} (weaponless cargo only).
     */
    public static int requestManualBailExpanded(ServerPlayer player, Collection<Integer> seedIds,
                                                BiPredicate<VehicleEntity, Entity> who) {
        Set<VehicleEntity> hulls = new LinkedHashSet<>();
        for (int unitId : seedIds) {
            Entity e = player.level().getEntity(unitId);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!OrderAuth.check(player, pmc, "BailOutSupport.expand")) continue;
            if (!(pmc.getVehicle() instanceof VehicleEntity hull)) continue;
            hulls.add(hull);
        }

        int queued = 0;
        for (VehicleEntity hull : hulls) {
            for (Entity passenger : hull.getPassengers()) {
                if (!(passenger instanceof PmcUnitEntity pmc)) continue;
                if (!OrderAuth.check(player, pmc, "BailOutSupport.expand")) continue;
                if (OrderGuard.rejectIfDowned(player, pmc)) continue;
                if (!who.test(hull, passenger)) continue;
                requestManualBail(pmc);
                queued++;
            }
        }
        return queued;
    }

    /** Whole-crew bail — every owned PMC passenger on the hull. */
    public static BiPredicate<VehicleEntity, Entity> everyone() {
        return (hull, passenger) -> true;
    }

    /** Paratroop / rappel seat filter — weaponless cargo only. */
    public static BiPredicate<VehicleEntity, Entity> rappelEligible() {
        return RappelSupport::isRappelEligible;
    }

    /**
     * Radio, parachute, dismount, and full stand-down. Returns the scramble point when one was
     * found — the caller issues movement (PMC order or direct navigation).
     */
    @Nullable
    public static BlockPos triggerVehicleBail(AbstractUnit unit, VehicleEntity vehicle) {
        CrewRadio.speak(vehicle, unit, CrewRadio.Line.BAIL);

        BlockPos escape = findEscapePos(unit, vehicle);
        issueParachute(unit);
        TowRecoverySupport.clearOrder(unit, vehicle);
        unit.stopRiding();

        IVehicleBoarder boarder = (IVehicleBoarder) unit;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);
        MortarSupport.releaseClaim(unit);
        EntrenchSupport.clear(unit);
        unit.getPersistentData().remove(BailOutVehicleGoal.TAG_SANDBAG_SCRAMBLE);

        IHelicopterPilot pilot = (IHelicopterPilot) unit;
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
        pilot.sewv$setHeliLandPos(null);

        if (unit instanceof PmcUnitEntity pmc) {
            OrderStandDown.clearForVehicleBail(pmc);
            if (escape != null) {
                pmc.setMoveToTarget(Vec3.atBottomCenterOf(escape));
            }
        }
        return escape;
    }

    private static void issueParachute(AbstractUnit unit) {
        Level level = unit.level();
        BlockPos ground = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, unit.blockPosition());
        if (unit.getY() - ground.getY() < PARACHUTE_MIN_HEIGHT) return;

        CuriosApi.getCuriosInventory(unit).ifPresent(curios -> {
            ICurioStacksHandler back = curios.getStacksHandler(PARACHUTE_SLOT).orElse(null);
            if (back == null || back.getSlots() < 1) return;
            if (!back.getStacks().getStackInSlot(0).isEmpty()) return;
            back.getStacks().setStackInSlot(0, new ItemStack(ModItems.PARACHUTE.get()));
        });
    }

    @Nullable
    private static BlockPos findEscapePos(AbstractUnit unit, VehicleEntity vehicle) {
        AABB hullBox = VehicleMotionUtils.INSTANCE.calculateCombinedAABBOptimized(vehicle);
        double halfW = Math.max(hullBox.getXsize(), hullBox.getZsize()) * 0.5;
        return findEscapePosNear(unit, vehicle.getX(), vehicle.getY(), vehicle.getZ(), hullBox,
                halfW + MIN_CLEARANCE, halfW + MAX_CLEARANCE);
    }

    @Nullable
    private static BlockPos findEscapePosNear(AbstractUnit unit, double cx, double cy, double cz,
                                              @Nullable AABB hullBox, double minR, double maxR) {
        Level level = unit.level();
        RandomSource random = unit.getRandom();

        double exitX = unit.getX() - cx;
        double exitZ = unit.getZ() - cz;
        boolean hasExitDir = exitX * exitX + exitZ * exitZ > 1.0e-4;

        BlockPos bestSame = null;
        double bestSameDistSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDistSq = Double.MAX_VALUE;
        int refY = Mth.floor(cy);

        for (int i = 0; i < ESCAPE_CANDIDATES; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double radius = minR + random.nextDouble() * (maxR - minR);
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;
            int x = Mth.floor(cx + dx);
            int z = Mth.floor(cz + dz);

            BlockPos candidate = standableGroundAt(level, x, z, refY);
            if (candidate == null) continue;
            if (hullBox != null && hullBox.intersects(candidate.getX(), candidate.getY(), candidate.getZ(),
                    candidate.getX() + 1.0, candidate.getY() + 2.0, candidate.getZ() + 1.0)) {
                continue;
            }

            double distSq = unit.distanceToSqr(Vec3.atBottomCenterOf(candidate));
            if (distSq < bestAnyDistSq) {
                bestAny = candidate;
                bestAnyDistSq = distSq;
            }
            if (hasExitDir && dx * exitX + dz * exitZ > 0.0 && distSq < bestSameDistSq) {
                bestSame = candidate;
                bestSameDistSq = distSq;
            }
        }
        return bestSame != null ? bestSame : bestAny;
    }

    @Nullable
    private static BlockPos standableGroundAt(Level level, int x, int z, int hullY) {
        BlockPos column = new BlockPos(x, hullY, z);
        if (!level.isLoaded(column)) return null;

        BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (Math.abs(pos.getY() - hullY) > MAX_ESCAPE_ELEVATION) return null;

        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP)) return null;
        if (ground.getFluidState().is(FluidTags.LAVA)) return null;

        return isPassable(level, pos) && isPassable(level, pos.above()) ? pos : null;
    }

    private static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && !state.getFluidState().is(FluidTags.LAVA);
    }
}
