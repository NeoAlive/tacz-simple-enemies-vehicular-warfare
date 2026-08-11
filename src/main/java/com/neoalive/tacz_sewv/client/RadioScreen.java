package com.neoalive.tacz_sewv.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketRadioCommand;

/**
 * Compact forward-observer panel for the handheld radio — same C2 palette as {@link TdtScreen},
 * smaller footprint, no world dim.
 */
public class RadioScreen extends Screen {

    private static final int PANEL_W = 220;
    private static final int PAD = 8;
    private static final int CELL_H = 20;
    private static final int GAP = 4;
    private static final int PLANE_ROW_H = CELL_H + GAP;

    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;

    @Nullable
    private final Integer prefilledEntityId;

    private RadioSettings.State settings;
    private int panelLeft;
    private int panelTop;
    private int panelH;

    private record Btn(String labelKey, String arg, int x, int y, int w, int h, boolean enabled, boolean selected) {}

    public RadioScreen(@Nullable Integer prefilledEntityId) {
        super(Component.translatable("gui.tacz_sewv.radio.title"));
        this.prefilledEntityId = prefilledEntityId;
        this.settings = RadioSettings.State.defaults();
    }

    public static void open(@Nullable LivingEntity prefilledTarget) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (InvasionHudClient.isActive()) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.invasion.orders_locked"), true);
            return;
        }
        ItemStack stack = findRadio(mc.player);
        if (stack.isEmpty()) return;
        RadioScreen screen = new RadioScreen(prefilledTarget != null ? prefilledTarget.getId() : null);
        screen.settings = RadioSettings.read(stack);
        mc.setScreen(screen);
    }

    @Override
    protected void init() {
        this.panelLeft = (this.width - PANEL_W) / 2;
        this.panelTop = (this.height - panelHeight()) / 2;
        this.panelH = panelHeight();
    }

    private int panelHeight() {
        int rows = 3 + (this.settings.frequency() == RadioFrequency.AIR ? 1 : 0);
        return PAD * 2 + 10 + rows * (CELL_H + GAP) + CELL_H;
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
        this.panelH = panelHeight();
        this.panelTop = (this.height - this.panelH) / 2;

        g.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelTop + this.panelH, COL_BASE);
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelTop + 1, COL_BORDER);
        g.fill(this.panelLeft, this.panelTop + this.panelH - 1, this.panelLeft + PANEL_W,
                this.panelTop + this.panelH, COL_BORDER);
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelTop + this.panelH, COL_BORDER);
        g.fill(this.panelLeft + PANEL_W - 1, this.panelTop, this.panelLeft + PANEL_W,
                this.panelTop + this.panelH, COL_BORDER);

        g.drawString(this.font, this.title, this.panelLeft + PAD, this.panelTop + PAD, COL_ACCENT, false);

        for (Btn btn : layoutButtons()) {
            renderBtn(g, btn, mouseX, mouseY);
        }
    }

    private void renderBtn(GuiGraphics g, Btn btn, int mouseX, int mouseY) {
        boolean hover = btn.enabled()
                && mouseX >= btn.x() && mouseX < btn.x() + btn.w()
                && mouseY >= btn.y() && mouseY < btn.y() + btn.h();
        int fill = !btn.enabled() ? COL_SURFACE : hover ? COL_HOVER : COL_SURFACE;
        g.fill(btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h(), fill);
        g.fill(btn.x(), btn.y() + btn.h() - 1, btn.x() + btn.w(), btn.y() + btn.h(), COL_BORDER);
        if (btn.selected()) {
            g.fill(btn.x(), btn.y() + btn.h() - 2, btn.x() + btn.w(), btn.y() + btn.h(), COL_ACCENT);
        }
        String text;
        if ("gui.tacz_sewv.radio.delay".equals(btn.labelKey())) {
            text = btn.arg() != null ? btn.arg() : I18n.get("gui.tacz_sewv.radio.delay_off");
        } else if (btn.arg() == null) {
            text = I18n.get(btn.labelKey());
        } else {
            text = I18n.get(btn.labelKey(), btn.arg());
        }
        int color = btn.enabled() ? (btn.selected() ? COL_ACCENT : COL_TEXT) : COL_MUTED;
        g.drawCenteredString(this.font, text, btn.x() + btn.w() / 2, btn.y() + (btn.h() - 8) / 2, color);
    }

    private java.util.List<Btn> layoutButtons() {
        int innerW = PANEL_W - PAD * 2;
        int halfW = (innerW - GAP) / 2;
        int x0 = this.panelLeft + PAD;
        int y = this.panelTop + PAD + 12;

        java.util.List<Btn> buttons = new java.util.ArrayList<>();

        String freqArg = I18n.get("gui.tacz_sewv.radio.freq." + this.settings.frequency().name().toLowerCase());
        buttons.add(new Btn("gui.tacz_sewv.radio.frequency", freqArg, x0, y, innerW, CELL_H, true, false));
        y += CELL_H + GAP;

        boolean position = this.settings.positionTarget();
        boolean canPosition = this.settings.frequency().supportsPositionTarget();
        String targetKey = position
                ? "gui.tacz_sewv.radio.target_mode_position"
                : "gui.tacz_sewv.radio.target_mode_entity";
        buttons.add(new Btn(targetKey, null, x0, y, halfW, CELL_H, canPosition || !position, position));

        boolean canDelay = this.settings.frequency().supportsDelay();
        String delayLabel;
        if (!canDelay || this.settings.delaySeconds() <= 0) {
            delayLabel = I18n.get("gui.tacz_sewv.radio.delay_off");
        } else {
            delayLabel = I18n.get("gui.tacz_sewv.radio.delay_seconds", this.settings.delaySeconds());
        }
        buttons.add(new Btn("gui.tacz_sewv.radio.delay", delayLabel, x0 + halfW + GAP, y, halfW, CELL_H,
                canDelay, this.settings.delaySeconds() > 0));
        y += CELL_H + GAP;

        if (this.settings.frequency() == RadioFrequency.AIR) {
            int quarter = (innerW - GAP * 3) / 4;
            PlaneAttackMode[] modes = PlaneAttackMode.values();
            for (int i = 0; i < modes.length; i++) {
                PlaneAttackMode mode = modes[i];
                String arg = I18n.get("gui.tacz_sewv.radio.plane." + mode.name().toLowerCase());
                int bx = x0 + i * (quarter + GAP);
                buttons.add(new Btn("gui.tacz_sewv.radio.plane_mode_short", arg, bx, y, quarter, CELL_H,
                        true, mode == this.settings.planeMode()));
            }
            y += PLANE_ROW_H;
        }

        int actionW = (innerW - GAP) / 2;
        buttons.add(new Btn("gui.tacz_sewv.radio.cancel", null, x0, y, actionW, CELL_H, true, false));
        buttons.add(new Btn("gui.tacz_sewv.radio.call", null, x0 + actionW + GAP, y, actionW, CELL_H, true, false));

        return buttons;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        for (Btn btn : layoutButtons()) {
            if (!btn.enabled()) continue;
            if (mouseX < btn.x() || mouseX >= btn.x() + btn.w()
                    || mouseY < btn.y() || mouseY >= btn.y() + btn.h()) {
                continue;
            }
            clickSound();
            handleClick(btn);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleClick(Btn btn) {
        String key = btn.labelKey();
        if ("gui.tacz_sewv.radio.frequency".equals(key)) {
            this.settings = this.settings.withFrequency(this.settings.frequency().next());
            persist();
            return;
        }
        if ("gui.tacz_sewv.radio.target_mode_entity".equals(key)
                || "gui.tacz_sewv.radio.target_mode_position".equals(key)) {
            if (!this.settings.frequency().supportsPositionTarget()) return;
            this.settings = new RadioSettings.State(
                    this.settings.frequency(), !this.settings.positionTarget(),
                    this.settings.delaySeconds(), this.settings.planeMode());
            persist();
            return;
        }
        if ("gui.tacz_sewv.radio.delay".equals(key)) {
            this.settings = new RadioSettings.State(
                    this.settings.frequency(), this.settings.positionTarget(),
                    RadioSettings.cycleDelay(this.settings.delaySeconds()), this.settings.planeMode());
            persist();
            return;
        }
        if ("gui.tacz_sewv.radio.plane_mode_short".equals(key) && btn.arg() != null) {
            PlaneAttackMode picked = parsePlaneMode(btn.arg());
            if (picked != null) {
                this.settings = new RadioSettings.State(
                        this.settings.frequency(), this.settings.positionTarget(),
                        this.settings.delaySeconds(), picked);
                persist();
            }
            return;
        }
        if ("gui.tacz_sewv.radio.cancel".equals(key)) {
            onClose();
            return;
        }
        if ("gui.tacz_sewv.radio.call".equals(key)) {
            sendCall();
        }
    }

    @Nullable
    private static PlaneAttackMode parsePlaneMode(String label) {
        for (PlaneAttackMode mode : PlaneAttackMode.values()) {
            if (I18n.get("gui.tacz_sewv.radio.plane." + mode.name().toLowerCase()).equals(label)) {
                return mode;
            }
        }
        return null;
    }

    private void sendCall() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return;

        ItemStack stack = findRadio(mc.player);
        if (!stack.isEmpty()) {
            RadioSettings.write(stack, this.settings);
        }

        int entityId = -1;
        BlockPos pos = null;
        if (this.settings.positionTarget()) {
            HitResult hit = mc.player.pick(256.0, 0.0F, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                pos = bhr.getBlockPos();
            } else {
                mc.player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.radio.no_position")
                                .withStyle(ChatFormatting.GRAY), true);
                return;
            }
        } else {
            LivingEntity target = pickEntityTarget(mc.player);
            if (target == null && this.prefilledEntityId != null) {
                Entity entity = mc.level.getEntity(this.prefilledEntityId);
                if (entity instanceof LivingEntity living && HandheldRadioItem.isDesignatable(entity)) {
                    target = living;
                }
            }
            if (target == null) {
                mc.player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.radio.no_target")
                                .withStyle(ChatFormatting.GRAY), true);
                return;
            }
            entityId = target.getId();
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketRadioCommand(this.settings, entityId, pos));
        onClose();
    }

    @Nullable
    private static LivingEntity pickEntityTarget(Player player) {
        double range = 256.0;
        Vec3 eye = player.getEyePosition();
        Vec3 reach = player.getViewVector(1.0F).scale(range);
        Vec3 end = eye.add(reach);
        AABB search = player.getBoundingBox().expandTowards(reach).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, search, HandheldRadioItem::isDesignatable, range * range);
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
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
