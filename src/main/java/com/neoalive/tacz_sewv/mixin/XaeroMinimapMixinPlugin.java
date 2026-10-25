package com.neoalive.tacz_sewv.mixin;

import java.util.List;

/**
 * Soft-compat gate for {@code tacz_sewv.xaerominimap.mixins.json} — see
 * {@link AbstractSoftDepMixinPlugin} for why the gate exists at all.
 *
 * <p>Two ids: Xaero's Minimap ({@code xaerominimap}) and Xaero's Better PvP ({@code xaerobetterpvp})
 * ship the same {@code xaero.common} / {@code xaero.hud} classes, so the mixins fit either. Same ids
 * as {@code XaeroMinimapCompat.MODIDS} — do not import that class (it names Xaero types).
 */
public final class XaeroMinimapMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "xaerominimap";
    }

    @Override
    protected List<String> modIds() {
        return List.of("xaerominimap", "xaerobetterpvp");
    }
}
