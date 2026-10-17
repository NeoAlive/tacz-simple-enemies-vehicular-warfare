package com.neoalive.tacz_sewv.client.gui.config;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;
import com.neoalive.tacz_sewv.config.ConfigValueType;

/**
 * The vanilla scrolling list (same base as the Controls screen) that every config page is drawn
 * in. The list owns the scissor, scrollbar and background, and each row places its widgets in
 * {@code render} every frame — so nothing can draw or be clicked outside the list area.
 */
public final class ConfigEntryList extends ContainerObjectSelectionList<ConfigEntryList.Row> {

    static final int ROW_H = 24;
    private static final int CONTROL_W = 120;
    private static final int RESET_W = 44;
    private static final int GAP = 4;
    private static final int TIP_W = 240;

    private final int rowWidth;

    public ConfigEntryList(Minecraft mc, int width, int height, int top, int bottom, int rowWidth) {
        super(mc, width, height, top, bottom, ROW_H);
        this.rowWidth = rowWidth;
    }

    /** Swaps the rows, keeping the scroll position (clamped) — used after any structural change. */
    public void setRows(List<Row> rows) {
        double scroll = getScrollAmount();
        setFocused(null);
        replaceEntries(rows);
        setScrollAmount(scroll);
    }

    @Override
    public int getRowWidth() {
        return this.rowWidth;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.width / 2 + this.rowWidth / 2 + 8;
    }

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}

    /** A centred heading, like the key-binds category rows. */
    public static final class SectionRow extends Row {

        private final Font font;
        private final Component label;

        public SectionRow(Font font, Component label) {
            this.font = font;
            this.label = label;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partial) {
            int x = left + width / 2 - this.font.width(this.label) / 2;
            g.drawString(this.font, this.label, x, top + height - this.font.lineHeight - 3, 0xFFFFFF);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** One or two buttons sharing the row width (home screen grid). */
    public static final class ButtonsRow extends Row {

        private final List<Button> buttons;

        public ButtonsRow(List<Button> buttons) {
            this.buttons = buttons;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partial) {
            int n = this.buttons.size();
            int w = (width - GAP * (n - 1)) / Math.max(1, n);
            if (n == 1) w = (width - GAP) / 2;
            for (int i = 0; i < n; i++) {
                Button b = this.buttons.get(i);
                b.setX(left + i * (w + GAP));
                b.setY(top + (height - 20) / 2);
                b.setWidth(w);
                b.render(g, mouseX, mouseY, partial);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.buttons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.buttons;
        }
    }

    /** Label, control, Reset. */
    public static final class ValueRow extends Row {

        private final Screen screen;
        private final Font font;
        private final ConfigSession session;
        private final ConfigEntry entry;
        private final AbstractWidget control;
        @Nullable private final EditBox box;
        private final Button reset;
        private final boolean advancedTag;

        ValueRow(Screen screen, Font font, ConfigSession session, ConfigEntry entry, AbstractWidget control,
                 @Nullable EditBox box, Runnable onReset, boolean advancedTag) {
            this.screen = screen;
            this.font = font;
            this.session = session;
            this.entry = entry;
            this.control = control;
            this.box = box;
            this.advancedTag = advancedTag;
            this.reset = Button.builder(Component.translatable("controls.reset"), b -> onReset.run())
                    .size(RESET_W, 20).build();
            boolean editable = session.editable(entry);
            this.control.active = editable;
            if (box != null) box.setEditable(editable);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partial) {
            boolean editable = this.session.editable(this.entry);
            int resetX = left + width - RESET_W;
            int ctrlX = resetX - GAP - CONTROL_W;
            int labelW = ctrlX - left - 6;

            MutableComponent label = Component.translatable(this.entry.labelKey());
            if (this.advancedTag) {
                label.append(Component.translatable("gui.tacz_sewv.config.advanced.tag")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            int color = !editable ? 0xA0A0A0 : this.session.unsaved(this.entry) ? 0xFFFF55 : 0xFFFFFF;
            FormattedCharSequence text = clip(label, labelW);
            g.drawString(this.font, text, left, top + (height - this.font.lineHeight) / 2 + 1, color);

            boolean hex = this.entry.type == ConfigValueType.HEX_COLOR;
            int ctrlW = hex ? CONTROL_W - 16 : CONTROL_W;
            this.control.setX(ctrlX);
            this.control.setWidth(ctrlW);
            this.control.setY(this.box != null ? top + (height - 18) / 2 : top + (height - 20) / 2);
            if (this.box != null) {
                this.box.setTextColor(this.session.valid(this.entry) ? 0xE0E0E0 : 0xFF5555);
            }
            this.control.render(g, mouseX, mouseY, partial);
            if (hex) drawSwatch(g, ctrlX + ctrlW + 3, top + (height - 12) / 2);

            this.reset.setX(resetX);
            this.reset.setY(top + (height - 20) / 2);
            this.reset.active = editable && !this.session.isDefault(this.entry);
            this.reset.render(g, mouseX, mouseY, partial);

            boolean overLabel = hovered && mouseX < ctrlX;
            boolean overControl = this.control.isMouseOver(mouseX, mouseY);
            if (overLabel || overControl) {
                this.screen.setTooltipForNextRenderPass(tooltip());
            }
        }

        private FormattedCharSequence clip(Component label, int maxW) {
            if (this.font.width(label) <= maxW) return label.getVisualOrderText();
            int ell = this.font.width(CommonComponents.ELLIPSIS);
            return Language.getInstance().getVisualOrder(
                    FormattedText.composite(
                            this.font.substrByWidth(label, Math.max(0, maxW - ell)), CommonComponents.ELLIPSIS));
        }

        private void drawSwatch(GuiGraphics g, int x, int y) {
            String hex = this.session.get(this.entry).replace("#", "");
            g.fill(x, y, x + 12, y + 12, 0xFF000000);
            if (hex.matches("[0-9A-Fa-f]{6}")) {
                g.fill(x + 1, y + 1, x + 11, y + 11, 0xFF000000 | Integer.parseInt(hex, 16));
            }
        }

        private List<FormattedCharSequence> tooltip() {
            List<FormattedCharSequence> out = new ArrayList<>();
            out.addAll(this.font.split(Component.translatable(this.entry.labelKey())
                    .withStyle(ChatFormatting.WHITE), TIP_W));
            out.addAll(this.font.split(Component.translatable(this.entry.tooltipKey())
                    .withStyle(ChatFormatting.GRAY), TIP_W));
            if (this.entry.type == ConfigValueType.ENUM) {
                String cur = this.session.get(this.entry);
                out.addAll(this.font.split(Component.translatable(
                        ConfigEntry.enumOptionTooltipKey(this.entry.key, cur)).withStyle(ChatFormatting.GRAY), TIP_W));
            }
            if (this.entry.min != null && this.entry.max != null) {
                out.add(Component.translatable("gui.tacz_sewv.config.tip.range",
                        num(this.entry.min), num(this.entry.max)).withStyle(ChatFormatting.DARK_GRAY)
                        .getVisualOrderText());
            }
            out.add(Component.translatable("gui.tacz_sewv.config.tip.default", defaultLabel())
                    .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
            if (!this.session.editable(this.entry)) {
                out.add(Component.translatable("gui.tacz_sewv.config.tip.locked")
                        .withStyle(ChatFormatting.RED).getVisualOrderText());
            }
            return out;
        }

        private Component defaultLabel() {
            String d = this.entry.defaultDraftString();
            return switch (this.entry.type) {
                case BOOLEAN, GAMERULE_BOOL -> CommonComponents.optionStatus(Boolean.parseBoolean(d));
                case ENUM -> Component.translatable(ConfigEntry.enumOptionLabelKey(this.entry.key, d));
                case MULTILINE_IDS -> Component.literal(String.valueOf(d.isBlank() ? 0 : d.split("\n").length));
                default -> Component.literal(d);
            };
        }

        private String num(double v) {
            return this.entry.type == ConfigValueType.INT ? String.valueOf((long) v) : String.valueOf(v);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.control, this.reset);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.control, this.reset);
        }
    }

    /** True when some entry is only shown while {@code e} is on (its {@code requiresKey}). */
    private static boolean gatesOthers(ConfigEntry e) {
        for (ConfigEntry other : ConfigRegistry.entries()) {
            if (e.key.equals(other.requiresKey)) return true;
        }
        return false;
    }

    /** Builds the row for one entry: the control matches its value type. */
    static ValueRow valueRow(Screen screen, Font font, ConfigSession session, ConfigEntry e,
                             Runnable onStructuralChange, Runnable openIdList, boolean advancedTag) {
        String cur = session.get(e);
        Runnable onReset = () -> {
            session.reset(e);
            onStructuralChange.run();
        };
        Runnable afterCycle = () -> {
            // A master toggle (Easy Mode) shows or hides the rows gated on it.
            if (gatesOthers(e)) onStructuralChange.run();
        };
        Component name = Component.translatable(e.labelKey());

        switch (e.type) {
            case BOOLEAN, GAMERULE_BOOL -> {
                CycleButton<Boolean> b = CycleButton.onOffBuilder(Boolean.parseBoolean(cur)).displayOnlyValue()
                        .create(0, 0, CONTROL_W, 20, name, (btn, v) -> {
                            session.set(e, v ? "true" : "false");
                            afterCycle.run();
                        });
                return new ValueRow(screen, font, session, e, b, null, onReset, advancedTag);
            }
            case ENUM -> {
                List<String> opts = e.enumOptions == null ? List.of(cur) : e.enumOptions;
                String initial = opts.stream().filter(o -> o.equalsIgnoreCase(cur)).findFirst().orElse(opts.get(0));
                CycleButton<String> b = CycleButton.<String>builder(
                                o -> Component.translatable(ConfigEntry.enumOptionLabelKey(e.key, o)))
                        .withValues(opts).withInitialValue(initial).displayOnlyValue()
                        .create(0, 0, CONTROL_W, 20, name, (btn, v) -> {
                            session.set(e, v);
                            afterCycle.run();
                        });
                return new ValueRow(screen, font, session, e, b, null, onReset, advancedTag);
            }
            case MULTILINE_IDS -> {
                int n = cur.isBlank() ? 0 : cur.split("\n").length;
                Button b = Button.builder(Component.translatable("gui.tacz_sewv.config.edit_list", n),
                        btn -> openIdList.run()).size(CONTROL_W, 20).build();
                return new ValueRow(screen, font, session, e, b, null, onReset, advancedTag);
            }
            default -> {
                EditBox box = new EditBox(font, 0, 0, CONTROL_W, 18, name);
                box.setMaxLength(256);
                box.setValue(cur);
                box.moveCursorToStart();
                if (e.type == ConfigValueType.INT) {
                    box.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
                } else if (e.type == ConfigValueType.DOUBLE) {
                    box.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
                } else if (e.type == ConfigValueType.HEX_COLOR) {
                    box.setFilter(s -> s.isEmpty() || s.matches("[#0-9A-Fa-f]*"));
                }
                box.setResponder(s -> session.set(e, s));
                return new ValueRow(screen, font, session, e, box, box, onReset, advancedTag);
            }
        }
    }
}
