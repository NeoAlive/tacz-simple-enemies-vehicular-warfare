package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.xaero.UnitOrderOption;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.VehicleMarker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * The command half of the map integration: left-click a vehicle marker to select it, right-click the
 * map for "move the selected units here".
 *
 * <p>Both hooks are here because Xaero has no registry for either. Selection rides on
 * {@code mapClicked}, whose left-button branch is empty — the element framework has already resolved
 * whatever the cursor is over into {@code viewed}, so this only has to read it and stop the click.
 * The menu entry rides on {@code getRightClickOptions}, which builds a fresh list per click and has
 * the clicked block position sitting in {@code rightClickX/Y/Z}; the option itself is
 * {@link MoveHereOption}, deliberately not an anonymous class in a mixin.
 *
 * <p>Lives in a separate, <b>non-required</b> mixin config: Xaero is optional, and the main config
 * is {@code required: true}, which would turn a missing map mod into a startup crash.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMap {

    @Shadow
    private HoveredMapElementHolder<?, ?> viewed;

    @Shadow
    private int rightClickX;

    @Shadow
    private int rightClickY;

    @Shadow
    private int rightClickZ;

    @Shadow
    private ResourceKey<Level> rightClickDim;

    @Inject(method = "mapClicked", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$selectMarker(int button, int x, int y, CallbackInfo ci) {
        if (button != 0 || this.viewed == null) return;
        if (!(this.viewed.getElement() instanceof VehicleMarker marker)) return;
        // Only swallow the click if it selected something — a click on an enemy symbol should still
        // do whatever the map would have done with it.
        if (MapMarkers.toggleSelected(marker)) ci.cancel();
    }

    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void tacz_sewv$moveHereOption(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        if (!SewvConfig.MAP_MARKERS_ENABLED.get()) return;
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null) return;

        options.addAll(UnitOrderOption.allFor(options.size(), (GuiMap) (Object) this,
                this.rightClickX, this.rightClickY, this.rightClickZ, this.rightClickDim,
                MapMarkers.selected().size()));
    }
}
