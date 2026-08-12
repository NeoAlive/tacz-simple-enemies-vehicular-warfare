package com.neoalive.tacz_sewv.client.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.airport.RunwaySlots;
import com.neoalive.tacz_sewv.client.AirportPreview;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketAirportAction;

/**
 * PMC runway editor, drawn in the terminal's C2 palette rather than with vanilla widgets.
 *
 * <p>The panel is entirely custom except the four coordinate fields, which stay {@link EditBox}es
 * (unbordered, in a frame of our own) because a text cursor is not worth reimplementing.
 *
 * <p>Everything above the buttons is a preview of one thing: {@link AirportClearance#evaluate} is
 * pure geometry with no world reads, so the client can run the very check the server will run and
 * draw the resulting segmentation live while a slider moves. Only the world scans — obstructions,
 * the approach — need the round trip, which is what Check Clearance is for.
 */
public class AirportScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 4;
    private static final int DIAGRAM_H = 36;
    private static final int BTN_H = 20;

    // C2 palette — shared with the Tactical Data Terminal.
    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;
    private static final int COL_TRACK = 0x404FD1C5;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_GOOD = 0xFF7ED97E;

    // Diagram — one colour per thing the strip is divided into.
    private static final int COL_PAVEMENT = 0xFF232B34;
    private static final int COL_SLOT = 0xFF2F6E63;
    private static final int COL_SLOT_EDGE = 0xFF4FD1C5;
    private static final int COL_TAKEOFF = 0xFF7A5A2A;
    private static final int COL_TAKEOFF_EDGE = 0xFFD9A65A;
    private static final int COL_THRESHOLD = 0xFFE8ECF0;

    private final BlockPos pos;
    private int x1;
    private int z1;
    private int x2;
    private int z2;
    private boolean cleared;
    private AirportClearance.Status status;
    @Nullable private BlockPos blocker;
    /** Cleared footprint size for the status line — must not be named {@code width}/{@code height}
     *  (those are {@link Screen}'s GUI pixel size). */
    private int stripLength;
    private int stripWidth;
    /** How many aircraft the strip can park. Derived from its size; never configured. */
    private int capacity;
    /** Segmentation, per runway. Percent of the strip's length; see {@link RunwaySlots}. */
    private int slotPercent;
    private int bufferPercent;
    private int extraPercent;

    /**
     * Static, and deliberately not cleared on close: the preview is a survey aid, and its whole use
     * is walking the strip with the box drawn while the GUI is shut. It is turned off by toggling
     * the button again (or by leaving the world — see {@link AirportPreview}).
     */
    private static boolean preview;

    /** X1, Z1, X2, Z2 in that order. */
    private EditBox[] boxes = new EditBox[0];

    /** The local answer, recomputed whenever an input changes rather than every frame. */
    @Nullable private AirportClearance.Result plan;

    private int panelLeft;
    private int panelTop;
    private int panelBottom;
    private int innerW;
    private int fieldsY;
    private int slidersY;
    private int diagramY;
    private int legendY;
    private int buttonsY;
    private int statusY;

    private int dragging = -1;

    public AirportScreen(BlockPos pos, int x1, int z1, int x2, int z2, boolean cleared,
                         AirportClearance.Status status, @Nullable BlockPos blocker,
                         int stripLength, int stripWidth, int capacity,
                         float slotFactor, float bufferFactor, float extraFactor) {
        super(Component.translatable("gui.tacz_sewv.airport.title"));
        this.pos = pos;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.cleared = cleared;
        this.status = status == null ? AirportClearance.Status.NONE : status;
        this.blocker = blocker;
        this.stripLength = stripLength;
        this.stripWidth = stripWidth;
        this.capacity = capacity;
        this.slotPercent = Math.round(slotFactor * 100.0F);
        this.bufferPercent = Math.round(bufferFactor * 100.0F);
        this.extraPercent = Math.round(extraFactor * 100.0F);
    }

    // --- Layout -------------------------------------------------------------

    @Override
    protected void init() {
        int panelH = PAD + 16 + (ROW_H * 2 + ROW_GAP) + 8 + (ROW_H * 3 + ROW_GAP)
                + 6 + DIAGRAM_H + 14 + BTN_H + 18 + PAD;
        this.panelLeft = (this.width - PANEL_W) / 2;
        this.panelTop = Math.max(0, (this.height - panelH) / 2);
        this.panelBottom = this.panelTop + panelH;
        this.innerW = PANEL_W - PAD * 2;

        int left = this.panelLeft + PAD;
        this.fieldsY = this.panelTop + PAD + 16;
        this.slidersY = this.fieldsY + ROW_H * 2 + ROW_GAP + 8;
        this.diagramY = this.slidersY + ROW_H * 3 + ROW_GAP + 6;
        this.legendY = this.diagramY + DIAGRAM_H + 3;
        this.buttonsY = this.legendY + 14;
        this.statusY = this.buttonsY + BTN_H + 6;

        int fieldW = (this.innerW - 6) / 2 - 18;
        int row2 = this.fieldsY + ROW_H + ROW_GAP;
        int col2 = left + this.innerW / 2 + 3;
        this.boxes = new EditBox[]{
                field(left + 18, this.fieldsY, fieldW, this.x1),
                field(col2 + 18, this.fieldsY, fieldW, this.z1),
                field(left + 18, row2, fieldW, this.x2),
                field(col2 + 18, row2, fieldW, this.z2)};

        refreshPlan();
        syncPreview();
    }

    private EditBox field(int x, int y, int w, int value) {
        // Inset inside the frame drawn for it: an unbordered EditBox starts its text at its own
        // left edge, which would otherwise sit right on the border.
        EditBox box = new EditBox(this.font, x + 3, y, w - 6, ROW_H, Component.empty());
        box.setBordered(false);
        box.setMaxLength(12);
        box.setTextColor(COL_TEXT);
        box.setValue(Integer.toString(value));
        // Responder last: setValue fires it, and a screen that un-cleared its own runway while
        // filling in the numbers the server just sent would open showing Deploy greyed out.
        box.setResponder(s -> {
            invalidate();
            refreshPlan();
            syncPreview();
        });
        addRenderableWidget(box);
        return box;
    }

    private record Slider(String key, IntSupplier get, IntConsumer set, int min, int max) {}

    private Slider slider(int index) {
        return switch (index) {
            case 0 -> new Slider("gui.tacz_sewv.airport.slot_size",
                    () -> this.slotPercent, v -> this.slotPercent = v, 2, 50);
            case 1 -> new Slider("gui.tacz_sewv.airport.slot_buffer",
                    () -> this.bufferPercent, v -> this.bufferPercent = v, 0, 20);
            default -> new Slider("gui.tacz_sewv.airport.extra_takeoff",
                    () -> this.extraPercent, v -> this.extraPercent = v, 0, 50);
        };
    }

    private int sliderTop(int index) {
        return this.slidersY + index * (ROW_H + 2);
    }

    private int buttonLeft(int index) {
        int w = (this.innerW - 8) / 3;
        return this.panelLeft + PAD + index * (w + 4);
    }

    private int buttonWidth() {
        return (this.innerW - 8) / 3;
    }

    // --- State --------------------------------------------------------------

    /** Any edit un-clears the strip: the cached clearance describes the previous definition. */
    private void invalidate() {
        this.cleared = false;
        this.status = AirportClearance.Status.NONE;
        this.blocker = null;
    }

    /** Re-run the geometry half of the check locally. No world reads, so this is free. */
    private void refreshPlan() {
        if (!readBoxes()) {
            this.plan = null;
            return;
        }
        this.plan = AirportClearance.evaluate(
                new BlockPos(this.x1, this.pos.getY(), this.z1),
                new BlockPos(this.x2, this.pos.getY(), this.z2),
                this.pos.getY(),
                AirportClearance.Rules.forRunway(this.slotPercent / 100.0,
                        this.bufferPercent / 100.0, this.extraPercent / 100.0));
    }

    private boolean readBoxes() {
        if (this.boxes.length < 4) return false;
        try {
            this.x1 = Integer.parseInt(this.boxes[0].getValue().trim());
            this.z1 = Integer.parseInt(this.boxes[1].getValue().trim());
            this.x2 = Integer.parseInt(this.boxes[2].getValue().trim());
            this.z2 = Integer.parseInt(this.boxes[3].getValue().trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Corners and segmentation travel together: a check is of one whole runway definition. */
    private void send(int action) {
        if (!readBoxes()) return;
        NetworkHandler.CHANNEL.sendToServer(new PacketAirportAction(
                this.pos, this.x1, this.z1, this.x2, this.z2, action,
                this.slotPercent / 100.0F, this.bufferPercent / 100.0F, this.extraPercent / 100.0F));
    }

    private void syncPreview() {
        if (!preview || !readBoxes()) {
            AirportPreview.clear();
            return;
        }
        int minX = Math.min(this.x1, this.x2);
        int maxX = Math.max(this.x1, this.x2);
        int minZ = Math.min(this.z1, this.z2);
        int maxZ = Math.max(this.z1, this.z2);
        long area = (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
        int maxArea = SewvConfig.AIRPORT_MAX_AREA_BLOCKS.get();
        float r;
        float g;
        float b;
        if (area > maxArea) {
            r = 1.0f; g = 0.2f; b = 0.2f;
        } else if (area > maxArea / 8L) {
            r = 1.0f; g = 0.75f; b = 0.15f;
        } else {
            r = 0.2f; g = 0.9f; b = 0.3f;
        }
        int airY = this.pos.getY() + 1;
        AirportPreview.set(
                new AABB(minX, airY, minZ, maxX + 1, airY + 1, maxZ + 1),
                this.blocker,
                r, g, b);
    }

    // --- Input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < 3; i++) {
                int top = sliderTop(i);
                if (mouseY >= top && mouseY < top + ROW_H
                        && mouseX >= this.panelLeft + PAD && mouseX < this.panelLeft + PAD + this.innerW) {
                    this.dragging = i;
                    setSliderFromMouse(i, mouseX);
                    click();
                    return true;
                }
            }
            if (mouseY >= this.buttonsY && mouseY < this.buttonsY + BTN_H) {
                for (int i = 0; i < 3; i++) {
                    if (mouseX < buttonLeft(i) || mouseX >= buttonLeft(i) + buttonWidth()) continue;
                    if (i == 2 && !this.cleared) return true; // inert until the strip is cleared
                    click();
                    switch (i) {
                        case 0 -> {
                            preview = !preview;
                            syncPreview();
                        }
                        case 1 -> send(PacketAirportAction.ACTION_CHECK);
                        default -> send(PacketAirportAction.ACTION_DEPLOY);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging >= 0) {
            setSliderFromMouse(this.dragging, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void setSliderFromMouse(int index, double mouseX) {
        Slider s = slider(index);
        double frac = Mth.clamp((mouseX - (this.panelLeft + PAD)) / this.innerW, 0.0, 1.0);
        int value = s.min() + (int) Math.round(frac * (s.max() - s.min()));
        if (value == s.get().getAsInt()) return;
        s.set().accept(value);
        invalidate();
        refreshPlan();
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    @Override
    public void onClose() {
        syncPreview(); // keep whatever the toggle says; closing is not turning it off
        super.onClose();
    }

    // --- Render -------------------------------------------------------------

    /** No world dim: the strip being surveyed stays visible behind the panel. */
    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelBottom, COL_BASE);
        frame(g, this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelBottom);

        int left = this.panelLeft + PAD;
        g.drawString(this.font, this.title, left, this.panelTop + PAD - 2, COL_TEXT, false);
        g.drawString(this.font, AirportClearance.airportId(this.pos),
                left + this.innerW - this.font.width(AirportClearance.airportId(this.pos)),
                this.panelTop + PAD - 2, COL_ACCENT, false);

        renderFields(g);
        renderSliders(g, mouseX, mouseY);
        renderDiagram(g);
        renderButtons(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);

        Component line = statusLine();
        if (line != null) {
            int color = this.status == AirportClearance.Status.OK ? COL_GOOD : COL_BAD;
            g.drawString(this.font, line, left, this.statusY, color, false);
        }
    }

    private void renderFields(GuiGraphics g) {
        int left = this.panelLeft + PAD;
        int fieldW = (this.innerW - 6) / 2 - 18;
        String[] labels = {"X1", "Z1", "X2", "Z2"};
        for (int i = 0; i < 4; i++) {
            int x = (i % 2 == 0) ? left : left + this.innerW / 2 + 3;
            int y = this.fieldsY + (i / 2) * (ROW_H + ROW_GAP);
            boolean focused = i < this.boxes.length && this.boxes[i].isFocused();
            g.drawString(this.font, labels[i], x, y + 4, COL_MUTED, false);
            g.fill(x + 18, y, x + 18 + fieldW, y + ROW_H, COL_SURFACE);
            g.fill(x + 18, y + ROW_H - 1, x + 18 + fieldW, y + ROW_H,
                    focused ? COL_ACCENT : COL_BORDER);
        }
    }

    private void renderSliders(GuiGraphics g, int mouseX, int mouseY) {
        int left = this.panelLeft + PAD;
        for (int i = 0; i < 3; i++) {
            Slider s = slider(i);
            int top = sliderTop(i);
            boolean hover = mouseY >= top && mouseY < top + ROW_H
                    && mouseX >= left && mouseX < left + this.innerW;

            g.fill(left, top, left + this.innerW, top + ROW_H, hover ? COL_HOVER : COL_SURFACE);
            int filled = (int) (this.innerW
                    * (s.get().getAsInt() - s.min()) / (double) (s.max() - s.min()));
            g.fill(left, top, left + filled, top + ROW_H, COL_TRACK);
            g.fill(left + Math.max(0, filled - 1), top, left + filled + 1, top + ROW_H, COL_ACCENT);
            g.fill(left, top + ROW_H - 1, left + this.innerW, top + ROW_H, COL_BORDER);

            g.drawString(this.font, Component.translatable(s.key()), left + 4, top + 4, COL_TEXT, false);
            String value = s.get().getAsInt() + "%";
            g.drawString(this.font, value, left + this.innerW - 4 - this.font.width(value),
                    top + 4, COL_MUTED, false);
        }
    }

    /**
     * The strip from above, to scale along its length: parking slots, the gaps between them, and
     * the reserved run at the far end that every landing touches down into and every departure
     * accelerates down. Drawn from the same {@link RunwaySlots} the server will cut, so what is
     * on screen is what the aircraft will be given.
     */
    private void renderDiagram(GuiGraphics g) {
        int left = this.panelLeft + PAD;
        int right = left + this.innerW;
        int top = this.diagramY;
        int bottom = top + DIAGRAM_H;

        g.fill(left, top, right, bottom, COL_PAVEMENT);
        frame(g, left, top, right, bottom);

        AirportClearance.Result p = this.plan;
        if (p == null || p.slots() == null) {
            // Say why here rather than only after a Check: the shape gates need no world reads,
            // so making the player ask the server what it can already see would be pure latency.
            Component why = p == null
                    ? Component.translatable("gui.tacz_sewv.airport.diagram.no_shape")
                    : describe(p.status(), p.length(), p.width(), 0, null);
            g.drawCenteredString(this.font, why == null ? Component.empty() : why,
                    (left + right) / 2, top + DIAGRAM_H / 2 - 4, COL_BAD);
            return;
        }

        RunwaySlots slots = p.slots();
        double scale = this.innerW / (double) Math.max(1, slots.length());
        int lane0 = top + 6;
        int lane1 = bottom - 6;

        // Takeoff / landing run at the far end.
        int toStart = left + (int) Math.round(slots.usableLength() * scale);
        g.fill(toStart, lane0, right, lane1, COL_TAKEOFF);
        g.fill(toStart, lane0, toStart + 1, lane1, COL_TAKEOFF_EDGE);
        String toLabel = I18n.get("gui.tacz_sewv.airport.diagram.takeoff");
        if (right - toStart > this.font.width(toLabel) + 6) {
            g.drawCenteredString(this.font, toLabel, (toStart + right) / 2,
                    (lane0 + lane1) / 2 - 4, COL_TAKEOFF_EDGE);
        }

        // Parking slots, in order from the threshold.
        for (RunwaySlots.Slot slot : slots.slots()) {
            double start = slot.index() * (double) (slots.slotLength() + slots.bufferLength());
            int sx = left + (int) Math.round(start * scale);
            int ex = left + (int) Math.round((start + slots.slotLength()) * scale);
            if (ex <= sx + 1) ex = sx + 2;
            g.fill(sx, lane0, ex, lane1, COL_SLOT);
            frame(g, sx, lane0, ex, lane1, COL_SLOT_EDGE);
            String tag = Integer.toString(slot.index() + 1);
            if (ex - sx >= this.font.width(tag) + 4) {
                g.drawCenteredString(this.font, tag, (sx + ex) / 2, (lane0 + lane1) / 2 - 4, COL_TEXT);
            }
        }

        // Threshold bar, and the direction everything runs.
        g.fill(left, lane0 - 3, left + 2, lane1 + 3, COL_THRESHOLD);
        g.fill(left, (lane0 + lane1) / 2, right, (lane0 + lane1) / 2 + 1, 0x33FFFFFF);
        g.drawString(this.font, ">", right - 6, (lane0 + lane1) / 2 - 4, COL_THRESHOLD, false);

        String legend = I18n.get("gui.tacz_sewv.airport.diagram.legend",
                slots.capacity(), slots.slotLength(), slots.bufferLength(),
                (int) Math.round(slots.takeoffBuffer()), slots.length(), slots.width());
        g.drawString(this.font, legend, left, this.legendY, COL_MUTED, false);
    }

    private void renderButtons(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {
                I18n.get(preview ? "gui.tacz_sewv.airport.preview.on" : "gui.tacz_sewv.airport.preview.off"),
                I18n.get("gui.tacz_sewv.airport.check"),
                I18n.get("gui.tacz_sewv.airport.deploy")};
        for (int i = 0; i < 3; i++) {
            int x = buttonLeft(i);
            int w = buttonWidth();
            boolean enabled = i != 2 || this.cleared;
            boolean hover = enabled && mouseX >= x && mouseX < x + w
                    && mouseY >= this.buttonsY && mouseY < this.buttonsY + BTN_H;
            g.fill(x, this.buttonsY, x + w, this.buttonsY + BTN_H, hover ? COL_HOVER : COL_SURFACE);
            g.fill(x, this.buttonsY + BTN_H - 1, x + w, this.buttonsY + BTN_H,
                    i == 0 && preview ? COL_ACCENT : COL_BORDER);
            g.drawCenteredString(this.font, labels[i], x + w / 2, this.buttonsY + (BTN_H - 8) / 2,
                    enabled ? COL_TEXT : COL_MUTED);
        }
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        frame(g, x0, y0, x1, y1, COL_BORDER);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    @Nullable
    private Component statusLine() {
        return describe(this.status, this.stripLength, this.stripWidth, this.capacity, this.blocker);
    }

    /** One wording for a verdict, whether the server sent it or the client worked it out. */
    @Nullable
    private Component describe(AirportClearance.Status st, int length, int width, int capacity,
                               @Nullable BlockPos blocker) {
        return switch (st) {
            case NONE -> null;
            case OK -> Component.translatable("gui.tacz_sewv.airport.status.ok",
                    AirportClearance.airportId(this.pos), length, width, capacity);
            case ASPECT -> Component.translatable("gui.tacz_sewv.airport.status.aspect",
                    SewvConfig.AIRPORT_MIN_ASPECT_RATIO.get());
            case TOO_SHORT -> Component.translatable("gui.tacz_sewv.airport.status.too_short",
                    length, SewvConfig.AIRPORT_MIN_LENGTH_BLOCKS.get());
            case TOO_LARGE -> Component.translatable("gui.tacz_sewv.airport.status.too_large",
                    (long) length * (long) width, SewvConfig.AIRPORT_MAX_AREA_BLOCKS.get());
            case OBSTRUCTED -> blocker == null
                    ? Component.translatable("gui.tacz_sewv.airport.status.obstructed", 0, 0, 0)
                    : Component.translatable("gui.tacz_sewv.airport.status.obstructed",
                            blocker.getX(), blocker.getY(), blocker.getZ());
            case NOT_POOLED -> Component.translatable("gui.tacz_sewv.airport.status.not_pooled");
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
