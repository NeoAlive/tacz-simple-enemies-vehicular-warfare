package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A row of tab buttons that wraps onto further rows and underlines the active one. The vanilla
 * button has no "selected" look, which is what made the old category rows hard to read.
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

    void select(int index) {
        this.selected = index;
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
                this.selected = index;
                this.onSelect.accept(index);
            }).bounds(x + col * (bw + GAP), y + row * ROW_STEP, bw, BTN_H).build();
            b.setTooltip(Tooltip.create(this.tips.get(i)));
            register.accept(b);
            this.buttons.add(b);
        }
        return y + rowsNeeded * ROW_STEP;
    }

    /** Drawn after the widgets: a coloured bar under the active tab. */
    void renderSelection(GuiGraphics g) {
        if (this.selected < 0 || this.selected >= this.buttons.size()) return;
        Button b = this.buttons.get(this.selected);
        g.fill(b.getX(), b.getY() + b.getHeight(), b.getX() + b.getWidth(), b.getY() + b.getHeight() + 2, 0xFFFFAA00);
    }
}
