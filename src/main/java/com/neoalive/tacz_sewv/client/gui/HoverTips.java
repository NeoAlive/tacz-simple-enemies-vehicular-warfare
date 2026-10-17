package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * Tooltips that show on mouse hover only. Vanilla's {@code AbstractWidget.setTooltip} also shows a
 * widget's tooltip while it is <i>focused</i> once the last input was the keyboard — for a text box
 * that means the tooltip sits open under it the whole time you type. Text boxes therefore register
 * here instead, and the screen calls {@link #render} last in its own render.
 */
final class HoverTips {

    private record Entry(AbstractWidget widget, Component tip) {}

    private final List<Entry> entries = new ArrayList<>();

    /** Call at the top of {@code init()}: widgets are rebuilt on every resize. */
    void clear() {
        this.entries.clear();
    }

    <T extends AbstractWidget> T add(T widget, Component tip) {
        this.entries.add(new Entry(widget, tip));
        return widget;
    }

    /** Draws the tip of the widget under the pointer, if any. Returns true when one was drawn. */
    boolean render(GuiGraphics g, Font font, int mx, int my) {
        for (Entry e : this.entries) {
            if (e.widget().visible && e.widget().isMouseOver(mx, my)) {
                SheetTable.drawTip(g, font, List.of(e.tip()), mx, my);
                return true;
            }
        }
        return false;
    }
}
