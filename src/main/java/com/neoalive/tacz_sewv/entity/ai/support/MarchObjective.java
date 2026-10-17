package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A standing "drive at this place" for a crew with no order queue.
 *
 * <p>RU/US have no way to be told where to go. Every existing destination pipeline is somebody
 * else's: {@code IVehiclePatrol} is mixed onto {@code PmcUnitEntity} alone and its whole branch in
 * {@code VehicleTargeting.resolveDestination} sits inside the PMC test, and {@code ICaptureOrder}
 * is on RU/US but is welded to the invasion product mode (it needs a live
 * {@code InvasionSession} and a capture-point block to aim at). So a faction crew that is not
 * fighting has exactly two answers today — reinforce a nearby ally, or wander — and neither is
 * "attack that colony".
 *
 * <p>State lives in the <b>hull's</b> {@code getPersistentData()} rather than a bridge interface
 * on the unit, the same choice {@code IdleSupport} made and for the same two reasons: it needs no
 * mixin (so it costs nothing on units that never have one), and the objective belongs to the
 * vehicle, so a crew replaced mid-march inherits it. Being a {@code BlockPos} it survives a
 * save/load meaning the same place, unlike anything keyed on a network id.
 *
 * <p>It is deliberately outranked by a live target: a crew marching on an objective still fights
 * whatever gets in the way, and resumes the march when the fight is over.
 */
public final class MarchObjective {

    private static final String TAG_POS = "tacz_sewv_march_objective";

    private MarchObjective() {}

    public static void set(VehicleEntity hull, BlockPos pos) {
        hull.getPersistentData().putLong(TAG_POS, pos.asLong());
    }

    public static void clear(VehicleEntity hull) {
        hull.getPersistentData().remove(TAG_POS);
    }

    @Nullable
    public static BlockPos of(@Nullable VehicleEntity hull) {
        if (hull == null) return null;
        CompoundTag tag = hull.getPersistentData();
        return tag.contains(TAG_POS) ? BlockPos.of(tag.getLong(TAG_POS)) : null;
    }
}
