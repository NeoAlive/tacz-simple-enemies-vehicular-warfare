package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.client.DoctrineClient;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.DoctrineKnobs;
import com.neoalive.tacz_sewv.entity.ai.utility.DoctrinePresets;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveDoctrine;

public class DoctrineEditorScreen extends Screen {

    private static final int PANEL_W_PREF = 400;
    /** Right-hand Effects panel; shown beside the list when it fits, otherwise as a tooltip on the points line. */
    private static final int EFFECTS_W = 190;
    private static final int EFFECTS_GAP = 14;
    private static final int LIST_MIN_W = 260;
    /** Armour engagement ring before doctrine and tacticalScale, as Facts.preferredRange. */
    private static final double BASE_ARMOUR_RING = 40.0;
    private static final int PANEL_H = 36;
    private static final int GAP = 8;
    private static final int STEP_BTN_W = 20;
    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 72;
    private static final int PRESET_BTN_GAP = 4;
    /** Debounce for preview requests while the player is clicking +/-. */
    private static final long PREVIEW_DEBOUNCE_MS = 250L;

    private static final int SCROLLBAR_W = 6;
    private static final int SCROLL_STEP = 18;

    private final int[] draftedAxes = new int[Doctrine.Axis.VALUES.length];
    /** Points the ledger must spend, from the server's sync. */
    private final int budget = DoctrineClient.budget();
    private Button confirmButton;
    private boolean effectsBeside;
    private boolean previewDirty = true;
    private long previewChangedAt;

    private final List<AxisRow> rows = new ArrayList<>();

    private int listLeft;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private int scrollOffset;
    private boolean draggingScrollbar;

    private record AxisRow(Button minus, Button plus, int index) {}

    private int panelW() {
        if (this.effectsBeside) return Math.min(PANEL_W_PREF, this.width - EFFECTS_W - EFFECTS_GAP - 40);
        return GuiFit.panelW(PANEL_W_PREF, this.width);
    }

    public DoctrineEditorScreen() {
        super(Component.translatable("item.tacz_sewv.doctrine_ledger"));
        // Open on what is actually active, so a respec starts from the current doctrine instead of zeros.
        if (DoctrineClient.has()) {
            int[] saved = DoctrineClient.axes();
            System.arraycopy(saved, 0, this.draftedAxes, 0, Math.min(saved.length, this.draftedAxes.length));
        }
    }

    @Override
    protected void init() {
        this.rows.clear();
        this.scrollOffset = 0;
        this.draggingScrollbar = false;

        this.effectsBeside = this.width >= LIST_MIN_W + EFFECTS_W + EFFECTS_GAP + 40;
        int block = panelW() + (this.effectsBeside ? EFFECTS_GAP + EFFECTS_W : 0);
        this.listLeft = (this.width - block) / 2;
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

        int count = DoctrinePresets.ALL.size();
        int presetW = Math.min(76, (Math.min(this.width - 20, 420) - PRESET_BTN_GAP * (count - 1)) / count);
        int presetsX = this.width / 2 - (presetW * count + PRESET_BTN_GAP * (count - 1)) / 2;
        for (int i = 0; i < count; i++) {
            DoctrinePresets.Preset preset = DoctrinePresets.ALL.get(i);
            addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.doctrine.preset." + preset.key()),
                            b -> applyPreset(preset))
                    .bounds(presetsX + i * (presetW + PRESET_BTN_GAP), this.height - 58, presetW, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("gui.tacz_sewv.doctrine.preset." + preset.key() + ".tip")))
                    .build());
        }

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
            int btnMinusX = this.listLeft + panelW() - 12 - 20 - 30 - 20;
            int btnPlusX = this.listLeft + panelW() - 12 - 20;

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

            if (newTotal <= this.budget) {
                this.draftedAxes[index] = newVal;
                updateConfirmButton();
                markPreviewDirty();
            }
        }
    }

    private void applyPreset(DoctrinePresets.Preset preset) {
        System.arraycopy(preset.axes(), 0, this.draftedAxes, 0, this.draftedAxes.length);
        updateConfirmButton();
        markPreviewDirty();
    }

    private void markPreviewDirty() {
        this.previewDirty = true;
        this.previewChangedAt = net.minecraft.Util.getMillis();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.previewDirty && net.minecraft.Util.getMillis() - this.previewChangedAt >= PREVIEW_DEBOUNCE_MS) {
            this.previewDirty = false;
            NetworkHandler.CHANNEL.sendToServer(
                    new com.neoalive.tacz_sewv.network.PacketDoctrinePreview(this.draftedAxes.clone()));
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
        this.confirmButton.active = (getTotalAllocated() == this.budget);
    }

    private int scrollbarX() {
        return this.listLeft + panelW() + 4;
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

        int remaining = this.budget - getTotalAllocated();
        int color = remaining == 0 ? 0x55FF55 : 0xFFFFFF;
        Component remainingText = Component.translatable("gui.tacz_sewv.doctrine.remaining", remaining);
        g.drawCenteredString(this.font, remainingText, this.width / 2, 28, color);
        int remW = this.font.width(remainingText);
        boolean overRemaining = !this.effectsBeside && mouseY >= 26 && mouseY < 38
                && Math.abs(mouseX - this.width / 2) <= remW / 2;

        g.enableScissor(this.listLeft, this.listTop, this.listLeft + panelW(), this.listBottom);
        String pendingAxisTip = null;
        for (AxisRow row : this.rows) {
            int y = this.listTop + row.index() * (PANEL_H + GAP) - this.scrollOffset;
            if (y + PANEL_H < this.listTop || y > this.listBottom) continue;

            g.fill(this.listLeft, y, this.listLeft + panelW(), y + PANEL_H, 0x44000000);
            g.renderOutline(this.listLeft, y, panelW(), PANEL_H, 0xFF444444);

            Doctrine.Axis axis = Doctrine.Axis.VALUES[row.index()];
            String name = Component.translatable("gui.tacz_sewv.doctrine.axis." + axis.key).getString();
            g.drawString(this.font, name, this.listLeft + 12, y + 7, 0xFFFFFF, true);

            String tipKey = "gui.tacz_sewv.doctrine.axis." + axis.key + ".tip";
            String tip = Component.translatable(tipKey).getString();
            String desc = tip.equals(tipKey) ? axis.description : tip;
            int maxDescWidth = panelW() - 90 - 12 - 10;
            String truncatedDesc = this.font.plainSubstrByWidth(desc, maxDescWidth);
            if (!truncatedDesc.equals(desc)) truncatedDesc += "...";
            g.drawString(this.font, truncatedDesc, this.listLeft + 12, y + 20, 0xFFAAAAAA, true);

            int value = this.draftedAxes[row.index()];
            String displayVal = (value > 0 ? "+" : "") + value;
            g.drawCenteredString(this.font, displayVal, this.listLeft + panelW() - 47, y + 14, 0xFFFFFF);

            if (mouseX >= this.listLeft && mouseX < this.listLeft + panelW()
                    && mouseY >= y && mouseY < y + PANEL_H
                    && mouseY >= this.listTop && mouseY < this.listBottom) {
                pendingAxisTip = tip.equals(tipKey) ? axis.description : tip;
            }
        }
        g.disableScissor();

        renderScrollbar(g);

        List<Component> effects = effectLines();
        if (this.effectsBeside) renderEffects(g, effects);

        // Widgets (buttons) after panels so they sit on top; footer buttons stay outside the list.
        super.render(g, mouseX, mouseY, partialTick);

        if (pendingAxisTip != null) {
            g.renderTooltip(this.font, Component.literal(pendingAxisTip), mouseX, mouseY);
        } else if (overRemaining) {
            List<net.minecraft.util.FormattedCharSequence> tip = new ArrayList<>();
            for (Component line : effects) tip.add(line.getVisualOrderText());
            g.renderTooltip(this.font, tip, mouseX, mouseY);
        }
    }

    /**
     * What the drafted doctrine does, in the numbers the AI will use (DoctrineKnobs), so a player sees the change
     * before committing a ledger to it.
     */
    private List<Component> effectLines() {
        Doctrine d = Doctrine.ofAxes(this.draftedAxes);
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable(DoctrineClient.has()
                ? "gui.tacz_sewv.doctrine.effects" : "gui.tacz_sewv.doctrine.effects_none")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.budget", this.budget));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.temper",
                Component.translatable("gui.tacz_sewv.doctrine.temper."
                        + Doctrine.Temperament.of(Doctrine.cautionCentre(d)).name().toLowerCase(java.util.Locale.ROOT))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.ring",
                Math.round(com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale.of(BASE_ARMOUR_RING)
                        * DoctrineKnobs.ringScale(d))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.break_off",
                Math.round(100 * DoctrineKnobs.breakOffHealth(d))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.bail",
                Math.round(100 * DoctrineKnobs.bailHealth(d))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.replan",
                String.format(java.util.Locale.ROOT, "%.1f", DoctrineKnobs.commitTicks(d) / 20.0)));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.reaction",
                Math.round(100 * DoctrineKnobs.acquisitionScale(d))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.stalemate",
                Math.round(100 * DoctrineKnobs.stalemateScale(d))));
        out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.support",
                Math.round(100 * DoctrineKnobs.supportCooldownScale(d))));
        double t = DoctrineKnobs.armourPreference(d);
        out.add(Component.translatable(t > 0.0 ? "gui.tacz_sewv.doctrine.effect.target_armour"
                : t < 0.0 ? "gui.tacz_sewv.doctrine.effect.target_infantry" : "gui.tacz_sewv.doctrine.effect.target_none"));
        if (DoctrineKnobs.smokeOnHit(d)) out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.smoke"));
        if (DoctrineKnobs.creepsOnCourse(d)) out.add(Component.translatable("gui.tacz_sewv.doctrine.effect.creep"));

        // What the server's own scorer says these drafts would do, scenario by scenario.
        out.add(Component.translatable("gui.tacz_sewv.doctrine.preview").withStyle(net.minecraft.ChatFormatting.GOLD));
        if (!DoctrineClient.previewMatches(this.draftedAxes)) {
            out.add(Component.translatable("gui.tacz_sewv.doctrine.preview.pending"));
        } else {
            byte[] actions = DoctrineClient.previewActions();
            com.neoalive.tacz_sewv.entity.ai.utility.DoctrineScenarios.Scenario[] scenarios =
                    com.neoalive.tacz_sewv.entity.ai.utility.DoctrineScenarios.Scenario.VALUES;
            for (int i = 0; i < Math.min(actions.length, scenarios.length); i++) {
                com.neoalive.tacz_sewv.entity.ai.utility.Action action =
                        com.neoalive.tacz_sewv.network.PacketDoctrinePreview.action(actions[i]);
                if (action == null) continue;
                out.add(Component.translatable("gui.tacz_sewv.doctrine.preview.row",
                        Component.translatable("gui.tacz_sewv.doctrine.scenario." + scenarios[i].key),
                        Component.translatable("gui.tacz_sewv.doctrine.action." + action.key)));
            }
        }
        return out;
    }

    private void renderEffects(GuiGraphics g, List<Component> lines) {
        int x = this.listLeft + panelW() + EFFECTS_GAP;
        int y = this.listTop;
        int h = Math.min(this.listBottom - this.listTop, 8 + lines.size() * 12);
        g.fill(x, y, x + EFFECTS_W, y + h, 0x44000000);
        g.renderOutline(x, y, EFFECTS_W, h, 0xFF444444);
        int ly = y + 5;
        for (int i = 0; i < lines.size(); i++) {
            if (ly + 9 > y + h) break;
            String text = this.font.plainSubstrByWidth(lines.get(i).getString(), EFFECTS_W - 10);
            boolean header = lines.get(i).getStyle().getColor() != null;
            g.drawString(this.font, text, x + 5, ly, header ? 0xFFFFAA00 : 0xFFDDDDDD, false);
            ly += 12;
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
