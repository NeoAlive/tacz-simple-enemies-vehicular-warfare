package com.neoalive.tacz_sewv.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.norwood.komodo.client.render.kmodo.KmodoLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Guards Komodo's shared "world light" texture against off-render-thread use.
 *
 * <p>{@code KmodoLight.worldLightLightmap} lazily constructs a {@code DynamicTexture} and uploads
 * pixels to it on every call — GL work that is only safe on the render thread. Vanilla's
 * {@code DynamicTexture.upload()} silently defers to {@code RenderSystem}'s next-frame replay
 * queue when called off-thread instead of failing loudly, and by the time that queued call runs
 * the texture can already be gone, producing a same-tick NPE
 * ({@code Cannot invoke "NativeImage.upload()" because "this.pixels" is null}) that crashes the
 * whole client. This is Komodo's own bug (its retained-rendering fallback — used for a vehicle's
 * first frame or two before its GPU-instanced path is baked — is reachable from a non-render-thread
 * caller), not anything this mod's dormancy compat ({@link MixinKmodoDormancy}) does; the two
 * mixins target unrelated classes.
 *
 * <p>{@code worldLightLightmap} is the single choke point for every use of that texture, so
 * guarding it here is sufficient: off the render thread, skip the whole body (no texture
 * construction, no pixel upload) and hand back {@code 0} (GL texture unit unbound) for that one
 * frame instead of crashing. Komodo's own {@code KmodoFlywheelModelCache.getModels} uses the same
 * "not on render thread → skip, don't touch GL" defensive shape.
 */
@Mixin(value = KmodoLight.class, remap = false)
public abstract class MixinKmodoLight {

    @Inject(method = "worldLightLightmap", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$skipOffRenderThread(int packedLight, CallbackInfoReturnable<Integer> cir) {
        if (SewvConfig.SPEC.isLoaded() && !SewvConfig.KOMODO_RENDER_FIX_ENABLED.get()) return;
        if (!RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(0);
        }
    }
}
