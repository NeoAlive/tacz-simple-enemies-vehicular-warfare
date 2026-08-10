package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveDoctrine;

public class DoctrineEditorScreen extends Screen {

    private static final int MAX_POINTS = 20;
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 36;
    private static final int GAP = 8;
    private static final int STEP_BTN_W = 20;
    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 48;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLL_STEP = 18;

    private final int[] draftedAxes = new int[Doctrine.Axis.VALUES.length];
    private Button confirmButton;

    private final List<AxisRow> rows = new ArrayList<>();

    private int listLeft;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private int scrollOffset;
    private boolean draggingScrollbar;

    private record AxisRow(Button minus, Button plus, int index) {}

    public DoctrineEditorScreen() {
        super(Component.translatable("item.tacz_sewv.doctrine_ledger"));
    }

    @Override
    protected void init() {
        this.rows.clear();
        this.scrollOffset = 0;
        this.draggingScrollbar = false;

        this.listLeft = (this.width - PANEL_W) / 2;
        this.listTop = HEADER_H;
        this.listBottom = this.height - FOOTER_H;
        this.contentHeight = Doctrine.Axis.VALUES.length * PANEL_H
                + Math.max(0, Doctrine.Axis.VALUES.length - 1) * GAP;

        for (int i = 0; i < Doctrine.Axis.VALUES.length; i++) {
            final int axisIndex = i;

            Button minus = addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustAxis(axisIndex, -1))
                    .bounds(0, 0, STEP_BTN_W, 20).build());
            Button plus = addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustAxis(axisIndex, 1))
                    .bounds(0, 0, STEP_BTN_W, 20).build());

            this.rows.add(new AxisRow(minus, plus, axisIndex));
        }

        int centerBtnX = this.width / 2;
        int btnY = this.height - 32;

        this.confirmButton = addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.doctrine.confirm"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new PacketSaveDoctrine(this.draftedAxes));
            onClose();
        }).bounds(centerBtnX - 105, btnY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(centerBtnX + 5, btnY, 100, 20).build());

        layoutRows();
        updateConfirmButton();
    }

    private int viewportHeight() {
        return Math.max(0, this.listBottom - this.listTop);
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - viewportHeight());
    }

    private boolean needsScrollbar() {
        return this.contentHeight > viewportHeight();
    }

    private void setScrollOffset(int offset) {
        this.scrollOffset = Mth.clamp(offset, 0, maxScroll());
        layoutRows();
    }

    private void layoutRows() {
        for (int i = 0; i < this.rows.size(); i++) {
            AxisRow row = this.rows.get(i);
            int rowTop = this.listTop + i * (PANEL_H + GAP) - this.scrollOffset;
            int btnY = rowTop + 8;
            int btnMinusX = this.listLeft + PANEL_W - 12 - 20 - 30 - 20;
            int btnPlusX = this.listLeft + PANEL_W - 12 - 20;

            row.minus().setX(btnMinusX);
            row.minus().setY(btnY);
            row.plus().setX(btnPlusX);
            row.plus().setY(btnY);

            boolean visible = btnY >= this.listTop && btnY + 20 <= this.listBottom;
            row.minus().visible = visible;
            row.plus().visible = visible;
            row.minus().active = visible;
            row.plus().active = visible;
        }
    }

    private void adjustAxis(int index, int delta) {
        int oldVal = this.draftedAxes[index];
        int newVal = Math.max(-Doctrine.AXIS_LIMIT, Math.min(Doctrine.AXIS_LIMIT, oldVal + delta));

        if (newVal != oldVal) {
            int currentTotal = getTotalAllocated();
            int newTotal = currentTotal - Math.abs(oldVal) + Math.abs(newVal);

            if (newTotal <= MAX_POINTS) {
                this.draftedAxes[index] = newVal;
                updateConfirmButton();
            }
        }
    }

    private int getTotalAllocated() {
        int total = 0;
        for (int val : this.draftedAxes) {
            total += Math.abs(val);
        }
        return total;
    }

    private void updateConfirmButton() {
        this.confirmButton.active = (getTotalAllocated() == MAX_POINTS);
    }

    private int scrollbarX() {
        return this.listLeft + PANEL_W + 4;
    }

    private void renderScrollbar(GuiGraphics g) {
        if (!needsScrollbar()) return;

        int trackX = scrollbarX();
        int trackTop = this.listTop;
        int trackH = viewportHeight();
        g.fill(trackX, trackTop, trackX + SCROLLBAR_W, trackTop + trackH, 0x66000000);

        int max = maxScroll();
        int thumbH = Math.max(16, (int) ((float) trackH * trackH / this.contentHeight));
        int thumbTravel = trackH - thumbH;
        int thumbY = trackTop + (max == 0 ? 0 : (int) ((float) this.scrollOffset / max * thumbTravel));
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
    }

    private boolean scrollbarContains(double mouseX, double mouseY) {
        if (!needsScrollbar()) return false;
        int x = scrollbarX();
        return mouseX >= x && mouseX < x + SCROLLBAR_W
                && mouseY >= this.listTop && mouseY < this.listBottom;
    }

    private void scrollFromMouseY(double mouseY) {
        int trackH = viewportHeight();
        int thumbH = Math.max(16, (int) ((float) trackH * trackH / this.contentHeight));
        int thumbTravel = Math.max(1, trackH - thumbH);
        double rel = (mouseY - this.listTop - thumbH / 2.0) / thumbTravel;
        setScrollOffset((int) Math.round(rel * maxScroll()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int remaining = MAX_POINTS - getTotalAllocated();
        int color = remaining == 0 ? 0x55FF55 : 0xFFFFFF;
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.doctrine.remaining", remaining),
                this.width / 2, 28, color);

        g.enableScissor(this.listLeft, this.listTop, this.listLeft + PANEL_W, this.listBottom);
        String pendingAxisTip = null;
        for (AxisRow row : this.rows) {
            int y = this.listTop + row.index() * (PANEL_H + GAP) - this.scrollOffset;
            if (y + PANEL_H < this.listTop || y > this.listBottom) continue;

            g.fill(this.listLeft, y, this.listLeft + PANEL_W, y + PANEL_H, 0x44000000);
            g.renderOutline(this.listLeft, y, PANEL_W, PANEL_H, 0xFF444444);

            Doctrine.Axis axis = Doctrine.Axis.VALUES[row.index()];
            String name = Component.translatable("gui.tacz_sewv.doctrine.axis." + axis.key).getString();
            g.drawString(this.font, name, this.listLeft + 12, y + 7, 0xFFFFFF, true);

            String tipKey = "gui.tacz_sewv.doctrine.axis." + axis.key + ".tip";
            String tip = Component.translatable(tipKey).getString();
            String desc = tip.equals(tipKey) ? axis.description : tip;
            int maxDescWidth = PANEL_W - 90 - 12 - 10;
            String truncatedDesc = this.font.plainSubstrByWidth(desc, maxDescWidth);
            if (!truncatedDesc.equals(desc)) truncatedDesc += "...";
            g.drawString(this.font, truncatedDesc, this.listLeft + 12, y + 20, 0xFFAAAAAA, true);

            int value = this.draftedAxes[row.index()];
            String displayVal = (value > 0 ? "+" : "") + value;
            g.drawCenteredString(this.font, displayVal, this.listLeft + PANEL_W - 47, y + 14, 0xFFFFFF);

            if (mouseX >= this.listLeft && mouseX < this.listLeft + PANEL_W
                    && mouseY >= y && mouseY < y + PANEL_H
                    && mouseY >= this.listTop && mouseY < this.listBottom) {
                pendingAxisTip = tip.equals(tipKey) ? axis.description : tip;
            }
        }
        g.disableScissor();

        renderScrollbar(g);

        // Widgets (buttons) after panels so they sit on top; footer buttons stay outside the list.
        super.render(g, mouseX, mouseY, partialTick);

        if (pendingAxisTip != null) {
            g.renderTooltip(this.font, Component.literal(pendingAxisTip), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (needsScrollbar() && mouseY >= this.listTop && mouseY < this.listBottom) {
            setScrollOffset(this.scrollOffset - (int) Math.signum(delta) * SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && scrollbarContains(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            scrollFromMouseY(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar && button == 0) {
            scrollFromMouseY(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
