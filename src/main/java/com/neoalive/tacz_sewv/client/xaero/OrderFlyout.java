package com.neoalive.tacz_sewv.client.xaero;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;

import com.neoalive.tacz_sewv.client.radial.OrderScales;
import com.neoalive.tacz_sewv.client.radial.WheelUi;

/**
 * The second level of the map's order menu. Xaero's right-click menu is flat, so each category row
 * opens this panel beside it on hover (or on click, when it stays pinned after Xaero closes its own
 * menu). Drawn in Xaero's colours so the two read as one menu.
 */
public final class OrderFlyout {

    /** Xaero's menu as it stands this frame: position, width, first visible slot. */
    public record Menu(int x, int y, int w, int scroll) {}

    private static final long HOVER_OPEN_MS = 120L;
    private static final long LEAVE_GRACE_MS = 300L;
    private static final long DETAIL_DELAY_MS = 200L;
    private static final float DETAIL_FADE_S = 0.22f;
    private static final int PAD = 4;
    private static final int ROW = XaeroTheme.LINE_H + 1;

    private static List<MapOrderCatalog.Category> categories = List.of();
    private static int firstSlot = -1;
    @Nullable
    private static UnitOrderOption.MapClick click;

    @Nullable
    private static MapOrderCatalog.Category open;
    private static boolean pinned;
    private static int px;
    private static int py;
    private static int pw;
    private static int ph;
    private static int hoverCat = -1;
    private static boolean needPlace;
    private static int anchorX;
    private static int anchorW;
    private static int anchorY;
    private static long hoverSince;
    private static long leftAt;
    private static int hotRow = -1;
    private static long hotSince;
    private static boolean fired;

    private OrderFlyout() {}

    /** Called as the menu is built: which slots are category rows, and where the map was clicked. */
    public static void prepare(List<MapOrderCatalog.Category> cats, int firstCategorySlot, UnitOrderOption.MapClick at) {
        categories = cats;
        firstSlot = firstCategorySlot;
        click = at;
        close();
    }

    /** A category row was clicked: Xaero closes its menu, so keep the flyout up on its own. */
    public static void pin(MapOrderCatalog.Category cat) {
        open = cat;
        pinned = true;
        needPlace = true;
        leftAt = 0L;
    }

    public static boolean active() {
        return open != null;
    }

    public static void close() {
        open = null;
        pinned = false;
        hoverCat = -1;
        hotRow = -1;
    }

    /** Hover tracking and drawing, once per frame. {@code menu} is null when Xaero's menu is closed. */
    public static void render(GuiGraphics g, Font font, int mx, int my, int sw, int sh, @Nullable Menu menu) {
        long now = System.currentTimeMillis();
        if (menu != null) {
            int slot = (my - menu.y()) / XaeroTheme.LINE_H + menu.scroll();
            boolean overMenu = mx >= menu.x() && mx < menu.x() + menu.w() && my >= menu.y();
            int cat = overMenu ? slot - firstSlot : -1;
            if (cat >= 0 && cat < categories.size()) {
                anchorX = menu.x();
                anchorW = menu.w();
                anchorY = menu.y() + (slot - menu.scroll()) * XaeroTheme.LINE_H;
                if (cat != hoverCat) {
                    hoverCat = cat;
                    hoverSince = now;
                }
                if (now - hoverSince >= HOVER_OPEN_MS && open != categories.get(cat)) {
                    open = categories.get(cat);
                    pinned = false;
                    place(font, sw, sh, anchorX, anchorW, anchorY);
                }
            } else {
                hoverCat = -1;
            }
        } else if (!pinned) {
            open = null;
        }
        if (open == null) return;
        if (needPlace) {
            if (anchorW == 0) {
                anchorX = sw / 2 - 60;
                anchorY = sh / 3;
            }
            place(font, sw, sh, anchorX, anchorW, anchorY);
            needPlace = false;
        }

        boolean inside = inside(mx, my);
        if (!pinned && menu != null && !inside && hoverCat < 0) {
            if (leftAt == 0L) leftAt = now;
            if (now - leftAt > LEAVE_GRACE_MS && !corridor(mx, my, menu)) {
                open = null;
                return;
            }
        } else {
            leftAt = 0L;
        }
        draw(g, font, mx, my, now, sw, sh);
    }

    public static boolean mouseClicked(double mx, double my, int button) {
        if (open == null) return false;
        if (!inside(mx, my)) {
            if (pinned) {
                close();
                return true;
            }
            return false;
        }
        MapOrderCatalog.Entry e = entryAt(my);
        if (e != null && button == 0 && e.enabled() && click != null) {
            UnitOrderOption.MapClick at = click;
            close();
            fired = true;
            e.run().run(at);
            return true;
        }
        return true;
    }

    /** Scrolling over a row with a value steps it, as on the wheel. */
    public static boolean mouseScrolled(double mx, double my, double delta) {
        if (open == null || !inside(mx, my)) return false;
        MapOrderCatalog.Entry e = entryAt(my);
        if (e != null && e.scale() != null) OrderScales.step(e.scale(), delta > 0 ? 1 : -1, Screen.hasShiftDown());
        return true;
    }

    public static boolean escape() {
        if (open == null) return false;
        close();
        return true;
    }

    private static void place(Font font, int sw, int sh, int anchorX, int anchorW, int rowY) {
        int w = 0;
        for (MapOrderCatalog.Entry e : open.entries()) {
            int vw = e.scale() != null ? font.width(valueText(e)) + 8 : 0;
            w = Math.max(w, font.width(e.label()) + vw);
        }
        w = Math.max(w, font.width(open.label()) + 8) + PAD * 2 + 3;
        int h = XaeroTheme.LINE_H + open.entries().size() * ROW + 2;
        int x = anchorX + anchorW;
        if (x + w > sw - 2) x = anchorX - w;
        int y = Math.max(2, Math.min(rowY, sh - h - 2));
        px = Math.max(2, x);
        py = y;
        pw = w;
        ph = h;
    }

    private static void draw(GuiGraphics g, Font font, int mx, int my, long now, int sw, int sh) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        XaeroTheme.panel(g, px, py, pw, ph, true);
        XaeroTheme.titleRow(g, font, px + 1, py + 1, pw - 2, XaeroTheme.LINE_H - 1, open.glyph() + " " + open.label());
        List<MapOrderCatalog.Entry> entries = open.entries();
        int row = inside(mx, my) ? rowAt(my) : -1;
        if (row != hotRow) {
            hotRow = row;
            hotSince = now;
        }
        for (int i = 0; i < entries.size(); i++) {
            MapOrderCatalog.Entry e = entries.get(i);
            int y = py + XaeroTheme.LINE_H + 1 + i * ROW;
            boolean hot = i == row && e.enabled();
            XaeroTheme.row(g, px + 1, y, pw - 2, ROW, hot, false);
            if (i > 0) XaeroTheme.separator(g, px + 3, y, pw - 6);
            g.fill(px + 2, y + 2, px + 3, y + ROW - 2, e.enabled() ? 0xFF000000 | e.accent() : XaeroTheme.DISABLED);
            XaeroTheme.text(g, font, e.label(), px + PAD + 3, y + 2, e.enabled() ? XaeroTheme.TEXT : XaeroTheme.DISABLED);
            if (e.scale() != null) {
                String v = valueText(e);
                XaeroTheme.text(g, font, v, px + pw - PAD - font.width(v), y + 2,
                        e.enabled() ? XaeroTheme.GOLD : XaeroTheme.DISABLED);
            }
        }
        g.pose().popPose();

        MapOrderCatalog.Entry hot = row >= 0 && row < entries.size() ? entries.get(row) : null;
        if (hot != null && !hot.wheelId().isEmpty() && Screen.hasControlDown() && now - hotSince >= DETAIL_DELAY_MS) {
            float t = Math.min(1f, (now - hotSince - DETAIL_DELAY_MS) / 1000f / DETAIL_FADE_S);
            if (!WheelUi.description(hot.wheelId()).isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 450);
                WheelUi.detail(g, font, sw, sh, WheelUi.ease(t), hot.label(), hot.wheelId());
                g.pose().popPose();
            }
        }
    }

    private static String valueText(MapOrderCatalog.Entry e) {
        int v = OrderScales.get(e.scale());
        return switch (e.scale()) {
            case PATROL, SEARCH, ALTITUDE -> I18n.get("gui.tacz_sewv.map.flyout.blocks", v);
            case HOLD, CEASE_FIRE -> v == 0 ? I18n.get("gui.tacz_sewv.map.flyout.forever") : I18n.get("gui.tacz_sewv.map.flyout.seconds", v);
        };
    }

    private static boolean inside(double mx, double my) {
        return open != null && mx >= px && mx < px + pw && my >= py && my < py + ph;
    }

    /** The strip between Xaero's menu and the flyout, so a diagonal move does not drop it. */
    private static boolean corridor(double mx, double my, Menu menu) {
        int left = Math.min(menu.x() + menu.w(), px);
        int right = Math.max(menu.x(), px + pw);
        return mx >= left && mx < right && my >= Math.min(menu.y(), py) && my < py + ph;
    }

    private static int rowAt(double my) {
        int r = (int) Math.floor((my - py - XaeroTheme.LINE_H - 1) / ROW);
        return r >= 0 && r < open.entries().size() ? r : -1;
    }

    @Nullable
    private static MapOrderCatalog.Entry entryAt(double my) {
        int r = rowAt(my);
        return r < 0 ? null : open.entries().get(r);
    }

    /** Whether the hovered flyout row has a value to scroll (legend context). */
    public static boolean hoveringValue(double mx, double my) {
        if (!inside(mx, my)) return false;
        MapOrderCatalog.Entry e = entryAt(my);
        return e != null && e.scale() != null;
    }

    /** An order was just issued from the flyout, so Xaero's own menu should close too. */
    public static boolean consumeFired() {
        boolean f = fired;
        fired = false;
        return f;
    }

    public static boolean pinnedOpen() {
        return pinned && open != null;
    }
}
