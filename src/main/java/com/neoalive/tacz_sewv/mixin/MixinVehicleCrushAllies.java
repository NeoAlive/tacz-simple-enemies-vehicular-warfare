package com.neoalive.tacz_sewv.mixin;

import java.util.ArrayList;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Safety net for AI-crewed hulls: SBW's {@code crushEntities} damages every unmounted
 * living entity under the hull with no faction check. Same-faction infantry are dropped
 * from the crush candidate list when the driver is an {@link AbstractUnit}, so a whisker
 * miss (dismount squad beside an IFV, crowded garrison) cannot teamkill. Enemy infantry
 * stay crushable; player-driven hulls are untouched.
 *
 * <p>0.8.9.1 replaced the old {@code stream().filter(...).toList()} with an explicit
 * {@link ArrayList} build, so the filter hooks {@code ArrayList.add} instead of
 * {@code Stream.toList}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils")
public abstract class MixinVehicleCrushAllies {

    @Redirect(
            method = "crushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/ArrayList;add(Ljava/lang/Object;)Z",
                    remap = false
            ),
            remap = false
    )
    private static boolean tacz_sewv$omitAlliedInfantry(ArrayList<?> list, Object entity,
            VehicleEntity vehicle) {
        if (vehicle.getFirstPassenger() instanceof AbstractUnit driver
                && entity instanceof AbstractUnit victim
                && VehicleTargeting.isFriendly(driver, victim)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        ArrayList<Object> raw = (ArrayList<Object>) list;
        return raw.add(entity);
    }
}
