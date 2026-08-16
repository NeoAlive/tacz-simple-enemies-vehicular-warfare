package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.norwood.komodo.client.render.kmodo.KmodoDormancy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Komodo's render-thread dormancy fast path (skip re-baking GPU geometry for a parked vehicle)
 * treats ANY passenger as "occupied" and therefore never lets a crewed hull sleep
 * (komodo's {@code KmodoDormancy.occupied}). That blanket rule is right for a live player at the
 * wheel, who could react any instant, but wrong for tacz_sewv's AI crews, which spend long
 * stretches sitting still (HOLD, idle dwell, a finished patrol leg) — exactly the case Komodo's
 * dormancy exists to catch. Komodo's own {@code wakeSignal} already wakes on any real state
 * change (movement, rotation, turret/gun angle, recoil, fire, ammo content) independently of
 * occupancy, so ignoring AI-only passengers here cannot leave a moving or firing hull frozen; it
 * only unlocks the fast path for hulls that were already motionless.
 *
 * <p>Compiled against Komodo's own release jar ({@code libs/komodo.jar}, {@code compileOnly}) —
 * {@link com.neoalive.tacz_sewv.mixin.KomodoMixinPlugin} keeps this mixin out of the apply queue
 * entirely when Komodo isn't installed at runtime, and a future Komodo refactor that renames or
 * removes {@code occupied} just makes this injection fail to apply (config is
 * {@code "required": false}), not crash.
 */
@Mixin(value = KmodoDormancy.class, remap = false)
public abstract class MixinKmodoDormancy {

    @Inject(method = "occupied", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$ignoreAiOnlyCrew(GeoVehicleEntity e, CallbackInfoReturnable<Boolean> cir) {
        boolean hasPassenger = false;
        for (Entity passenger : e.getPassengers()) {
            if (passenger instanceof Player) {
                return; // a real player aboard: leave Komodo's own (always-occupied) answer alone
            }
            hasPassenger = true;
        }
        if (hasPassenger) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
