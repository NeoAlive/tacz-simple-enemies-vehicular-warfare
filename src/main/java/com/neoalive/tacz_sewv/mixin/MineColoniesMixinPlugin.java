package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.minecolonies.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin} for why the gate exists at all.
 *
 * <p>Both gated mixins sit on MineColonies' own classes, and both are the kind that would be
 * loudly missing on an install without it: {@code MixinGuardTargeting} on a guard AI method and
 * {@code MixinRaidManager} on the colony raid scheduler.
 */
public final class MineColoniesMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "minecolonies";
    }
}
