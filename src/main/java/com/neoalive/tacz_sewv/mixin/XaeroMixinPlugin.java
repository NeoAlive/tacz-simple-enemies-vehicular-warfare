package com.neoalive.tacz_sewv.mixin;

/**
 * Soft-compat gate for {@code tacz_sewv.xaero.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin} for why the gate exists at all.
 *
 * <p>Mod id is {@code xaeroworldmap} — Forge World Map only; Minimap ({@code xaerominimap}) does
 * not ship {@code xaero.map.gui.GuiMap}. Same id as {@code XaeroMapCompat.MODID} / mods.toml —
 * do not import that class (it names Xaero types).
 */
public final class XaeroMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "xaeroworldmap";
    }
}
