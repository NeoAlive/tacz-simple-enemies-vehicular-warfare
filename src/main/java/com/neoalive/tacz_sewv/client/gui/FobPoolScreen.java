package com.neoalive.tacz_sewv.client.gui;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketFobData;

/**
 * FOB assignment terminal — click rows to assign/unassign entities in the master area.
 */
abstract class FobPoolScreen extends Screen {

    private static final int PANEL_W = 400;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int LIST_ROWS = 12;
    private static final int LIST_ROW_H = 14;
    private static final int BTN_H = 20;
    private static final int SCROLL_W = 18;

    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_GOOD = 0xFF7ED97E;
    private static final int COL_WARN = 0xFFE8C070;
    private static final int COL_ASSIGNED = 0xFF2F6E63;

    protected record FobRow(UUID id, String label, boolean assigned) {}

    protected FobGuiSnapshot snapshot;
    protected int scroll;
    protected List<FobRow> rows = List.of();

    private int panelLeft;
    private int panelTop;
    private int panelBottom;
    private int innerW;
    private int statusY;
    private int listTop;
    private int listBottom;
    private int actionRowY;
    private int footerY;
    private int closeY;

    protected FobPoolScreen(Component title, FobGuiSnapshot snapshot) {
        super(title);
        this.snapshot = snapshot;
    }

    public void applySnapshot(FobGuiSnapshot snapshot) {
        this.snapshot = snapshot;
        refreshRows();
        this.init();
    }

    protected abstract FobGuiSnapshot.GuiKind guiKind();

    protected abstract void refreshRows();

    protected abstract void toggleRow(int index);

    protected abstract Component listHint();

    protected abstract Component listCaption();

    protected List<FobButton> extraFooterButtons() {
        return List.of();
    }

    protected record FobButton(Component label, Runnable action, BooleanSupplier enabled) {
        FobButton(Component label, Runnable action) {
            this(label, action, () -> true);
        }
    }

    protected int assignedCount() {
        int n = 0;
        for (FobRow row : this.rows) {
            if (row.assigned()) n++;
        }
        return n;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        refreshRows();
        computeLayout();
    }

    private int statusStripH() {
        return this.snapshot.scrambleActive() ? ROW_H * 2 + 2 : ROW_H;
    }

    private void computeLayout() {
        int footerRows = extraFooterRows();
        int stripH = statusStripH();
        int panelH = PAD + 14 + stripH + ROW_GAP + 14 + 8 + LIST_ROWS * LIST_ROW_H + 8
                + BTN_H + footerRows * (BTN_H + ROW_GAP) + PAD + BTN_H + PAD;
        this.panelLeft = (this.width - PANEL_W) / 2;
        this.panelTop = Math.max(8, (this.height - panelH) / 2);
        this.panelBottom = this.panelTop + panelH;
        this.innerW = PANEL_W - PAD * 2;
        this.statusY = this.panelTop + PAD + 14;
        this.listTop = this.statusY + stripH + ROW_GAP + 14 + 8;
        this.listBottom = this.listTop + LIST_ROWS * LIST_ROW_H;
        this.actionRowY = this.listBottom + 8;
        this.footerY = this.actionRowY + BTN_H + ROW_GAP;
        this.closeY = this.panelBottom - PAD - BTN_H;
    }

    private int extraFooterRows() {
        int count = extraFooterButtons().size();
        if (count == 0) return 0;
        return (count + 1) / 2;
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    private void requestLayoutCheck() {
        NetworkHandler.CHANNEL.sendToServer(
                PacketFobData.request(this.snapshot.anchorPos(), guiKind()));
    }

    private int buttonLeft(int index, int cols, int rowWidth) {
        int gap = 4;
        int w = (rowWidth - (cols - 1) * gap) / cols;
        return this.panelLeft + PAD + index * (w + gap);
    }

    private int buttonWidth(int cols, int rowWidth) {
        return (rowWidth - (cols - 1) * 4) / cols;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int left = this.panelLeft + PAD;
        int listW = this.innerW - SCROLL_W;

        if (mouseX >= left && mouseX < left + listW
                && mouseY >= this.listTop && mouseY < this.listBottom) {
            int row = (int) ((mouseY - this.listTop) / LIST_ROW_H) + this.scroll;
            if (row >= 0 && row < this.rows.size()) {
                toggleRow(row);
                click();
                return true;
            }
        }

        if (mouseX >= left + listW && mouseX < left + this.innerW
                && mouseY >= this.listTop && mouseY < this.listTop + BTN_H) {
            if (this.scroll > 0) {
                this.scroll--;
                click();
            }
            return true;
        }
        if (mouseX >= left + listW && mouseX < left + this.innerW
                && mouseY >= this.listBottom - BTN_H && mouseY < this.listBottom) {
            if (this.scroll + LIST_ROWS < this.rows.size()) {
                this.scroll++;
                click();
            }
            return true;
        }

        if (hitButton(mouseX, mouseY, this.actionRowY, 1, 0, this::requestLayoutCheck)) {
            return true;
        }

        List<FobButton> extras = extraFooterButtons();
        int frow = 0;
        for (int i = 0; i < extras.size(); i += 2) {
            int y = this.footerY + frow * (BTN_H + ROW_GAP);
            if (hitButton(mouseX, mouseY, y, 2, 0, extras.get(i).action(), extras.get(i).enabled())) {
                return true;
            }
            if (i + 1 < extras.size()
                    && hitButton(mouseX, mouseY, y, 2, 1, extras.get(i + 1).action(),
                            extras.get(i + 1).enabled())) {
                return true;
            }
            frow++;
        }

        int closeW = 100;
        int closeX = this.panelLeft + (PANEL_W - closeW) / 2;
        if (mouseX >= closeX && mouseX < closeX + closeW
                && mouseY >= this.closeY && mouseY < this.closeY + BTN_H) {
            click();
            onClose();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean hitButton(double mouseX, double mouseY, int y, int cols, int index,
                              Runnable action, BooleanSupplier enabled) {
        int w = buttonWidth(cols, this.innerW);
        int x = buttonLeft(index, cols, this.innerW);
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H) {
            if (enabled.getAsBoolean()) {
                action.run();
                click();
            }
            return true;
        }
        return false;
    }

    private boolean hitButton(double mouseX, double mouseY, int y, int cols, int index,
                              Runnable action) {
        return hitButton(mouseX, mouseY, y, cols, index, action, () -> true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + LIST_ROWS < this.rows.size()) this.scroll++;
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelBottom, COL_BASE);
        frame(g, this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelBottom);

        int left = this.panelLeft + PAD;
        g.drawString(this.font, this.title, left, this.panelTop + PAD, COL_TEXT, false);
        String kindTag = guiKind() == FobGuiSnapshot.GuiKind.COMMAND
                ? I18n.get("gui.tacz_sewv.fob.tag.command")
                : I18n.get("gui.tacz_sewv.fob.tag.parking");
        g.drawString(this.font, kindTag,
                this.panelLeft + PANEL_W - PAD - this.font.width(kindTag),
                this.panelTop + PAD, COL_ACCENT, false);

        renderStatusStrip(g, left);
        renderList(g, left, mouseX, mouseY);
        renderButtonRow(g, left, mouseX, mouseY, this.actionRowY, 1,
                new FobButton(Component.translatable("gui.tacz_sewv.fob.check_layout"),
                        this::requestLayoutCheck));

        List<FobButton> extras = extraFooterButtons();
        int frow = 0;
        for (int i = 0; i < extras.size(); i += 2) {
            int y = this.footerY + frow * (BTN_H + ROW_GAP);
            FobButton a = extras.get(i);
            FobButton b = i + 1 < extras.size() ? extras.get(i + 1) : null;
            if (b == null) {
                renderButtonRow(g, left, mouseX, mouseY, y, 1, a);
            } else {
                renderButtonRow(g, left, mouseX, mouseY, y, 2, a, b);
            }
            frow++;
        }

        int closeW = 100;
        int closeX = this.panelLeft + (PANEL_W - closeW) / 2;
        renderFlatButton(g, mouseX, mouseY,
                closeX, this.closeY, closeW, BTN_H,
                Component.translatable("gui.done").getString(), true, false);

        g.drawString(this.font, listHint().getString(), left,
                this.closeY - ROW_H, COL_MUTED, false);
    }

    private void renderStatusStrip(GuiGraphics g, int left) {
        int stripH = statusStripH();
        g.fill(left, this.statusY, left + this.innerW, this.statusY + stripH, COL_SURFACE);
        frame(g, left, this.statusY, left + this.innerW, this.statusY + stripH);

        String layout = this.snapshot.valid()
                ? I18n.get("gui.tacz_sewv.fob.valid")
                : this.snapshot.invalidReason();
        g.drawString(this.font, layout, left + 4, this.statusY + 4,
                this.snapshot.valid() ? COL_GOOD : COL_BAD, false);

        String threat = I18n.get("gui.tacz_sewv.fob.threat_short", this.snapshot.threatScore());
        g.drawString(this.font, threat,
                left + this.innerW / 2 - this.font.width(threat) / 2, this.statusY + 4,
                this.snapshot.threatScore() > 0 ? COL_WARN : COL_MUTED, false);

        if (guiKind() == FobGuiSnapshot.GuiKind.COMMAND) {
            String cmd = this.snapshot.fobCommandActive()
                    ? I18n.get("gui.tacz_sewv.fob.command_on")
                    : I18n.get("gui.tacz_sewv.fob.command_off");
            g.drawString(this.font, cmd,
                    left + this.innerW - 4 - this.font.width(cmd), this.statusY + 4,
                    this.snapshot.fobCommandActive() ? COL_ACCENT : COL_MUTED, false);
        }

        if (this.snapshot.scrambleActive()) {
            g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.fob.scramble_active"),
                    left + this.innerW / 2, this.statusY + ROW_H + 2, COL_BAD);
        }
    }

    private void renderList(GuiGraphics g, int left, int mouseX, int mouseY) {
        g.drawString(this.font, listCaption().getString(), left,
                this.statusY + statusStripH() + ROW_GAP + 2, COL_MUTED, false);

        int listW = this.innerW - SCROLL_W;
        g.fill(left, this.listTop, left + listW, this.listBottom, COL_SURFACE);
        frame(g, left, this.listTop, left + listW, this.listBottom);

        if (this.rows.isEmpty()) {
            g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.fob.list_empty"),
                    left + listW / 2, this.listTop + LIST_ROWS * LIST_ROW_H / 2 - 4, COL_MUTED);
        }

        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = i + this.scroll;
            if (idx >= this.rows.size()) break;
            FobRow row = this.rows.get(idx);
            int y = this.listTop + i * LIST_ROW_H;
            boolean hover = mouseX >= left && mouseX < left + listW
                    && mouseY >= y && mouseY < y + LIST_ROW_H;
            if (row.assigned()) {
                g.fill(left + 1, y + 1, left + listW - 1, y + LIST_ROW_H - 1, COL_ASSIGNED);
            } else if (hover) {
                g.fill(left + 1, y + 1, left + listW - 1, y + LIST_ROW_H - 1, COL_HOVER);
            }
            String mark = row.assigned()
                    ? I18n.get("gui.tacz_sewv.fob.row_assigned")
                    : I18n.get("gui.tacz_sewv.fob.row_unassigned");
            g.drawString(this.font, mark + " " + row.label(), left + 6, y + 3,
                    row.assigned() ? COL_TEXT : COL_MUTED, false);
        }

        int scrollX = left + listW + 2;
        renderFlatButton(g, mouseX, mouseY, scrollX, this.listTop, SCROLL_W - 2, BTN_H, "▲",
                this.scroll > 0, false);
        renderFlatButton(g, mouseX, mouseY, scrollX, this.listBottom - BTN_H, SCROLL_W - 2, BTN_H, "▼",
                this.scroll + LIST_ROWS < this.rows.size(), false);
    }

    private void renderButtonRow(GuiGraphics g, int left, int mouseX, int mouseY, int y, int cols,
                                 FobButton... buttons) {
        for (int i = 0; i < buttons.length; i++) {
            int w = buttonWidth(cols, this.innerW);
            int x = buttonLeft(i, cols, this.innerW);
            FobButton btn = buttons[i];
            boolean accent = guiKind() == FobGuiSnapshot.GuiKind.COMMAND
                    && this.snapshot.fobCommandActive()
                    && btn.label().getString().contains(
                            I18n.get("gui.tacz_sewv.fob.command_on"));
            renderFlatButton(g, mouseX, mouseY, x, y, w, BTN_H, btn.label().getString(),
                    btn.enabled().getAsBoolean(), accent);
        }
    }

    private void renderFlatButton(GuiGraphics g, int mouseX, int mouseY,
                                  int x, int y, int w, int h, String label,
                                  boolean enabled, boolean accentBar) {
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? COL_HOVER : COL_SURFACE);
        g.fill(x, y + h - 1, x + w, y + h, accentBar ? COL_ACCENT : COL_BORDER);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, enabled ? COL_TEXT : COL_MUTED);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y0 + 1, COL_BORDER);
        g.fill(x0, y1 - 1, x1, y1, COL_BORDER);
        g.fill(x0, y0, x0 + 1, y1, COL_BORDER);
        g.fill(x1 - 1, y0, x1, y1, COL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
