package com.neoalive.tacz_sewv.mixin.client;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.EntityType;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides any mounted unit whose seat encloses it ({@code getHidePassenger}). One mixin for
 * all three unit renderers — they share no SEM base class (each extends MobRenderer
 * directly), so the entity parameter's type differs per target and is {@code @Coerce}d to
 * the common {@link AbstractUnit}.
 */
@Mixin({PmcUnitRenderer.class, RUunitRenderer.class, USunitRenderer.class})
public abstract class MixinUnitRenderer {

    @Unique
    private static final Map<EntityType<?>, boolean[]> TACZ_SEWV$HIDDEN_SEATS = new IdentityHashMap<>();

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$hideMountedInEnclosedSeat(
            @Coerce AbstractUnit entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {

        if (entity.getVehicle() instanceof VehicleEntity vehicle) {
            int seat = vehicle.getTagSeatIndex(entity);
            boolean[] hidden = TACZ_SEWV$HIDDEN_SEATS.computeIfAbsent(vehicle.getType(),
                    type -> tacz_sewv$hiddenSeats(type));
            if (seat >= 0 && seat < hidden.length && hidden[seat]) {
                cir.setReturnValue(false); // enclosed seat, skip rendering entirely
            }
        }
    }

    @Unique
    private static boolean[] tacz_sewv$hiddenSeats(EntityType<?> type) {
        try {
            List<com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo> seats =
                    VehicleData.getDefault(type).seats();
            boolean[] hidden = new boolean[seats.size()];
            for (int i = 0; i < hidden.length; i++) hidden[i] = seats.get(i).getHidePassenger();
            return hidden;
        } catch (Throwable ignored) {
            return new boolean[0];
        }
    }
}
