package com.neoalive.tacz_sewv.order;

import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/**
 * Which order a unit's standing MOVE or area task came from, so the map can drag every unit of one
 * order together. SEM sends one packet per unit, so a batch is the owner plus the game tick the
 * order landed on. Stamped from the two writers every such order goes through
 * ({@code setMoveToTarget}, {@code sewv$setAreaTask}). Persistent, id-free.
 *
 * <p>Also holds the MOVE arrival flag: SEM's MOVE never completes, so the map hides a waypoint once
 * its unit has parked on it.
 */
public final class OrderBatch {

    public static final String TAG = "sewv:order_batch";
    public static final String TAG_REACHED = "sewv:move_reached";

    private static final double FOOT_REACH = 6.0;
    private static final double DRIFT = 12.0;

    /** True while a waypoint drag rewrites an order, so the rewrite keeps its batch. Server thread only. */
    private static boolean editing;

    private OrderBatch() {}

    public static void stamp(Entity unit) {
        if (editing || unit.level().isClientSide()) return;
        long owner = 0L;
        if (unit instanceof PmcUnitEntity pmc) {
            UUID id = pmc.getOwnerUUID();
            if (id != null) owner = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        }
        CompoundTag data = unit.getPersistentData();
        data.putLong(TAG, mix(owner * 31L + unit.level().getGameTime()));
        data.remove(TAG_REACHED);
    }

    /** A new batch shared by the given units, used when one is pulled out of its group. */
    public static void detach(Iterable<? extends Entity> units, long seed) {
        long id = mix(seed ^ 0x5DEECE66DL);
        for (Entity e : units) {
            e.getPersistentData().putLong(TAG, id);
        }
    }

    public static long of(Entity unit) {
        return unit.getPersistentData().getLong(TAG);
    }

    public static void edit(Runnable change) {
        editing = true;
        try {
            change.run();
        } finally {
            editing = false;
        }
    }

    public static void clearReached(Entity unit) {
        unit.getPersistentData().remove(TAG_REACHED);
    }

    /**
     * Updates and answers the arrival flag for a unit standing on MOVE. A formed-up unit is measured
     * to its slot, since that is where it parks. Cleared again past {@link #DRIFT} so a unit knocked
     * off its point shows the waypoint again.
     */
    public static boolean reached(PmcUnitEntity pmc, Vec3 target) {
        Entity body = pmc.getRootVehicle();
        Vec3 dest = target;
        double reach;
        if (body instanceof VehicleEntity hull) {
            var slot = VehicleFormation.formationSlotPos(pmc.level(), pmc, target);
            if (slot != null) dest = Vec3.atBottomCenterOf(slot);
            reach = VehicleTargeting.arrivalDistance(pmc, hull) + 2.0;
        } else {
            Vec3 slot = VehicleFormation.formationSlotCenter(pmc, target);
            if (slot != null) dest = slot;
            reach = FOOT_REACH;
        }
        double dx = body.getX() - dest.x;
        double dz = body.getZ() - dest.z;
        double d2 = dx * dx + dz * dz;
        CompoundTag data = pmc.getPersistentData();
        boolean was = data.getBoolean(TAG_REACHED);
        if (!was && d2 <= reach * reach) {
            data.putBoolean(TAG_REACHED, true);
            return true;
        }
        if (was && d2 > Math.max(DRIFT, reach) * Math.max(DRIFT, reach)) {
            data.remove(TAG_REACHED);
            return false;
        }
        return was;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z == 0L ? 1L : z;
    }
}
