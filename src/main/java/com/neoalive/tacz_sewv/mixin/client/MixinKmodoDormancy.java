package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.norwood.komodo.client.render.kmodo.KmodoDormancy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
 *
 * <p>Escape hatch: {@code -Dsewv.komodoDormancy=false} disables this injection's effect at
 * startup (same JVM-flag shape as {@code GunCacheProbe}'s {@code sewv.guncacheProbe}) — a way to
 * rule this compat in or out of a client crash without rebuilding, by isolating whether Komodo's
 * own dormancy code path is stable under the case it never gets exercised by a plain install
 * (many AI-crewed hulls going dormant together, vs. one player-abandoned vehicle at a time).
 */
@Mixin(value = KmodoDormancy.class, remap = false)
public abstract class MixinKmodoDormancy {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("sewv.komodoDormancy", "true"));

    @Inject(method = "occupied", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$ignoreAiOnlyCrew(GeoVehicleEntity e, CallbackInfoReturnable<Boolean> cir) {
        if (!ENABLED) return;
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
