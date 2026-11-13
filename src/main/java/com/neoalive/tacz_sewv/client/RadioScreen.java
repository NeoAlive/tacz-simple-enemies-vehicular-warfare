package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketRadioSettings;

/**
 * Compact forward-observer panel for the handheld radio — same C2 palette and section colouring
 * as {@link TdtScreen}, smaller footprint, no world dim.
 *
 * <p>CALL closes the panel and calls at the crosshair ({@link ClientEvents#callRadio}); the server
 * raycast decides entity vs grid mission by what it hits.
 */
public class RadioScreen extends Screen {

    private static final int PANEL_W_PREF = 240;
    private static final int PAD = 10;
    private static final int CELL_H = 22;
    private static final int CELL_GAP = 4;
    private static final int SECTION_GAP = 8;
    private static final int HEADER_H = 14;
    private static final int STRIPE_W = 3;

    // Same C2 palette as TdtScreen.
    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;

    // Function stripes (~30% alpha), matching TDT category colours where they map.
    private static final int STRIPE_FREQ = 0x4D7C8CD9;   // ORDERS blue — band select
    private static final int STRIPE_TARGET = 0x4DC9A15A; // AREA amber — designation
    private static final int STRIPE_AIR = 0x4DB57ED1;    // AIR purple — plane doctrine
    private static final int STRIPE_ACTION = 0x4D8FAA6B; // CREW green — commit
    private static final int STRIPE_COMMS = 0x4D4FD1C5;  // teal — what the radio receives

    private enum Action {
        CYCLE_FREQ, CYCLE_DELAY, SET_PLANE, TOGGLE_NOTIFY, TOGGLE_CHATTER, TOGGLE_VOICE, CYCLE_NET, CANCEL, CALL
    }

    private record Btn(Action action, String label, int x, int y, int w, int h,
                       boolean enabled, boolean selected, @Nullable PlaneAttackMode planeMode,
                       @Nullable String tip) {

        Btn(Action action, String label, int x, int y, int w, int h,
            boolean enabled, boolean selected, @Nullable PlaneAttackMode planeMode) {
            this(action, label, x, y, w, h, enabled, selected, planeMode, null);
        }

        boolean contains(double mx, double my, int left, int top) {
            return mx >= left + x && mx < left + x + w && my >= top + y && my < top + y + h;
        }
    }

    private record Section(int stripe, int y0, int y1, String headerKey) {}

    private RadioSettings.State settings;
    private RadioSettings.Comms comms = RadioSettings.Comms.defaults();
    private int builtKey = -1;
    private int panelLeft;
    private int panelTop;
    private int panelW;
    private int panelH;
    private final List<Btn> buttons = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    @Nullable
    private String pendingTip;

    public RadioScreen() {
        super(Component.translatable("gui.tacz_sewv.radio.title"));
        this.settings = RadioSettings.State.defaults();
    }

    public static void open(@Nullable LivingEntity ignoredPrefill) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ItemStack stack = HandheldRadioItem.findCarried(mc.player);
        if (stack.isEmpty()) return;
        RadioScreen screen = new RadioScreen();
        screen.settings = RadioSettings.read(stack);
        screen.comms = RadioSettings.readComms(stack);
        mc.setScreen(screen);
    }

    @Override
    protected void init() {
        rebuildLayout();
    }

    private void rebuildLayout() {
        this.buttons.clear();
        this.sections.clear();

        this.panelW = com.neoalive.tacz_sewv.client.gui.GuiFit.panelW(PANEL_W_PREF, this.width);
        int innerW = this.panelW - PAD * 2;
        int x0 = PAD;
        int y = PAD + 14; // below title

        // --- FREQUENCY ---
        int secTop = y;
        y += HEADER_H;
        String freqArg = I18n.get("gui.tacz_sewv.radio.freq." + this.settings.frequency().name().toLowerCase());
        this.buttons.add(new Btn(Action.CYCLE_FREQ,
                I18n.get("gui.tacz_sewv.radio.frequency", freqArg),
                x0, y, innerW, CELL_H, true, false, null));
        y += CELL_H + CELL_GAP;
        this.sections.add(new Section(STRIPE_FREQ, secTop, y - CELL_GAP, "gui.tacz_sewv.radio.section.frequency"));
        y += SECTION_GAP;

        // --- DELAY ---
        secTop = y;
        y += HEADER_H;
        boolean canDelay = this.settings.frequency().supportsDelay();
        String delayLabel = !canDelay || this.settings.delaySeconds() <= 0
                ? I18n.get("gui.tacz_sewv.radio.delay_off")
                : I18n.get("gui.tacz_sewv.radio.delay_seconds", this.settings.delaySeconds());
        this.buttons.add(new Btn(Action.CYCLE_DELAY, delayLabel,
                x0, y, innerW, CELL_H, canDelay, this.settings.delaySeconds() > 0, null));
        y += CELL_H + CELL_GAP;
        this.sections.add(new Section(STRIPE_TARGET, secTop, y - CELL_GAP, "gui.tacz_sewv.radio.delay"));
        y += SECTION_GAP;

        // --- PLANE MODE (AIR only) ---
        if (this.settings.frequency() == RadioFrequency.AIR) {
            secTop = y;
            y += HEADER_H;
            int quarter = (innerW - CELL_GAP * 3) / 4;
            PlaneAttackMode[] modes = PlaneAttackMode.values();
            for (int i = 0; i < modes.length; i++) {
                PlaneAttackMode mode = modes[i];
                String label = I18n.get("gui.tacz_sewv.radio.plane." + mode.name().toLowerCase());
                this.buttons.add(new Btn(Action.SET_PLANE, label,
                        x0 + i * (quarter + CELL_GAP), y, quarter, CELL_H,
                        true, mode == this.settings.planeMode(), mode));
            }
            y += CELL_H + CELL_GAP;
            this.sections.add(new Section(STRIPE_AIR, secTop, y - CELL_GAP, "gui.tacz_sewv.radio.section.plane"));
            y += SECTION_GAP;
        }

        // --- COMMS ---
        secTop = y;
        y += HEADER_H;
        int half = (innerW - CELL_GAP) / 2;
        boolean gated = RadioFlagsClient.requireRadio();
        String notifyLabel = gated
                ? I18n.get(this.comms.alerts() ? "gui.tacz_sewv.radio.notify.on" : "gui.tacz_sewv.radio.notify.off")
                : I18n.get("gui.tacz_sewv.radio.notify.always");
        this.buttons.add(new Btn(Action.TOGGLE_NOTIFY, notifyLabel,
                x0, y, half, CELL_H, gated, gated && this.comms.alerts(), null,
                gated ? "gui.tacz_sewv.radio.tip.alerts" : "gui.tacz_sewv.radio.tip.alerts_always"));
        this.buttons.add(new Btn(Action.TOGGLE_CHATTER,
                I18n.get(this.comms.chatter() ? "gui.tacz_sewv.radio.chatter.on" : "gui.tacz_sewv.radio.chatter.off"),
                x0 + half + CELL_GAP, y, half, CELL_H, true, this.comms.chatter(), null,
                "gui.tacz_sewv.radio.tip.chatter"));
        y += CELL_H + CELL_GAP;
        if (RadioFlagsClient.voice()) {
            this.buttons.add(new Btn(Action.TOGGLE_VOICE,
                    I18n.get(this.comms.voice() ? "gui.tacz_sewv.radio.voice.on" : "gui.tacz_sewv.radio.voice.off"),
                    x0, y, half, CELL_H, true, this.comms.voice(), null, "gui.tacz_sewv.radio.tip.voice"));
            this.buttons.add(new Btn(Action.CYCLE_NET, I18n.get("gui.tacz_sewv.radio.net", this.comms.net()),
                    x0 + half + CELL_GAP, y, half, CELL_H, this.comms.voice(), false, null,
                    "gui.tacz_sewv.radio.tip.net"));
            y += CELL_H + CELL_GAP;
        }
        this.sections.add(new Section(STRIPE_COMMS, secTop, y - CELL_GAP, "gui.tacz_sewv.radio.comms"));
        y += SECTION_GAP;

        // --- ACTIONS ---
        secTop = y;
        y += HEADER_H;
        int actionW = (innerW - CELL_GAP) / 2;
        this.buttons.add(new Btn(Action.CANCEL, I18n.get("gui.tacz_sewv.radio.cancel"),
                x0, y, actionW, CELL_H, true, false, null));
        this.buttons.add(new Btn(Action.CALL, I18n.get("gui.tacz_sewv.radio.call"),
                x0 + actionW + CELL_GAP, y, actionW, CELL_H, true, false, null));
        y += CELL_H;
        this.sections.add(new Section(STRIPE_ACTION, secTop, y, "gui.tacz_sewv.radio.section.action"));

        this.panelH = y + PAD;
        this.builtKey = layoutKey();
        this.panelLeft = (this.width - this.panelW) / 2;
        this.panelTop = (this.height - this.panelH) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Re-layout when a section appears or goes (AIR plane row, voice row after the login sync).
        if (layoutKey() != this.builtKey) {
            rebuildLayout();
        }

        int pl = this.panelLeft;
        int pt = this.panelTop;

        this.pendingTip = null;
        g.fill(pl, pt, pl + this.panelW, pt + this.panelH, COL_BASE);
        g.fill(pl, pt + this.panelH - 1, pl + this.panelW, pt + this.panelH, COL_BORDER);
        g.fill(pl, pt, pl + 1, pt + this.panelH, COL_BORDER);
        g.fill(pl + this.panelW - 1, pt, pl + this.panelW, pt + this.panelH, COL_BORDER);
        g.fill(pl, pt, pl + this.panelW, pt + 1, COL_ACCENT);

        g.drawString(this.font, this.title, pl + PAD, pt + PAD, COL_TEXT, false);

        for (Section section : this.sections) {
            int sy0 = pt + section.y0();
            int sy1 = pt + section.y1();
            g.fill(pl + PAD, sy0, pl + PAD + STRIPE_W, sy1, section.stripe());
            g.fill(pl + PAD + STRIPE_W, sy1 - 1, pl + this.panelW - PAD, sy1, COL_BORDER);
            g.drawString(this.font, I18n.get(section.headerKey()),
                    pl + PAD + STRIPE_W + 4, sy0 + 2, COL_MUTED, false);
        }

        for (Btn btn : this.buttons) {
            renderBtn(g, btn, mouseX, mouseY);
        }
        if (this.pendingTip != null) {
            g.renderTooltip(this.font, this.font.split(Component.translatable(this.pendingTip), 200), mouseX, mouseY);
        }
    }

    private int layoutKey() {
        return (this.settings.frequency() == RadioFrequency.AIR ? 1 : 0) | (RadioFlagsClient.voice() ? 2 : 0);
    }

    private void renderBtn(GuiGraphics g, Btn btn, int mouseX, int mouseY) {
        int bx = this.panelLeft + btn.x();
        int by = this.panelTop + btn.y();
        boolean inside = btn.contains(mouseX, mouseY, this.panelLeft, this.panelTop);
        boolean hover = btn.enabled() && inside;
        if (inside && btn.tip() != null) this.pendingTip = btn.tip();
        g.fill(bx, by, bx + btn.w(), by + btn.h(), hover ? COL_HOVER : COL_SURFACE);
        g.fill(bx, by + btn.h() - 1, bx + btn.w(), by + btn.h(), COL_BORDER);
        if (btn.selected()) {
            g.fill(bx, by + btn.h() - 2, bx + btn.w(), by + btn.h(), COL_ACCENT);
        }
        int color = !btn.enabled() ? COL_MUTED : btn.selected() ? COL_ACCENT : COL_TEXT;
        int textY = by + (btn.h() - 8) / 2;
        if (btn.action() == Action.CYCLE_NET) {
            int glyph = btn.enabled() ? COL_ACCENT : COL_BORDER;
            g.drawString(this.font, "<", bx + 6, textY, glyph, false);
            g.drawString(this.font, ">", bx + btn.w() - 6 - this.font.width(">"), textY, glyph, false);
        }
        g.drawCenteredString(this.font, btn.label(), bx + btn.w() / 2, textY, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) return true; // swallow, world stays non-interactive while open

        // Clicks outside the panel are swallowed (same doctrine as TDT).
        if (mouseX < this.panelLeft || mouseX > this.panelLeft + this.panelW
                || mouseY < this.panelTop || mouseY > this.panelTop + this.panelH) {
            return true;
        }

        for (Btn btn : this.buttons) {
            if (!btn.enabled() || !btn.contains(mouseX, mouseY, this.panelLeft, this.panelTop)) continue;
            if (btn.action() == Action.CYCLE_NET) {
                boolean back = button == 1 || mouseX < this.panelLeft + btn.x() + btn.w() / 2.0;
                clickSound();
                setComms(this.comms.stepNet(back ? -1 : 1));
                return true;
            }
            if (button != 0) return true;
            clickSound();
            handleClick(btn);
            return true;
        }
        return true;
    }

    private void handleClick(Btn btn) {
        switch (btn.action()) {
            case CYCLE_FREQ -> {
                this.settings = this.settings.withFrequency(this.settings.frequency().next());
                persist();
                rebuildLayout();
            }
            case CYCLE_DELAY -> {
                this.settings = new RadioSettings.State(
                        this.settings.frequency(),
                        RadioSettings.cycleDelay(this.settings.delaySeconds()), this.settings.planeMode());
                persist();
                rebuildLayout();
            }
            case SET_PLANE -> {
                if (btn.planeMode() == null) return;
                this.settings = new RadioSettings.State(
                        this.settings.frequency(), this.settings.delaySeconds(), btn.planeMode());
                persist();
                rebuildLayout();
            }
            case TOGGLE_NOTIFY -> setComms(this.comms.withAlerts(!this.comms.alerts()));
            case TOGGLE_CHATTER -> setComms(this.comms.withChatter(!this.comms.chatter()));
            case TOGGLE_VOICE -> setComms(this.comms.withVoice(!this.comms.voice()));
            case CYCLE_NET -> {}
            case CANCEL -> onClose();
            case CALL -> armCall();
        }
    }

    private void armCall() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return;
        persist();
        onClose();
        ClientEvents.callRadio(this.settings);
    }

    /** Local write so the panel answers at once, server write so a slot sync cannot revert it. */
    private void persist() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return;
        ItemStack stack = HandheldRadioItem.findCarried(mc.player);
        if (stack.isEmpty()) return;
        RadioSettings.write(stack, this.settings);
        RadioSettings.writeComms(stack, this.comms);
        NetworkHandler.CHANNEL.sendToServer(new PacketRadioSettings(this.settings, this.comms));
    }

    private void setComms(RadioSettings.Comms next) {
        if (next.equals(this.comms)) return;
        this.comms = next;
        persist();
        rebuildLayout();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        for (Btn btn : this.buttons) {
            if (btn.action() != Action.CYCLE_NET || !btn.enabled()) continue;
            if (btn.contains(mouseX, mouseY, this.panelLeft, this.panelTop)) {
                clickSound();
                setComms(this.comms.stepNet(delta > 0 ? 1 : -1));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }
}
