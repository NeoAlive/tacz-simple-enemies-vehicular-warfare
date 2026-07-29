package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.config.ClientConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import xaero.map.gui.GuiMap;

/**
 * Stops Xaero's World Map from pausing the integrated server — the single thing that made the whole
 * map-command feature look broken in singleplayer.
 *
 * <p>A vanilla {@code Screen} returns {@code true} from {@code isPauseScreen}, and Xaero's GuiMap
 * never overrides it, so in singleplayer opening the map pauses the server: <b>no ticks run, so the
 * marker sync ({@code OwnedVehicleTracker}, a server-tick event) is frozen and never re-sent</b>.
 * The consequence is exactly the reported bug — you issue FREE_FIRE / DISMISS / a bulk move, the
 * server may even apply it, but the client is never told, so the order previews never update and it
 * all looks like nothing happened. This mod's own {@code TdtScreen} already returns false for the
 * same reason; the map has to as well to be a live command screen. Multiplayer never paused, so this
 * only ever changes singleplayer.
 *
 * <p>Its own mixin, deliberately not folded into the sibling {@code MixinGuiMap}. That one is
 * {@code remap = false} for Xaero's own members, but this ADDS an override of a <em>vanilla</em>
 * method whose name must be remapped to SRG in production — so it needs the default {@code remap =
 * true}, which the whole class cannot have without breaking every Xaero-member reference next door.
 * Same {@code XaeroMixinPlugin} gate as that sibling.
 */
@Mixin(GuiMap.class)
public abstract class MixinGuiMapPause extends Screen {

    protected MixinGuiMapPause(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        // Live only while the command feature is actually on; otherwise leave the vanilla pause be.
        return !ClientConfig.mapMarkersEnabled() || !ClientConfig.MAP_LIVE.get();
    }
}
