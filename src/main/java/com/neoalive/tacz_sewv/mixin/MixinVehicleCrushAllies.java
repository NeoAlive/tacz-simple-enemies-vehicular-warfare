package com.neoalive.tacz_sewv.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils")
public abstract class MixinVehicleCrushAllies {

    @Redirect(
            method = "crushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;toList()Ljava/util/List;",
                    remap = false
            ),
            remap = false
    )
    private List<?> tacz_sewv$omitAlliedInfantry(Stream<?> stream, VehicleEntity vehicle) {
        List<?> list = stream.toList();
        if (!(vehicle.getFirstPassenger() instanceof AbstractUnit driver)) {
            return list;
        }
        List<Object> filtered = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof AbstractUnit victim && VehicleTargeting.isFriendly(driver, victim)) {
                continue;
            }
            filtered.add(o);
        }
        return filtered;
    }
}
