package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The spreadsheet-style list shared by the pool editors: a status dot, column headers, zebra rows,
 * grid lines, a draggable scrollbar and hover tooltips, in the same look as the loadout manager.
 *
 * <p>It owns only presentation and selection. What a row means comes from the {@link Model}, so each
 * screen keeps its own data and quirks and this class never has to know them. Geometry is set by
 * {@link #layout}; every input method takes absolute screen coordinates, like the widgets around it.
 */
final class SheetTable {

    static final int ROW_H = 12;
    static final int HEAD_H = 12;
    static final int BAR_W = 6;
    private static final int DOT_W = 12;
    private static final int TIP_W = 190;
    private static final int TIP_MAX_LINES = 8;

    /** Row states, drawn as the leading dot. */
    static final int LIVE = 0;
    static final int OFF = 1;
    static final int BAD = 2;
    static final int WARN = 3;

    /**
     * One column. {@code width > 0} is fixed pixels; {@code width <= 0} is a flexible share of what is
     * left ({@code 0} counts as 1, {@code -3} as 3).
     */
    record Col(String key, int width, boolean numeric, Component head, Component tip) {}

    /** What the table shows; called per visible row per frame, so keep it cheap (cache inside). */
    interface Model {
        int size();

        String text(int row, int col);

        int state(int row);

        /** ARGB for a cell, or 0 for "use the row's default colour". */
        default int color(int row, int col) {
            return 0;
        }

        List<Component> tip(int row);
    }

    private final List<Col> cols;
    private final Model model;
    private final Component dotTip;
    private final int[] colW;
    private final int[] colX;

    private int left;
    private int headY;
    private int rows = 4;
    private int scroll;
    private int selected = -1;
    private boolean draggingBar;

    SheetTable(List<Col> cols, Model model, Component dotTip) {
        this.cols = cols;
        this.model = model;
        this.dotTip = dotTip;
        this.colW = new int[cols.size()];
        this.colX = new int[cols.size()];
    }

    // ---------------------------------------------------------------- geometry

    /** Rows that fit between {@code dataTop} and {@code below} pixels above the screen bottom. */
    static int rowsFor(int screenH, int dataTop, int below, int min, int max) {
        return Math.max(min, Math.min(max, (screenH - dataTop - below) / ROW_H));
    }

    /** Places the header at {@code headY}; {@code width} includes the scrollbar. */
    void layout(int left, int headY, int width, int rows) {
        this.left = left;
        this.headY = headY;
        this.rows = rows;
        int gridW = width - BAR_W - 2;
        int fixed = DOT_W;
        int flexUnits = 0;
        for (Col c : this.cols) {
            if (c.width() > 0) fixed += c.width();
            else flexUnits += Math.max(1, -c.width());
        }
        int flexTotal = Math.max(40 * Math.max(1, flexUnits), gridW - fixed);
        int x = DOT_W;
        for (int i = 0; i < this.cols.size(); i++) {
            Col c = this.cols.get(i);
            int w = c.width() > 0 ? c.width() : flexTotal * Math.max(1, -c.width()) / Math.max(1, flexUnits);
            this.colW[i] = w;
            this.colX[i] = x;
            x += w;
        }
        clampScroll();
    }

    int dataTop() {
        return this.headY + HEAD_H;
    }

    int bottom() {
        return dataTop() + this.rows * ROW_H;
    }

    int rows() {
        return this.rows;
    }

    private int gridW() {
        int last = this.cols.size() - 1;
        return last < 0 ? DOT_W : this.colX[last] + this.colW[last];
    }

    // ---------------------------------------------------------------- selection / scroll

    int selected() {
        return this.selected;
    }

    void clearSelection() {
        this.selected = -1;
    }

    void reset() {
        this.selected = -1;
        this.scroll = 0;
    }

    void select(int index) {
        this.selected = index;
        if (index < 0) return;
        if (index < this.scroll) this.scroll = index;
        else if (index >= this.scroll + this.rows) this.scroll = index - this.rows + 1;
        clampScroll();
    }

    private int maxScroll() {
        return Math.max(0, this.model.size() - this.rows);
    }

    void clampScroll() {
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
        if (this.selected >= this.model.size()) this.selected = this.model.size() - 1;
    }

    // ---------------------------------------------------------------- input

    private int rowAt(double mx, double my) {
        if (mx < this.left || mx >= this.left + gridW() || my < dataTop() || my >= bottom()) return -1;
        int row = (int) ((my - dataTop()) / ROW_H) + this.scroll;
        return row >= 0 && row < this.model.size() ? row : -1;
    }

    private boolean overBar(double mx, double my) {
        int bx = barX();
        return mx >= bx && mx < bx + BAR_W && my >= dataTop() && my < bottom();
    }

    private int barX() {
        return this.left + gridW() + 2;
    }

    private void dragBar(double my) {
        int max = maxScroll();
        if (max == 0) return;
        double frac = (my - dataTop()) / (double) (this.rows * ROW_H);
        this.scroll = (int) Math.round(Math.max(0, Math.min(1, frac)) * max);
    }

    /** True when the click landed on the sheet (bar or row) and was consumed. */
    boolean mouseClicked(double mx, double my) {
        if (overBar(mx, my)) {
            this.draggingBar = true;
            dragBar(my);
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            this.selected = row;
            return true;
        }
        return false;
    }

    boolean mouseDragged(double my) {
        if (!this.draggingBar) return false;
        dragBar(my);
        return true;
    }

    void mouseReleased() {
        this.draggingBar = false;
    }

    void mouseScrolled(double delta) {
        this.scroll -= (int) Math.signum(delta);
        clampScroll();
    }

    // ---------------------------------------------------------------- render

    void render(GuiGraphics g, Font font, int mx, int my) {
        int gw = gridW();
        int x0 = this.left;
        int top = dataTop();
        int bottom = bottom();
        g.fill(x0 - 1, this.headY - 1, x0 + gw + 1, bottom + 1, 0xAA000000);
        g.fill(x0, this.headY, x0 + gw, this.headY + HEAD_H, 0xFF2A2A2A);

        for (int i = 0; i < this.cols.size(); i++) {
            Col c = this.cols.get(i);
            int tx = c.numeric() ? x0 + this.colX[i] + this.colW[i] - 2 - font.width(c.head())
                    : x0 + this.colX[i] + 3;
            g.drawString(font, c.head(), tx, this.headY + 2, 0xFFE8E8E8, false);
        }

        int hover = rowAt(mx, my);
        int size = this.model.size();
        for (int i = 0; i < this.rows; i++) {
            int idx = i + this.scroll;
            int y = top + i * ROW_H;
            if (i % 2 == 1) g.fill(x0, y, x0 + gw, y + ROW_H, 0x14FFFFFF);
            if (idx >= size) continue;
            if (idx == hover) g.fill(x0, y, x0 + gw, y + ROW_H, 0x22FFFFFF);
            if (idx == this.selected) g.fill(x0, y, x0 + gw, y + ROW_H, 0x66FFAA00);

            int state = this.model.state(idx);
            g.fill(x0 + 4, y + 3, x0 + 9, y + 8, dotColor(state));
            int base = state == BAD ? 0xFFFF8888 : state == OFF ? 0xFF707070
                    : state == WARN ? 0xFFFFD27A : 0xFFFFFFFF;
            for (int k = 0; k < this.cols.size(); k++) {
                String text = font.plainSubstrByWidth(this.model.text(idx, k), this.colW[k] - 5);
                int custom = this.model.color(idx, k);
                int tx = this.cols.get(k).numeric() ? x0 + this.colX[k] + this.colW[k] - 2 - font.width(text)
                        : x0 + this.colX[k] + 3;
                g.drawString(font, text, tx, y + 2, custom != 0 ? custom : base, false);
            }
        }

        for (int i = 0; i < this.cols.size(); i++) {
            g.fill(x0 + this.colX[i], this.headY, x0 + this.colX[i] + 1, bottom, 0x33FFFFFF);
        }
        g.fill(x0, top - 1, x0 + gw, top, 0x99FFFFFF);
        g.fill(x0, bottom, x0 + gw, bottom + 1, 0x55FFFFFF);
        renderScrollbar(g);
    }

    private static int dotColor(int state) {
        return switch (state) {
            case LIVE -> 0xFF55FF55;
            case BAD -> 0xFFFF5555;
            case WARN -> 0xFFFFC04D;
            default -> 0xFF777777;
        };
    }

    private void renderScrollbar(GuiGraphics g) {
        int bx = barX();
        int trackH = this.rows * ROW_H;
        g.fill(bx, dataTop(), bx + BAR_W, dataTop() + trackH, 0x66000000);
        int max = maxScroll();
        if (max == 0) return;
        int thumbH = Math.max(8, trackH * this.rows / this.model.size());
        int thumbY = dataTop() + (trackH - thumbH) * this.scroll / max;
        g.fill(bx, thumbY, bx + BAR_W, thumbY + thumbH, this.draggingBar ? 0xFFDDDDDD : 0xFF999999);
    }

    // ---------------------------------------------------------------- tooltips

    /** Header cell explanation or the hovered row's detail lines; null when the pointer is elsewhere. */
    @Nullable
    List<Component> hoverTip(int mx, int my) {
        if (my >= this.headY && my < this.headY + HEAD_H && mx >= this.left && mx < this.left + gridW()) {
            if (mx < this.left + DOT_W) return List.of(this.dotTip);
            for (int i = 0; i < this.cols.size(); i++) {
                if (mx >= this.left + this.colX[i] && mx < this.left + this.colX[i] + this.colW[i]) {
                    return List.of(this.cols.get(i).tip());
                }
            }
            return null;
        }
        int row = rowAt(mx, my);
        return row >= 0 ? this.model.tip(row) : null;
    }

    /** Wrapped, line-capped tooltip: a long id list ends in "..." instead of overflowing the screen. */
    static void drawTip(GuiGraphics g, Font font, List<Component> lines, int mx, int my) {
        List<FormattedCharSequence> out = new ArrayList<>();
        boolean overflow = false;
        for (Component c : lines) {
            for (FormattedCharSequence s : font.split(c, TIP_W)) {
                if (out.size() >= TIP_MAX_LINES) {
                    overflow = true;
                    break;
                }
                out.add(s);
            }
        }
        if (out.isEmpty()) return;
        if (overflow) out.set(out.size() - 1, FormattedCharSequence.forward("...", Style.EMPTY));
        g.renderTooltip(font, out, mx, my);
    }
}
