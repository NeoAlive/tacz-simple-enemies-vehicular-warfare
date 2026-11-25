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
    /** Effects panel width: grows toward the preferred width when the screen has room, never below the minimum. */
    private static final int EFFECTS_W_MIN = 190;
    private static final int EFFECTS_W_PREF = 240;
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
    private static final int PRESET_ROW_H = 24;
    /** Debounce for preview requests while the player is clicking +/-. */
    private static final long PREVIEW_DEBOUNCE_MS = 250L;

    private static final int SCROLLBAR_W = 6;
    private static final int SCROLL_STEP = 18;

    private final int[] draftedAxes = new int[Doctrine.Axis.VALUES.length];
    /** Points the ledger must spend, from the server's sync. */
    private final int budget = DoctrineClient.budget();
    private Button confirmButton;
    private boolean effectsBeside;
    private int effectsW = EFFECTS_W_MIN;
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
        if (this.effectsBeside) return Math.min(PANEL_W_PREF, this.width - this.effectsW - EFFECTS_GAP - 40);
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

        this.effectsBeside = this.width >= LIST_MIN_W + EFFECTS_W_MIN + EFFECTS_GAP + 40;
        this.effectsW = Mth.clamp(this.width - LIST_MIN_W - EFFECTS_GAP - 40, EFFECTS_W_MIN, EFFECTS_W_PREF);
        int block = panelW() + (this.effectsBeside ? EFFECTS_GAP + this.effectsW : 0);
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

        // Buttons sized to their full labels and wrapped onto extra rows when the screen is narrow, so no name is
        // ever clipped; the axis list gives up the height.
        int count = DoctrinePresets.ALL.size();
        int maxRowW = this.width - 20;
        List<List<Integer>> presetRows = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int rowW = 0;
        int[] widths = new int[count];
        for (int i = 0; i < count; i++) {
            widths[i] = Math.min(maxRowW, this.font.width(Component.translatable(
                    "gui.tacz_sewv.doctrine.preset." + DoctrinePresets.ALL.get(i).key())) + 12);
            int add = widths[i] + (current.isEmpty() ? 0 : PRESET_BTN_GAP);
            if (!current.isEmpty() && rowW + add > maxRowW) {
                presetRows.add(current);
                current = new ArrayList<>();
                rowW = 0;
                add = widths[i];
            }
            current.add(i);
            rowW += add;
        }
        if (!current.isEmpty()) presetRows.add(current);
        for (int r = 0; r < presetRows.size(); r++) {
            List<Integer> row = presetRows.get(r);
            int w = PRESET_BTN_GAP * (row.size() - 1);
            for (int i : row) w += widths[i];
            int px = this.width / 2 - w / 2;
            int py = this.height - 58 - (presetRows.size() - 1 - r) * PRESET_ROW_H;
            for (int i : row) {
                DoctrinePresets.Preset preset = DoctrinePresets.ALL.get(i);
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.doctrine.preset." + preset.key()),
                                b -> applyPreset(preset))
                        .bounds(px, py, widths[i], 20)
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                Component.translatable("gui.tacz_sewv.doctrine.preset." + preset.key() + ".tip")))
                        .build());
                px += widths[i] + PRESET_BTN_GAP;
            }
        }
        this.listBottom = this.height - FOOTER_H - (presetRows.size() - 1) * PRESET_ROW_H;

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
        this.confirmButton.active = com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData.validTotal(
                getTotalAllocated(), this.budget);
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
        int color = com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData.validTotal(getTotalAllocated(), this.budget)
                ? 0x55FF55 : 0xFFFFFF;
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
            // Scaled down to fit before it is ever cut; the full text is the row's hover tooltip anyway.
            drawFit(g, desc, this.listLeft + 12, y + 20, panelW() - 90 - 12 - 10, 0xFFAAAAAA, false);

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

        List<EffectRow> effects = effectRows();
        if (this.effectsBeside) renderEffects(g, effects);

        // Widgets (buttons) after panels so they sit on top; footer buttons stay outside the list.
        super.render(g, mouseX, mouseY, partialTick);

        if (pendingAxisTip != null) {
            g.renderTooltip(this.font, Component.literal(pendingAxisTip), mouseX, mouseY);
        } else if (overRemaining) {
            g.renderTooltip(this.font, tooltipRows(effects), mouseX, mouseY);
        }
    }

    /**
     * One row of the Effects panel. {@code grade} is -1 (malus) .. 0 (neutral or balanced) .. +1 (benefit) and picks the
     * value colour; NaN leaves a descriptive row uncoloured. Headers span the panel and carry no value.
     */
    private record EffectRow(Component label, Component value, double grade, boolean header) {
        static EffectRow header(Component label) {
            return new EffectRow(label, Component.empty(), Double.NaN, true);
        }

        static EffectRow of(String key, Component value, double grade) {
            return new EffectRow(Component.translatable("gui.tacz_sewv.doctrine.row." + key), value, grade, false);
        }
    }

    private static final int ROW_H = 11;
    private static final int TEXT = 0xFFDDDDDD;
    private static final int HEADER = 0xFFFFAA00;
    private static final int MALUS = 0xFFE05A4F;
    private static final int BALANCED = 0xFFE8C547;
    private static final int BENEFIT = 0xFF5FD068;

    /** Red through yellow to green; grade is clamped to -1..1. */
    static int gradeColour(double grade) {
        if (Double.isNaN(grade)) return TEXT;
        double g = Math.max(-1.0, Math.min(1.0, grade));
        return g < 0 ? lerp(BALANCED, MALUS, -g) : lerp(BALANCED, BENEFIT, g);
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int gr = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    private static Component pct(double fraction) {
        return Component.translatable("gui.tacz_sewv.doctrine.value.percent", Math.round(100 * fraction));
    }

    /**
     * What the drafted doctrine does, in the numbers the AI will use (DoctrineKnobs), so a player sees the change
     * before committing a ledger to it. Green is survivability or responsiveness gained against neutral, red is the
     * same lost, yellow is neutral or a trade-off; every grade is the knob's offset from neutral over its full range.
     */
    private List<EffectRow> effectRows() {
        Doctrine d = Doctrine.ofAxes(this.draftedAxes);
        List<EffectRow> out = new ArrayList<>();
        out.add(EffectRow.header(Component.translatable(DoctrineClient.has()
                ? "gui.tacz_sewv.doctrine.effects" : "gui.tacz_sewv.doctrine.effects_none")));
        out.add(EffectRow.of("budget", Component.literal(String.valueOf(this.budget)),
                (this.budget - com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData.BASE_BUDGET)
                        / (double) (DoctrineKnobs.MAX_BUDGET - com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData.BASE_BUDGET)));
        out.add(EffectRow.of("temper", Component.translatable("gui.tacz_sewv.doctrine.temper."
                + Doctrine.Temperament.of(Doctrine.cautionCentre(d)).name().toLowerCase(java.util.Locale.ROOT)), Double.NaN));
        double ring = DoctrineKnobs.ringScale(d);
        out.add(EffectRow.of("ring", Component.translatable("gui.tacz_sewv.doctrine.value.metres",
                Math.round(com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale.of(BASE_ARMOUR_RING) * ring)),
                (ring - 1.0) / 0.35));
        double breakOff = DoctrineKnobs.breakOffHealth(d);
        out.add(EffectRow.of("break_off", pct(breakOff), (breakOff - DoctrineKnobs.BASE_BREAK_OFF) / 0.17));
        double bail = DoctrineKnobs.bailHealth(d);
        out.add(EffectRow.of("bail", pct(bail), (bail - DoctrineKnobs.BASE_BAIL) / 0.07));
        int commit = DoctrineKnobs.commitTicks(d);
        out.add(EffectRow.of("replan", Component.translatable("gui.tacz_sewv.doctrine.value.seconds",
                String.format(java.util.Locale.ROOT, "%.1f", commit / 20.0)),
                (DoctrineKnobs.BASE_COMMIT_TICKS - commit) / 14.0));
        double reaction = DoctrineKnobs.acquisitionScale(d);
        out.add(EffectRow.of("reaction", pct(reaction), (1.0 - reaction) / 0.4));
        double stalemate = DoctrineKnobs.stalemateScale(d);
        out.add(EffectRow.of("stalemate", pct(stalemate), (1.0 - stalemate) / 0.4));
        double support = DoctrineKnobs.supportCooldownScale(d);
        out.add(EffectRow.of("support", pct(support), (1.0 - support) / 0.5));
        double t = DoctrineKnobs.armourPreference(d);
        out.add(EffectRow.of("target", Component.translatable(t > 0.0 ? "gui.tacz_sewv.doctrine.value.target_armour"
                : t < 0.0 ? "gui.tacz_sewv.doctrine.value.target_infantry" : "gui.tacz_sewv.doctrine.value.target_none"),
                Double.NaN));
        boolean smoke = DoctrineKnobs.smokeOnHit(d);
        out.add(EffectRow.of("smoke", Component.translatable(smoke ? "gui.yes" : "gui.no"), smoke ? 1.0 : 0.0));
        // Creeping trades tempo for safety: always shown as a trade-off.
        boolean creep = DoctrineKnobs.creepsOnCourse(d);
        out.add(EffectRow.of("creep", Component.translatable(creep ? "gui.yes" : "gui.no"), 0.0));

        // What the server's own scorer says these drafts would do, scenario by scenario.
        out.add(EffectRow.header(Component.translatable("gui.tacz_sewv.doctrine.preview")));
        if (!DoctrineClient.previewMatches(this.draftedAxes)) {
            out.add(new EffectRow(Component.translatable("gui.tacz_sewv.doctrine.preview.pending"), Component.empty(),
                    Double.NaN, false));
        } else {
            byte[] actions = DoctrineClient.previewActions();
            com.neoalive.tacz_sewv.entity.ai.utility.DoctrineScenarios.Scenario[] scenarios =
                    com.neoalive.tacz_sewv.entity.ai.utility.DoctrineScenarios.Scenario.VALUES;
            for (int i = 0; i < Math.min(actions.length, scenarios.length); i++) {
                com.neoalive.tacz_sewv.entity.ai.utility.Action action =
                        com.neoalive.tacz_sewv.network.PacketDoctrinePreview.action(actions[i]);
                if (action == null) continue;
                out.add(new EffectRow(Component.translatable("gui.tacz_sewv.doctrine.scenario." + scenarios[i].key),
                        Component.translatable("gui.tacz_sewv.doctrine.action." + action.key), Double.NaN, false));
            }
        }
        return out;
    }

    /** Zebra-striped rows: label on the left, the value in its own cell column, coloured by grade. */
    private void renderEffects(GuiGraphics g, List<EffectRow> rows) {
        int w = this.effectsW;
        int x = this.listLeft + panelW() + EFFECTS_GAP;
        int y = this.listTop;
        int h = Math.min(this.listBottom - this.listTop, 2 + rows.size() * ROW_H);
        // The value column fits its widest value, so short values leave the labels room.
        int valueW = 0;
        for (EffectRow row : rows) {
            if (!row.header()) valueW = Math.max(valueW, this.font.width(row.value()));
        }
        valueW = Mth.clamp(valueW + 10, 34, (int) (w * 0.45F));
        int cellX = x + w - valueW;
        g.fill(x, y, x + w, y + h, 0x66000000);
        int ry = y + 1;
        int stripe = 0;
        for (EffectRow row : rows) {
            if (ry + ROW_H > y + h) break;
            if (row.header()) {
                g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0x55302008);
                drawFit(g, row.label().getString(), x + 4, ry + 2, w - 8, HEADER, false);
                stripe = 0;
            } else {
                g.fill(x + 1, ry, x + w - 1, ry + ROW_H, (stripe++ & 1) == 0 ? 0x22FFFFFF : 0x0AFFFFFF);
                boolean wide = row.value().getString().isEmpty();
                if (!wide) {
                    g.fill(cellX, ry, x + w - 1, ry + ROW_H, 0x33000000);
                    g.fill(cellX, ry, cellX + 1, ry + ROW_H, 0x55FFFFFF);
                    drawFit(g, row.value().getString(), x + w - 4, ry + 2, valueW - 7, gradeColour(row.grade()), true);
                }
                drawFit(g, row.label().getString(), x + 4, ry + 2, (wide ? w : w - valueW) - 8, TEXT, false);
            }
            ry += ROW_H;
        }
        g.renderOutline(x, y, w, h, 0xFF444444);
    }

    /**
     * Text that may not fit: drawn as is when it does, scaled down (to 70% at most) when it nearly does, and only
     * truncated past that. {@code right} anchors the text's right edge at {@code x}.
     */
    private void drawFit(GuiGraphics g, String text, int x, int y, int maxW, int colour, boolean right) {
        int tw = this.font.width(text);
        if (tw <= maxW) {
            g.drawString(this.font, text, right ? x - tw : x, y, colour, false);
            return;
        }
        float scale = Math.max(0.7F, maxW / (float) tw);
        String fitted = tw * scale <= maxW ? text : this.font.plainSubstrByWidth(text, (int) (maxW / scale));
        int fw = Math.round(this.font.width(fitted) * scale);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(right ? x - fw : x, y + (1.0F - scale) * 4.0F, 0.0F);
        pose.scale(scale, scale, 1.0F);
        g.drawString(this.font, fitted, 0, 0, colour, false);
        pose.popPose();
    }

    /** The same rows for the narrow-screen tooltip: label, then the coloured value. */
    private static List<net.minecraft.util.FormattedCharSequence> tooltipRows(List<EffectRow> rows) {
        List<net.minecraft.util.FormattedCharSequence> out = new ArrayList<>();
        for (EffectRow row : rows) {
            Component line = row.header()
                    ? row.label().copy().withStyle(s -> s.withColor(HEADER & 0xFFFFFF))
                    : row.value().getString().isEmpty() ? row.label()
                    : Component.empty().append(row.label()).append(": ").append(row.value().copy()
                            .withStyle(s -> s.withColor(gradeColour(row.grade()) & 0xFFFFFF)));
            out.add(line.getVisualOrderText());
        }
        return out;
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
