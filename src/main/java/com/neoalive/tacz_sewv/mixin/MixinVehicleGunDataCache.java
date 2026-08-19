package com.neoalive.tacz_sewv.mixin;

import java.util.Map;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SBW's {@code VehicleEntity.gunDataMap} getter has no memoization of its own: every
 * single read rebuilds the whole seat-&gt;weapon map from scratch, copying each weapon's
 * {@link net.minecraft.world.item.ItemStack} and feeding it through {@code GunData.from},
 * whose backing cache is built with Guava's {@code weakKeys()} — which compares keys by
 * reference identity, not equality. A freshly-copied stack is therefore a guaranteed miss,
 * every call, forcing a synchronous reload. {@code canShoot()}/{@code getGunData(...)} all
 * route through this getter, and SBW's own native AI-fire loop in {@code baseTick()} calls
 * {@code canShoot(mob)} for every crewed seat every tick — so a hull with many weapon slots
 * and many gunner seats (bmp_2: 7 slots x up to 6 gunners) pays this rebuild dozens of times
 * a tick, measured at ~1500-1600 microsec/t versus ~300 on a single-weapon hull.
 *
 * <p>This mirrors {@link MixinVehicleFireCooldown}'s own per-tick line-of-fire cache: memoize
 * the rebuilt map for the duration of one game tick, invalidated on any write. Every write
 * path in SBW's own code ({@code modifyGunData}, the end-of-tick gun-state tick, save/load)
 * already copies before mutating rather than touching a live read's map in place, so this
 * changes nothing observable — it only turns redundant same-tick rebuilds of an unchanged
 * map into a cache hit.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleGunDataCache {

    @Unique
    private long tacz_sewv$gunMapTick = Long.MIN_VALUE;

    @Unique
    private Map<String, GunData> tacz_sewv$gunMapCache;

    @Inject(method = "getGunDataMap", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$gunMapHead(CallbackInfoReturnable<Map<String, GunData>> cir) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (this.tacz_sewv$gunMapCache != null
                && this.tacz_sewv$gunMapTick == self.level().getGameTime()) {
            cir.setReturnValue(this.tacz_sewv$gunMapCache);
        }
    }

    @Inject(method = "getGunDataMap", at = @At("RETURN"), remap = false)
    private void tacz_sewv$gunMapReturn(CallbackInfoReturnable<Map<String, GunData>> cir) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        this.tacz_sewv$gunMapCache = cir.getReturnValue();
        this.tacz_sewv$gunMapTick = self.level().getGameTime();
    }

    // The synched map changed underneath us — a same-tick read after this (e.g. after
    // VehicleWeapons.selectCannonAmmo's modifyGunData call, or SBW's own end-of-tick
    // gun-state write) must rebuild against the fresh data, not serve the pre-write snapshot.
    @Inject(method = "setGunDataMap", at = @At("HEAD"), remap = false)
    private void tacz_sewv$gunMapInvalidate(Map<String, GunData> value, CallbackInfo ci) {
        this.tacz_sewv$gunMapCache = null;
        this.tacz_sewv$gunMapTick = Long.MIN_VALUE;
    }
}
