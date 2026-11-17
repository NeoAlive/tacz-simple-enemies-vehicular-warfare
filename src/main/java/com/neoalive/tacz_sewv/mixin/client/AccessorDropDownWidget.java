package com.neoalive.tacz_sewv.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

/** First visible slot of a Xaero dropdown, so the order flyout knows which row the cursor is on. */
@Mixin(value = DropDownWidget.class, remap = false)
public interface AccessorDropDownWidget {

    @Accessor("scroll")
    int tacz_sewv$scroll();
}
