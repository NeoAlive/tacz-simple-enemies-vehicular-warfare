package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.playerrevive.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin} for why the gate exists at all.
 *
 * <p>Mod id matches {@code PlayerReviveCompat.MODID} / mods.toml — do not import that class here
 * (it would force-load {@code team.creative.playerrevive.*} regardless of presence).
 */
public final class PlayerReviveMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "playerrevive";
    }
}
