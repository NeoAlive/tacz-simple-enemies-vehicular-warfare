package com.neoalive.tacz_sewv.mixin;

/** Soft-compat gate for {@code tacz_sewv.gemrender.mixins.json}. */
public final class GemRenderMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "gemrender";
    }
}
