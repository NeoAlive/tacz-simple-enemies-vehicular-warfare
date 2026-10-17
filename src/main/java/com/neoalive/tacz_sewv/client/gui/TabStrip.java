package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A row of tab buttons that wraps onto further rows. The active tab is the greyed-out (inactive)
 * button — the vanilla idiom, which keeps the default button texture instead of painting over it.
 */
final class TabStrip {

    private static final int GAP = 4;
    private static final int BTN_H = 18;
    private static final int ROW_STEP = BTN_H + 4;

    private final List<Component> labels = new ArrayList<>();
    private final List<Component> tips = new ArrayList<>();
    private final IntConsumer onSelect;
    private final int minButtonW;
    private final List<Button> buttons = new ArrayList<>();
    private int selected;

    TabStrip(int minButtonW, IntConsumer onSelect) {
        this.minButtonW = minButtonW;
        this.onSelect = onSelect;
    }

    TabStrip add(Component label, Component tip) {
        this.labels.add(label);
        this.tips.add(tip);
        return this;
    }

    /** {@code -1} leaves every tab active (used when another strip owns the selection). */
    void select(int index) {
        this.selected = index;
        for (int i = 0; i < this.buttons.size(); i++) this.buttons.get(i).active = i != index;
    }

    /**
     * Creates the buttons through {@code register} (the screen's {@code addRenderableWidget}) and
     * returns the y just below the last row. Buttons share the width of a row evenly.
     */
    int layout(Consumer<Button> register, int x, int y, int width) {
        this.buttons.clear();
        int perRow = Math.max(1, Math.min(this.labels.size(), (width + GAP) / (this.minButtonW + GAP)));
        int rowsNeeded = (this.labels.size() + perRow - 1) / perRow;
        int bw = (width - (perRow - 1) * GAP) / perRow;
        for (int i = 0; i < this.labels.size(); i++) {
            final int index = i;
            int col = i % perRow;
            int row = i / perRow;
            Button b = Button.builder(this.labels.get(i), btn -> {
                select(index);
                this.onSelect.accept(index);
            }).bounds(x + col * (bw + GAP), y + row * ROW_STEP, bw, BTN_H).build();
            b.setTooltip(Tooltip.create(this.tips.get(i)));
            register.accept(b);
            this.buttons.add(b);
        }
        select(this.selected);
        return y + rowsNeeded * ROW_STEP;
    }
}
