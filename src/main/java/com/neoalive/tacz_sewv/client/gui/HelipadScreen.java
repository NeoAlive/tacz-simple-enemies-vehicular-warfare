package com.neoalive.tacz_sewv.client.gui;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.airport.HelipadClearance;
import com.neoalive.tacz_sewv.client.AirportPreview;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelipadAction;

/**
 * Helipad panel, in the same C2 palette as the runway editor: the cached clearance verdict, whether a
 * helicopter is currently snapped to the pad, a Check Clearance button and a world preview of the
 * volume the pad reserves. No geometry is edited, so there are no fields.
 */
public class HelipadScreen extends Screen {

    private static final int PANEL_W_PREF = 300;
    private static final int PAD = 10;
    private static final int ROW_H = 14;
    private static final int BTN_H = 20;

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

    /** Static like the runway preview: it is a survey aid meant to stay on while the GUI is shut. */
    private static boolean preview;

    private final BlockPos pos;
    private boolean cleared;
    private HelipadClearance.Status status;
    @Nullable private BlockPos blocker;
    private boolean occupied;

    private int panelLeft;
    private int panelTop;
    private int panelW;
    private int panelH;

    public HelipadScreen(BlockPos pos, boolean cleared, HelipadClearance.Status status,
                         @Nullable BlockPos blocker, boolean occupied) {
        super(Component.translatable("gui.tacz_sewv.helipad.title"));
        this.pos = pos;
        this.cleared = cleared;
        this.status = status;
        this.blocker = blocker;
        this.occupied = occupied;
    }

    /** A fresh server verdict for the pad this screen is showing. */
    public void update(boolean cleared, HelipadClearance.Status status, @Nullable BlockPos blocker,
                       boolean occupied) {
        this.cleared = cleared;
        this.status = status;
        this.blocker = blocker;
        this.occupied = occupied;
        syncPreview();
    }

    public BlockPos pos() {
        return this.pos;
    }

    @Override
    protected void init() {
        this.panelW = Math.min(PANEL_W_PREF, this.width - 20);
        // title, status, occupancy, note, buttons
        this.panelH = PAD + 14 + ROW_H * 3 + 6 + BTN_H + PAD;
        this.panelLeft = (this.width - this.panelW) / 2;
        this.panelTop = (this.height - this.panelH) / 2;
        syncPreview();
    }

    private int buttonsY() {
        return this.panelTop + this.panelH - PAD - BTN_H;
    }

    private int buttonLeft(int index) {
        int gap = 4;
        int w = buttonWidth();
        return this.panelLeft + PAD + index * (w + gap);
    }

    private int buttonWidth() {
        return (this.panelW - PAD * 2 - 4) / 2;
    }

    private void syncPreview() {
        if (!preview) {
            AirportPreview.clear();
            return;
        }
        // Green once cleared, red with the offending block when blocked, amber before any check.
        float r = 1.0f;
        float g = 0.75f;
        float b = 0.15f;
        if (this.cleared) {
            r = 0.2f; g = 0.9f; b = 0.3f;
        } else if (this.blocker != null) {
            r = 1.0f; g = 0.2f; b = 0.2f;
        }
        AirportPreview.set(HelipadClearance.volume(this.pos), this.blocker, r, g, b);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= buttonsY() && mouseY < buttonsY() + BTN_H) {
            for (int i = 0; i < 2; i++) {
                int x = buttonLeft(i);
                if (mouseX >= x && mouseX < x + buttonWidth()) {
                    click();
                    if (i == 0) {
                        preview = !preview;
                        syncPreview();
                    } else {
                        NetworkHandler.CHANNEL.sendToServer(new PacketHelipadAction(this.pos));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelTop + this.panelH, COL_BASE);
        frame(g, this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelTop + this.panelH);

        int left = this.panelLeft + PAD;
        int y = this.panelTop + PAD;
        g.drawString(this.font, this.title, left, y, COL_TEXT, false);
        y += 14;

        g.drawString(this.font, I18n.get("gui.tacz_sewv.helipad.clearance") + " ", left, y, COL_MUTED, false);
        Component verdict = clearanceLine();
        g.drawString(this.font, verdict,
                left + this.font.width(I18n.get("gui.tacz_sewv.helipad.clearance") + " "), y,
                this.cleared ? COL_GOOD : this.status == HelipadClearance.Status.OBSTRUCTED ? COL_BAD : COL_WARN,
                false);
        y += ROW_H;

        String occLabel = I18n.get("gui.tacz_sewv.helipad.occupancy") + " ";
        g.drawString(this.font, occLabel, left, y, COL_MUTED, false);
        g.drawString(this.font, I18n.get(this.occupied
                        ? "gui.tacz_sewv.helipad.occupied" : "gui.tacz_sewv.helipad.free"),
                left + this.font.width(occLabel), y, this.occupied ? COL_WARN : COL_GOOD, false);
        y += ROW_H;

        g.drawString(this.font, I18n.get("gui.tacz_sewv.helipad.note"), left, y, COL_MUTED, false);

        String[] labels = {
                I18n.get(preview ? "gui.tacz_sewv.airport.preview.on" : "gui.tacz_sewv.airport.preview.off"),
                I18n.get("gui.tacz_sewv.airport.check")};
        for (int i = 0; i < 2; i++) {
            int x = buttonLeft(i);
            int w = buttonWidth();
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= buttonsY() && mouseY < buttonsY() + BTN_H;
            g.fill(x, buttonsY(), x + w, buttonsY() + BTN_H, hover ? COL_HOVER : COL_SURFACE);
            g.fill(x, buttonsY() + BTN_H - 1, x + w, buttonsY() + BTN_H,
                    i == 0 && preview ? COL_ACCENT : COL_BORDER);
            g.drawCenteredString(this.font, labels[i], x + w / 2, buttonsY() + (BTN_H - 8) / 2, COL_TEXT);
        }
    }

    private Component clearanceLine() {
        if (this.cleared) return Component.translatable("gui.tacz_sewv.helipad.clear");
        if (this.status == HelipadClearance.Status.OBSTRUCTED) {
            return this.blocker == null
                    ? Component.translatable("gui.tacz_sewv.helipad.obstructed", 0, 0, 0)
                    : Component.translatable("gui.tacz_sewv.helipad.obstructed",
                            this.blocker.getX(), this.blocker.getY(), this.blocker.getZ());
        }
        return Component.translatable("gui.tacz_sewv.helipad.unchecked");
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
