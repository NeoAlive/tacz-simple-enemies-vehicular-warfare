package com.neoalive.tacz_sewv.entity.ai.sensor;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IAcquisition;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.EasyTuning;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * The infantry side of {@link Acquisition}: whether a SEM unit has had long enough to notice a target
 * its proactive scan just picked.
 *
 * <p>The delay is computed once, when a target is first picked, and stored on the unit
 * ({@link IAcquisition}). Awareness carries between targets: if the scan flips to a different
 * candidate while the unit is still looking (two enemies at similar range, say), the clock keeps
 * running rather than restarting, so a unit facing a group is never starved of a target.
 *
 * <p>Crews are exempt (their scan goal acquires, and {@code FireHesitation} handles the reaction),
 * as is the unit's current target and whoever just hurt it.
 */
public final class UnitAcquisition {

    /** How long awareness of a contact carries over to a different candidate. */
    static final long CARRY_TICKS = 40;

    private UnitAcquisition() {}

    public static boolean enabled() {
        try {
            return SewvConfig.ACQUISITION_DELAY_ENABLED.get();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    /** True while {@code unit} has not yet noticed {@code target}. */
    public static boolean denies(AbstractUnit unit, LivingEntity target, long now) {
        if (!enabled()) return false;
        if (unit.getVehicle() instanceof VehicleEntity || LvCompat.isLvCrew(unit)) return false;
        if (unit.getTarget() == target || unit.getLastHurtByMob() == target) return false;

        IAcquisition a = (IAcquisition) unit;
        int id = target.getId();
        boolean carrying = a.sewv$getAcqTargetId() != -1
                && now - a.sewv$getAcqLastSeen() <= CARRY_TICKS;
        if (a.sewv$getAcqTargetId() == id || carrying) {
            a.sewv$setAcqTargetId(id);
            a.sewv$setAcqLastSeen(now);
            return now < a.sewv$getAcqReadyAt();
        }

        int delay = Acquisition.delayTicks(baseTicks(unit), unit.getEyePosition(), target, stress(unit),
                unit.getId(), now);
        a.sewv$setAcqTargetId(id);
        a.sewv$setAcqLastSeen(now);
        a.sewv$setAcqReadyAt(now + delay);
        return delay > 0;
    }

    private static int baseTicks(AbstractUnit unit) {
        int base = Acquisition.INFANTRY_BASE_TICKS;
        return EasyMode.softAim(unit) ? (int) Math.round(base * EasyTuning.ACQUISITION_MULT) : base;
    }

    /** SEM's own suppression level, as 0..1 stress. */
    private static double stress(AbstractUnit unit) {
        try {
            return Math.min(1.0, unit.getGenericSuppressionLevel());
        } catch (LinkageError olderSem) {
            return 0.0;
        }
    }
}
