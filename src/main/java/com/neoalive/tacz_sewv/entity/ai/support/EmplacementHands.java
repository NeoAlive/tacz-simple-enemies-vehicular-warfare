package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * When a unit's held items should not draw: crewing a TOW (vanilla passenger sync) or
 * standing at a mortar tube ({@link #MANNING_MORTAR}, set only while
 * {@code ManMortarGoal} is actually working the tube).
 *
 * <p>Approach, path failure, overrun (rifle fight), dead tube, and claim release all clear
 * the flag — so a stuck walker or a crew pulled off the tube shows its gun again.
 */
public final class EmplacementHands {

    /** Synched from {@code MixinAbstractUnit}; false until a mortar crew is at the tube. */
    public static final EntityDataAccessor<Boolean> MANNING_MORTAR =
            SynchedEntityData.defineId(AbstractUnit.class, EntityDataSerializers.BOOLEAN);

    private EmplacementHands() {}

    public static boolean hideHeldItems(LivingEntity entity) {
        if (entity.getVehicle() instanceof TowEntity tow && tow.isAlive() && !tow.isWreck()) {
            return true;
        }
        if (!(entity instanceof AbstractUnit)) return false;
        return entity.getEntityData().get(MANNING_MORTAR);
    }

    public static void setManningMortar(AbstractUnit unit, boolean manning) {
        if (unit.level().isClientSide()) return;
        Boolean cur = unit.getEntityData().get(MANNING_MORTAR);
        if (cur == manning) return;
        unit.getEntityData().set(MANNING_MORTAR, manning);
    }
}
