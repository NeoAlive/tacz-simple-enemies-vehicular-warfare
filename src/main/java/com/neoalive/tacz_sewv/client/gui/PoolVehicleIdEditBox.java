package com.neoalive.tacz_sewv.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * Edit box that completes a vehicle id on Tab instead of surrendering focus to the next widget.
 */
final class PoolVehicleIdEditBox extends EditBox {

    private BooleanSupplier tabCompleter;

    PoolVehicleIdEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
    }

    void setTabCompleter(BooleanSupplier tabCompleter) {
        this.tabCompleter = tabCompleter;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.isFocused() && keyCode == InputConstants.KEY_TAB && this.tabCompleter != null) {
            if (this.tabCompleter.getAsBoolean()) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
