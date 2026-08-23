package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.komodo.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin} for why the gate exists at all.
 *
 * <p>{@code MixinKmodoDormancy} targets Komodo by string name (no compile-time dependency on
 * Komodo's jar), so this plugin is the only place that needs Komodo's mod id.
 */
public final class KomodoMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "komodo";
    }
}
