package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.corpse.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin}.
 *
 * <p>Mod id matches {@code CorpseCompat.MODID} / mods.toml — do not import that class here.
 * All listed mixins use string {@code targets} (no Corpse class literals) so they remain
 * classloadable when Corpse is absent; this plugin still skips apply entirely in that case.
 */
public final class CorpseMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "corpse";
    }
}
