package com.neoalive.tacz_sewv.client.gui;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.airport.RunwaySlots;
import com.neoalive.tacz_sewv.airport.StaticCarrierAirports;
import com.neoalive.tacz_sewv.client.AirportPreview;
import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketCarrierOpsAction;

/**
 * Carrier plane-ops terminal — AirportScreen C2 look without AABB / clearance / slot sliders.
 * Strip geometry is baked from Neo Arms LandingLine while the hull is STATIC.
 */
public class CarrierOpsScreen extends Screen {

    private static final int PANEL_W_PREF = 360;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int DIAGRAM_H = 36;
    private static final int BTN_H = 20;

    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;
    private static final int COL_GOOD = 0xFF7ED97E;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_PAVEMENT = 0xFF232B34;
    private static final int COL_SLOT = 0xFF2F6E63;
    private static final int COL_SLOT_EDGE = 0xFF4FD1C5;
    private static final int COL_TAKEOFF = 0xFF7A5A2A;
    private static final int COL_TAKEOFF_EDGE = 0xFFD9A65A;
    private static final int COL_THRESHOLD = 0xFFE8ECF0;

    private final int carrierEntityId;
    private final NeoArmsCarrierAccess.Strip strip;
    private final RunwaySlots slots;
    private final boolean staticMode;

    private static boolean preview;

    private int panelLeft;
    private int panelTop;
    private int panelBottom;
    private int panelW;
    private int innerW;
    private int infoY;
    private int diagramY;
    private int legendY;
    private int buttonsY;
    private int statusY;
    @Nullable private Component statusMessage;
    private int statusColor = COL_MUTED;

    public CarrierOpsScreen(int carrierEntityId, NeoArmsCarrierAccess.Strip strip, boolean staticMode) {
        super(Component.translatable("gui.tacz_sewv.carrier_ops.title"));
        this.carrierEntityId = carrierEntityId;
        this.strip = strip;
        this.staticMode = staticMode;
        this.slots = StaticCarrierAirports.toAirport(strip).slots();
    }

    @Override
    protected void init() {
        int panelH = PAD + 16 + 22 + 6 + DIAGRAM_H + 14 + 11 + BTN_H + 18 + PAD;
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.panelLeft = (this.width - this.panelW) / 2;
        this.panelTop = Math.max(0, (this.height - panelH) / 2);
        this.panelBottom = this.panelTop + panelH;
        this.innerW = this.panelW - PAD * 2;
        this.infoY = this.panelTop + PAD + 16;
        this.diagramY = this.infoY + 22;
        this.legendY = this.diagramY + DIAGRAM_H + 3;
        this.buttonsY = this.legendY + 14;
        this.statusY = this.buttonsY + BTN_H + 6;
        syncPreview();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= this.buttonsY && mouseY < this.buttonsY + BTN_H) {
            int w = buttonWidth();
            for (int i = 0; i < 2; i++) {
                int x = buttonLeft(i);
                if (mouseX < x || mouseX >= x + w) continue;
                click();
                if (i == 0) {
                    preview = !preview;
                    syncPreview();
                } else if (this.staticMode) {
                    NetworkHandler.CHANNEL.sendToServer(
                            new PacketCarrierOpsAction(this.carrierEntityId,
                                    PacketCarrierOpsAction.ACTION_DEPLOY));
                    this.statusMessage = Component.translatable("gui.tacz_sewv.carrier_ops.deploy_sent");
                    this.statusColor = COL_ACCENT;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int buttonLeft(int index) {
        int w = buttonWidth();
        return this.panelLeft + PAD + index * (w + 4);
    }

    private int buttonWidth() {
        return (this.innerW - 4) / 2;
    }

    private void syncPreview() {
        if (!preview || this.slots == null) {
            AirportPreview.clear();
            return;
        }
        AABB area = this.slots.area();
        AirportPreview.set(area, null, 0.2f, 0.9f, 0.3f);
        double takeoff = this.slots.takeoffBuffer();
        Vec3 thr = this.strip.threshold();
        double rad = Math.toRadians(this.strip.headingDeg());
        double dx = Math.sin(rad);
        double dz = Math.cos(rad);
        // Slot region = whole strip minus takeoff buffer at the far end.
        double usable = this.slots.usableLength();
        AirportPreview.setSlotStart(new AABB(
                Math.min(thr.x, thr.x + dx * usable) - this.strip.width() / 2.0,
                thr.y,
                Math.min(thr.z, thr.z + dz * usable) - this.strip.width() / 2.0,
                Math.max(thr.x, thr.x + dx * usable) + this.strip.width() / 2.0,
                thr.y + 1.0,
                Math.max(thr.z, thr.z + dz * usable) + this.strip.width() / 2.0));
    }

    private void click() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelBottom, COL_BASE);
        frame(g, this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelBottom);

        int left = this.panelLeft + PAD;
        g.drawString(this.font, this.title, left, this.panelTop + PAD - 2, COL_TEXT, false);
        String id = AirportClearance.airportId(
                net.minecraft.core.BlockPos.of(this.carrierEntityId & 0xFFFFFFFFL));
        g.drawString(this.font, id, left + this.innerW - this.font.width(id),
                this.panelTop + PAD - 2, COL_ACCENT, false);

        Component mode = Component.translatable(this.staticMode
                ? "gui.tacz_sewv.carrier_ops.mode_static"
                : "gui.tacz_sewv.carrier_ops.mode_dynamic");
        g.drawString(this.font, mode, left, this.infoY, this.staticMode ? COL_GOOD : COL_BAD, false);
        String dims = this.strip.length() + " × " + this.strip.width()
                + "  ·  " + this.slots.capacity() + " slots";
        g.drawString(this.font, dims, left, this.infoY + 11, COL_MUTED, false);

        renderDiagram(g);
        renderLegend(g);
        renderButtons(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);

        if (this.statusMessage != null) {
            g.drawString(this.font, this.statusMessage, left, this.statusY, this.statusColor, false);
        } else if (!this.staticMode) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.carrier_ops.need_static"),
                    left, this.statusY, COL_BAD, false);
        }
    }

    private void renderDiagram(GuiGraphics g) {
        int left = this.panelLeft + PAD;
        int right = left + this.innerW;
        int top = this.diagramY;
        int bottom = top + DIAGRAM_H;
        g.fill(left, top, right, bottom, COL_PAVEMENT);
        frame(g, left, top, right, bottom);

        RunwaySlots slots = this.slots;
        double scale = this.innerW / (double) Math.max(1, slots.length());
        int lane0 = top + 6;
        int lane1 = bottom - 6;

        int toStart = left + (int) Math.round(slots.usableLength() * scale);
        g.fill(toStart, lane0, right, lane1, COL_TAKEOFF);
        g.fill(toStart, lane0, toStart + 1, lane1, COL_TAKEOFF_EDGE);

        for (RunwaySlots.Slot slot : slots.slots()) {
            int s0 = left + (int) Math.round(slot.index() * (slots.slotLength() + slots.bufferLength()) * scale);
            int s1 = s0 + (int) Math.round(slots.slotLength() * scale);
            g.fill(s0, lane0, s1, lane1, COL_SLOT);
            g.fill(s0, lane0, s0 + 1, lane1, COL_SLOT_EDGE);
        }
        g.fill(left, lane0, left + 2, lane1, COL_THRESHOLD);
    }

    private void renderLegend(GuiGraphics g) {
        int left = this.panelLeft + PAD;
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.carrier_ops.legend.slot"),
                left, this.legendY, COL_SLOT_EDGE, false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.carrier_ops.legend.takeoff"),
                left + this.innerW / 2, this.legendY, COL_TAKEOFF_EDGE, false);
    }

    private void renderButtons(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {
                preview ? "gui.tacz_sewv.airport.preview.on" : "gui.tacz_sewv.airport.preview.off",
                "gui.tacz_sewv.airport.deploy"
        };
        for (int i = 0; i < 2; i++) {
            int x = buttonLeft(i);
            int w = buttonWidth();
            boolean hover = mouseX >= x && mouseX < x + w
                    && mouseY >= this.buttonsY && mouseY < this.buttonsY + BTN_H;
            boolean inert = i == 1 && !this.staticMode;
            int fill = inert ? COL_BORDER : (hover ? COL_HOVER : COL_SURFACE);
            g.fill(x, this.buttonsY, x + w, this.buttonsY + BTN_H, fill);
            g.fill(x, this.buttonsY + BTN_H - 1, x + w, this.buttonsY + BTN_H, COL_BORDER);
            int textCol = inert ? COL_MUTED : COL_TEXT;
            g.drawCenteredString(this.font, Component.translatable(labels[i]),
                    x + w / 2, this.buttonsY + (BTN_H - 8) / 2, textCol);
        }
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

    @Override
    public void removed() {
        // Preview survives close like AirportScreen — toggled off by the button.
        super.removed();
    }

    public void applyDeployResult(boolean ok, @Nullable Component message) {
        this.statusMessage = message != null ? message
                : Component.translatable(ok ? "gui.tacz_sewv.carrier_ops.deploy_ok"
                : "gui.tacz_sewv.carrier_ops.deploy_fail");
        this.statusColor = ok ? COL_GOOD : COL_BAD;
    }
}
