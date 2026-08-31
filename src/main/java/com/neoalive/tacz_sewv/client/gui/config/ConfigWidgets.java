package com.neoalive.tacz_sewv.client.gui.config;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public final class ConfigWidgets {

    private static final int SWITCH_W = 28;
    private static final int SWITCH_H = 12;
    private static final int ON_COLOR = 0xFF4FD1C5;
    private static final int OFF_COLOR = 0xFF3A4550;
    private static final int KNOB_COLOR = 0xFFE8ECF0;

    private ConfigWidgets() {}

    public static int switchWidth() {
        return SWITCH_W;
    }

    public static int switchHeight() {
        return SWITCH_H;
    }

    public static class OnOffSwitch extends AbstractWidget {

        private boolean value;
        private final Consumer<Boolean> onChange;

        public OnOffSwitch(int x, int y, boolean initial, Consumer<Boolean> onChange) {
            super(x, y, SWITCH_W, SWITCH_H, Component.empty());
            this.value = initial;
            this.onChange = onChange;
        }

        public boolean value() {
            return this.value;
        }

        public void setValue(boolean value) {
            this.value = value;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int track = this.value ? ON_COLOR : OFF_COLOR;
            g.fill(this.getX(), this.getY(), this.getX() + SWITCH_W, this.getY() + SWITCH_H, track);
            int knobX = this.value ? this.getX() + SWITCH_W - SWITCH_H + 2 : this.getX() + 2;
            g.fill(knobX, this.getY() + 2, knobX + SWITCH_H - 4, this.getY() + SWITCH_H - 2, KNOB_COLOR);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            this.value = !this.value;
            this.onChange.accept(this.value);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    public static class ValidatedEditBox extends EditBox {

        private static final int DEFAULT_MAX_LENGTH = 256;
        private static final int MULTILINE_MAX_LENGTH = 4096;

        private final int capacity;
        private boolean valid = true;
        private final Runnable onChange;

        public ValidatedEditBox(net.minecraft.client.gui.Font font, int x, int y, int w, int h,
                                Component label, boolean multiline, Runnable onChange) {
            super(font, x, y, w, h, label);
            this.capacity = multiline ? MULTILINE_MAX_LENGTH : DEFAULT_MAX_LENGTH;
            setMaxLength(this.capacity);
            this.onChange = onChange;
            setResponder(s -> this.onChange.run());
        }

        public void setDraftValue(String value) {
            setValue(value);
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            if (!this.valid) {
                int x = this.getX() - 1;
                int y = this.getY() - 1;
                g.fill(x, y, x + this.width + 2, y + this.height + 2, 0xFFFF5555);
            }
            super.renderWidget(g, mouseX, mouseY, partial);
        }
    }
}
