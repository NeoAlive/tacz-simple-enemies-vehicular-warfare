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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Compact forward-observer panel for the handheld radio — same C2 palette and section colouring
 * as {@link TdtScreen}, smaller footprint, no world dim.
 *
 * <p>CALL MISSION does not designate from the crosshair while the screen is open: it closes and
 * arms the same in-world pick pattern as TDT Live Selection / Move-To ({@link ClientEvents}), so
 * mouse input never fights the panel.
 */
public class RadioScreen extends Screen {

    private static final int PANEL_W = 240;
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

    private enum Action {
        CYCLE_FREQ, TOGGLE_TARGET, CYCLE_DELAY, SET_PLANE, CANCEL, CALL
    }

    private record Btn(Action action, String label, int x, int y, int w, int h,
                       boolean enabled, boolean selected, @Nullable PlaneAttackMode planeMode) {}

    private record Section(int stripe, int y0, int y1, String headerKey) {}

    private RadioSettings.State settings;
    private int panelLeft;
    private int panelTop;
    private int panelH;
    private final List<Btn> buttons = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();

    public RadioScreen() {
        super(Component.translatable("gui.tacz_sewv.radio.title"));
        this.settings = RadioSettings.State.defaults();
    }

    public static void open(@Nullable LivingEntity ignoredPrefill) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ItemStack stack = findRadio(mc.player);
        if (stack.isEmpty()) return;
        ClientEvents.clearRadioPick();
        RadioScreen screen = new RadioScreen();
        screen.settings = RadioSettings.read(stack);
        mc.setScreen(screen);
    }

    @Override
    protected void init() {
        rebuildLayout();
    }

    private void rebuildLayout() {
        this.buttons.clear();
        this.sections.clear();

        int innerW = PANEL_W - PAD * 2;
        int halfW = (innerW - CELL_GAP) / 2;
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

        // --- TARGET / DELAY ---
        secTop = y;
        y += HEADER_H;
        boolean position = this.settings.positionTarget();
        boolean canPosition = this.settings.frequency().supportsPositionTarget();
        String targetLabel = I18n.get(position
                ? "gui.tacz_sewv.radio.target_mode_position"
                : "gui.tacz_sewv.radio.target_mode_entity");
        this.buttons.add(new Btn(Action.TOGGLE_TARGET, targetLabel,
                x0, y, halfW, CELL_H, canPosition || !position, position, null));

        boolean canDelay = this.settings.frequency().supportsDelay();
        String delayLabel = !canDelay || this.settings.delaySeconds() <= 0
                ? I18n.get("gui.tacz_sewv.radio.delay_off")
                : I18n.get("gui.tacz_sewv.radio.delay_seconds", this.settings.delaySeconds());
        this.buttons.add(new Btn(Action.CYCLE_DELAY, delayLabel,
                x0 + halfW + CELL_GAP, y, halfW, CELL_H, canDelay, this.settings.delaySeconds() > 0, null));
        y += CELL_H + CELL_GAP;
        this.sections.add(new Section(STRIPE_TARGET, secTop, y - CELL_GAP, "gui.tacz_sewv.radio.section.target"));
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
        this.panelLeft = (this.width - PANEL_W) / 2;
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
        // Keep centred if frequency row gains/loses the plane section mid-open.
        if (panelHeightNeeded() != this.panelH) {
            rebuildLayout();
        }

        int pl = this.panelLeft;
        int pt = this.panelTop;

        g.fill(pl, pt, pl + PANEL_W, pt + this.panelH, COL_BASE);
        g.fill(pl, pt, pl + PANEL_W, pt + 1, COL_BORDER);
        g.fill(pl, pt + this.panelH - 1, pl + PANEL_W, pt + this.panelH, COL_BORDER);
        g.fill(pl, pt, pl + 1, pt + this.panelH, COL_BORDER);
        g.fill(pl + PANEL_W - 1, pt, pl + PANEL_W, pt + this.panelH, COL_BORDER);

        g.drawString(this.font, this.title, pl + PAD, pt + PAD, COL_ACCENT, false);

        for (Section section : this.sections) {
            int sy0 = pt + section.y0();
            int sy1 = pt + section.y1();
            g.fill(pl + PAD, sy0, pl + PAD + STRIPE_W, sy1, section.stripe());
            g.fill(pl + PAD + STRIPE_W, sy1 - 1, pl + PANEL_W - PAD, sy1, COL_BORDER);
            g.drawString(this.font, I18n.get(section.headerKey()),
                    pl + PAD + STRIPE_W + 4, sy0 + 2, COL_MUTED, false);
        }

        for (Btn btn : this.buttons) {
            renderBtn(g, btn, mouseX, mouseY);
        }
    }

    private int panelHeightNeeded() {
        int rows = 3 + (this.settings.frequency() == RadioFrequency.AIR ? 1 : 0);
        // title + each section header + cells + gaps — rebuildLayout is authoritative; this is a
        // cheap dirty check so we re-layout when AIR toggles.
        return PAD + 14 + rows * (HEADER_H + CELL_H + CELL_GAP + SECTION_GAP) + PAD;
    }

    private void renderBtn(GuiGraphics g, Btn btn, int mouseX, int mouseY) {
        int bx = this.panelLeft + btn.x();
        int by = this.panelTop + btn.y();
        boolean hover = btn.enabled()
                && mouseX >= bx && mouseX < bx + btn.w()
                && mouseY >= by && mouseY < by + btn.h();
        int fill = hover && btn.enabled() ? COL_HOVER : COL_SURFACE;
        g.fill(bx, by, bx + btn.w(), by + btn.h(), fill);
        g.fill(bx, by + btn.h() - 1, bx + btn.w(), by + btn.h(), COL_BORDER);
        if (btn.selected()) {
            g.fill(bx, by + btn.h() - 2, bx + btn.w(), by + btn.h(), COL_ACCENT);
        }
        int color = !btn.enabled() ? COL_MUTED : btn.selected() ? COL_ACCENT : COL_TEXT;
        g.drawCenteredString(this.font, btn.label(), bx + btn.w() / 2, by + (btn.h() - 8) / 2, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true; // swallow — world stays non-interactive while open

        // Clicks outside the panel are swallowed (same doctrine as TDT).
        if (mouseX < this.panelLeft || mouseX > this.panelLeft + PANEL_W
                || mouseY < this.panelTop || mouseY > this.panelTop + this.panelH) {
            return true;
        }

        for (Btn btn : this.buttons) {
            if (!btn.enabled()) continue;
            int bx = this.panelLeft + btn.x();
            int by = this.panelTop + btn.y();
            if (mouseX < bx || mouseX >= bx + btn.w() || mouseY < by || mouseY >= by + btn.h()) {
                continue;
            }
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
            case TOGGLE_TARGET -> {
                if (!this.settings.frequency().supportsPositionTarget()) return;
                this.settings = new RadioSettings.State(
                        this.settings.frequency(), !this.settings.positionTarget(),
                        this.settings.delaySeconds(), this.settings.planeMode());
                persist();
                rebuildLayout();
            }
            case CYCLE_DELAY -> {
                this.settings = new RadioSettings.State(
                        this.settings.frequency(), this.settings.positionTarget(),
                        RadioSettings.cycleDelay(this.settings.delaySeconds()), this.settings.planeMode());
                persist();
                rebuildLayout();
            }
            case SET_PLANE -> {
                if (btn.planeMode() == null) return;
                this.settings = new RadioSettings.State(
                        this.settings.frequency(), this.settings.positionTarget(),
                        this.settings.delaySeconds(), btn.planeMode());
                persist();
                rebuildLayout();
            }
            case CANCEL -> onClose();
            case CALL -> armCall();
        }
    }

    private void armCall() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return;
        ItemStack stack = findRadio(mc.player);
        if (!stack.isEmpty()) {
            RadioSettings.write(stack, this.settings);
        }
        onClose();
        if (this.settings.positionTarget()) {
            ClientEvents.armRadioPosition(this.settings);
        } else {
            ClientEvents.armRadioEntity(this.settings);
        }
    }

    private void persist() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return;
        ItemStack stack = findRadio(mc.player);
        if (!stack.isEmpty()) {
            RadioSettings.write(stack, this.settings);
        }
    }

    private static ItemStack findRadio(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.HANDHELD_RADIO.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(ModItems.HANDHELD_RADIO.get())) return off;
        return ItemStack.EMPTY;
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }
}
