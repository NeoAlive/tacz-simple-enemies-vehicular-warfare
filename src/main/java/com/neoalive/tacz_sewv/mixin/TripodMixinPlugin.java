package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.tripod.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin}. {@code MixinTripodShieldBlock} targets Tripod Invasion by class
 * name (no compile-time dependency), so this is the only place its mod id appears on the mixin side.
 */
public final class TripodMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "xx_tripod_invasion";
    }
}
