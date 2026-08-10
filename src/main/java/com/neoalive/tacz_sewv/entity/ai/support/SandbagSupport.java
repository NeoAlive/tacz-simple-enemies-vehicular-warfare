package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.block.SandbagBlock;
import com.neoalive.tacz_sewv.block.SandbagBlockEntity;
import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;

/**
 * Sandbag fighting-position helpers for ENTRENCHED assign / seek / tick.
 *
 * <p>A seat is taken when someone is riding it <em>or</em> another unit holds the soft claim
 * UUID on the {@link SandbagBlockEntity} — so two crews cannot both walk the same single-seat
 * bag without a world entity scan.
 */
public final class SandbagSupport {

    private SandbagSupport() {}

    public static boolean isSandbag(BlockState state) {
        return state.getBlock() instanceof SandbagBlock;
    }

    public static boolean isSandbag(ServerLevel level, BlockPos pos) {
        return isSandbag(level.getBlockState(pos));
    }

    /**
     * True when nobody is seated, nobody else holds the BE claim, or {@code self}
     * already owns the seat / claim.
     */
    public static boolean isSeatAvailable(ServerLevel level, BlockPos pos, @Nullable LivingEntity self) {
        if (!isSandbag(level, pos)) return false;
        LivingEntity occupant = occupantOf(level, pos);
        if (occupant != null && occupant != self) return false;
        if (!(level.getBlockEntity(pos) instanceof SandbagBlockEntity be)) return false;
        return be.isClaimAvailable(self);
    }

    @Nullable
    public static LivingEntity occupantOf(ServerLevel level, BlockPos pos) {
        SandbagSeatEntity seat = findSeat(level, pos);
        if (seat == null) return null;
        Entity first = seat.getFirstPassenger();
        return first instanceof LivingEntity living ? living : null;
    }

    public static void setClaimant(ServerLevel level, BlockPos pos, @Nullable LivingEntity unit) {
        if (level.getBlockEntity(pos) instanceof SandbagBlockEntity be) {
            be.setClaimant(unit);
        }
    }

    public static void clearClaimantIf(ServerLevel level, BlockPos pos, @Nullable LivingEntity unit) {
        if (level.getBlockEntity(pos) instanceof SandbagBlockEntity be) {
            be.clearClaimantIf(unit);
        }
    }

    public static boolean isRidingThis(LivingEntity rider, BlockPos sandbag) {
        return rider.getVehicle() instanceof SandbagSeatEntity seat
                && sandbag.equals(seat.getSandbagPos());
    }

    public static boolean isRidingSandbag(Entity entity) {
        return entity.getVehicle() instanceof SandbagSeatEntity;
    }

    /** Dismount if riding any sandbag seat. Used by ENTRENCHED dismiss — not vehicle dismount. */
    public static void dismountIfSeated(LivingEntity entity) {
        if (entity.getVehicle() instanceof SandbagSeatEntity) {
            entity.stopRiding();
        }
    }

    public static boolean tryMount(ServerLevel level, BlockPos pos, LivingEntity rider) {
        if (!isSeatAvailable(level, pos, rider)) return false;
        if (isRidingThis(rider, pos)) return true;
        return SandbagBlock.tryMount(level, pos, rider);
    }

    /**
     * Nearest free sandbag within {@code radius} of {@code near}, or null.
     * {@code self} may reclaim a bag it already occupies / claims.
     */
    @Nullable
    public static BlockPos findNearestFree(ServerLevel level, BlockPos near, double radius,
                                           @Nullable LivingEntity self) {
        int r = (int) Math.ceil(radius);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                near.getX() - r, near.getY() - 2, near.getZ() - r,
                near.getX() + r, near.getY() + 2, near.getZ() + r)) {
            if (!isSandbag(level, p)) continue;
            if (!isSeatAvailable(level, p, self)) continue;
            double d = p.distSqr(near);
            if (d <= radius * radius && d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /**
     * Resolve a raycast/order hit to a free sandbag: exact hit if free, else nearest free bag
     * nearby (occupied / claimed bags are never returned).
     */
    @Nullable
    public static BlockPos resolveHit(ServerLevel level, BlockPos hitPos) {
        if (isSandbag(level, hitPos) && isSeatAvailable(level, hitPos, null)) {
            return hitPos.immutable();
        }
        // Same slack as trench network resolve (map / imprecise clicks).
        return findNearestFree(level, hitPos, 8.0, null);
    }

    @Nullable
    private static SandbagSeatEntity findSeat(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SandbagBlockEntity) {
            AABB box = new AABB(pos).inflate(0.75D);
            for (SandbagSeatEntity seat : level.getEntitiesOfClass(SandbagSeatEntity.class, box)) {
                if (pos.equals(seat.getSandbagPos())) return seat;
            }
        }
        return null;
    }

    /** Debug / seek helper: nearest RU/US infantry that can take a sandbag order. */
    @Nullable
    public static AbstractUnit findNearestIdleFactionUnit(ServerLevel level, BlockPos near,
                                                          double radius) {
        AbstractUnit best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class,
                new AABB(near).inflate(radius),
                SandbagSupport::canAutoSeat)) {
            double d = unit.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = unit;
            }
        }
        return best;
    }

    public static boolean canAutoSeat(Entity entity) {
        if (!(entity instanceof AbstractUnit unit)) return false;
        if (!(unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity
                || unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity)) {
            return false;
        }
        if (unit.isPassenger()) return false;
        if (unit.getTarget() != null) return false;
        if (EntrenchSupport.isEntrenched(unit)) return false;
        return true;
    }
}
