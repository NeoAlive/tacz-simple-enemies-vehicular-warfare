package com.neoalive.tacz_sewv.client.gui;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** {@code [-] label [+]}: a disabled centre button showing the value, clamped to {@code [min, max]}. */
final class Stepper {

    private Stepper() {}

    static void create(Consumer<Button> register, int x, int y, int w, int min, int max,
                       IntSupplier get, Consumer<Integer> set, IntFunction<Component> label) {
        Button centre = Button.builder(label.apply(get.getAsInt()), b -> {})
                .bounds(x + 22, y, w - 44, 20).build();
        centre.active = false;
        register.accept(Button.builder(Component.literal("-"), b -> {
            set.accept(Math.max(min, get.getAsInt() - 1));
            centre.setMessage(label.apply(get.getAsInt()));
        }).bounds(x, y, 20, 20).build());
        register.accept(centre);
        register.accept(Button.builder(Component.literal("+"), b -> {
            set.accept(Math.min(max, get.getAsInt() + 1));
            centre.setMessage(label.apply(get.getAsInt()));
        }).bounds(x + w - 20, y, 20, 20).build());
    }
}
