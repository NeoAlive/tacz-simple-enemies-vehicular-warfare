package com.neoalive.tacz_sewv.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** An on/off button whose label reads "{label}: ON/OFF" and refreshes itself on click. */
final class ToggleButton {

    private ToggleButton() {}

    static Button create(int x, int y, int w, Component label, BooleanSupplier get, Consumer<Boolean> set) {
        Button[] self = new Button[1];
        self[0] = Button.builder(message(label, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            self[0].setMessage(message(label, get.getAsBoolean()));
        }).bounds(x, y, w, 20).build();
        return self[0];
    }

    private static Component message(Component label, boolean on) {
        return Component.translatable(on ? "gui.tacz_sewv.toggle.on" : "gui.tacz_sewv.toggle.off", label);
    }
}
