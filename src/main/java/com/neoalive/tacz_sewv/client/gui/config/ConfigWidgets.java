package com.neoalive.tacz_sewv.client.gui.config;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
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

    private static final int BTN_FILL = 0xFF1B222B;
    private static final int BTN_FILL_HOVER = 0xFF24303C;
    private static final int BTN_FILL_SELECTED = 0xFF2A4A48;
    private static final int BTN_FILL_DISABLED = 0xFF151A20;
    private static final int BTN_BORDER = 0xFF2E3946;
    private static final int BTN_BORDER_ACCENT = 0xFF4FD1C5;
    private static final int BTN_TEXT = 0xFFE8ECF0;
    private static final int BTN_TEXT_MUTED = 0xFF8B98A5;

    private ConfigWidgets() {}

    public static int switchWidth() {
        return SWITCH_W;
    }

    public static int switchHeight() {
        return SWITCH_H;
    }

    /**
     * Dark flat button matching the Combined Arms Configuration panel (not vanilla
     * {@link net.minecraft.client.gui.components.Button}).
     */
    public static class FlatButton extends AbstractWidget {

        private final Runnable onPress;
        private boolean selected;
        private boolean accent;

        public FlatButton(int x, int y, int w, int h, Component message, Runnable onPress) {
            super(x, y, w, h, message);
            this.onPress = onPress;
        }

        public FlatButton selected(boolean selected) {
            this.selected = selected;
            return this;
        }

        public FlatButton accent(boolean accent) {
            this.accent = accent;
            return this;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            boolean hover = this.isHoveredOrFocused() && this.active;
            int fill = !this.active ? BTN_FILL_DISABLED
                    : this.selected ? BTN_FILL_SELECTED
                    : hover ? BTN_FILL_HOVER
                    : BTN_FILL;
            int border = (this.selected || (this.accent && this.active) || hover)
                    ? BTN_BORDER_ACCENT : BTN_BORDER;
            int text = this.active ? BTN_TEXT : BTN_TEXT_MUTED;

            int x0 = this.getX();
            int y0 = this.getY();
            int x1 = x0 + this.width;
            int y1 = y0 + this.height;
            g.fill(x0, y0, x1, y1, fill);
            g.fill(x0, y0, x1, y0 + 1, border);
            g.fill(x0, y1 - 1, x1, y1, border);
            g.fill(x0, y0, x0 + 1, y1, border);
            g.fill(x1 - 1, y0, x1, y1, border);

            var font = Minecraft.getInstance().font;
            int tw = font.width(this.getMessage());
            g.drawString(font, this.getMessage(),
                    x0 + (this.width - tw) / 2, y0 + (this.height - 8) / 2, text, false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (this.active) this.onPress.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
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
