package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.fpas.RadarContact;
import com.neoalive.tacz_sewv.inventory.ZoneBeaconMenu;

/**
 * FPAS panel over {@code fpas_inventory.png}; slot positions live in {@link ZoneBeaconMenu}. In MTI a
 * radar scope opens to the right, drawn from SBW's own FuMO25 atlas (scope, sweep, grid and its
 * 29x15 buttons), with one attack button per contact class, each over its legend colour and count.
 */
public class ZoneBeaconScreen extends AbstractContainerScreen<ZoneBeaconMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/fpas_inventory.png");
    private static final ResourceLocation RADAR = new ResourceLocation("superbwarfare", "textures/gui/radar.png");
    private static final ResourceLocation SCAN = new ResourceLocation("superbwarfare", "textures/gui/radar_scan.png");
    private static final int ATLAS_W = 358, ATLAS_H = 328;
    /** FuMO25 button sprites: u 148, 29x15, hovered 16 below, one mode every 32. */
    private static final int BTN_U = 148, BTN_W = 29, BTN_H = 15;
    private static final int BTN_V_WIDE = 199;
    private static final int BTN_V_GUIDE = 263;
    private static final int TEX_W = 173;
    private static final int TEX_H = 200;
    /** The scope half of SBW's radar atlas: 163x166, centre (81, 84), radius 74. */
    private static final int SCOPE_W = 163, SCOPE_H = 166, SCOPE_CX = 81, SCOPE_CY = 84, SCOPE_R = 74;
    private static final int STRIP_GAP = 3;
    private static final int BAR_X = 7, BAR_Y = 84, BAR_W = 136, BAR_H = 8;
    private static final int BAR_BG = 0xFF373737;
    private static final int BAR_ON = 0xFFD8402A;
    private static final int TEXT = 0x404040;
    private static final int PANEL_BG = 0xFF2B2B2B;
    private static final int HOVER_PX = 3;

    private Button smaller;
    private Button larger;
    private final List<AtlasButton> attack = new ArrayList<>();
    private ZoneBeaconBlockEntity.Mode builtFor;

    public ZoneBeaconScreen(ZoneBeaconMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = TEX_W;
        this.imageHeight = TEX_H;
        this.titleLabelX = ZoneBeaconMenu.INV_X;
        this.inventoryLabelX = ZoneBeaconMenu.INV_X;
        this.inventoryLabelY = ZoneBeaconMenu.INV_Y - 11;
    }

    private boolean mti() {
        return this.menu.mode() == ZoneBeaconBlockEntity.Mode.MTI;
    }

    @Override
    protected void init() {
        this.builtFor = this.menu.mode();
        this.imageWidth = mti() ? TEX_W + SCOPE_W : TEX_W;
        super.init();
        int x = this.leftPos, y = this.topPos;
        this.smaller = addRenderableWidget(Button.builder(Component.literal("-"), b -> click(ZoneBeaconMenu.BUTTON_SMALLER))
                .bounds(x + 7, y + 18, 20, 20).build());
        this.larger = addRenderableWidget(Button.builder(Component.literal("+"), b -> click(ZoneBeaconMenu.BUTTON_LARGER))
                .bounds(x + 63, y + 18, 20, 20).build());
        AtlasButton mode = addRenderableWidget(new AtlasButton(x + 93, y + 21, BTN_V_WIDE, ZoneBeaconMenu.BUTTON_MODE));
        mode.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.fpas.mode.tooltip")));
        this.attack.clear();
        if (!mti()) return;
        for (RadarContact.Cls cls : RadarContact.Cls.values()) {
            AtlasButton b = new AtlasButton(stripX(cls), y + SCOPE_H + 3, BTN_V_GUIDE,
                    ZoneBeaconMenu.BUTTON_ATTACK + cls.ordinal());
            b.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.fpas.attack", clsName(cls))));
            this.attack.add(addRenderableWidget(b));
        }
    }

    /** Screen x of a class's column in the strip under the scope. */
    private int stripX(RadarContact.Cls cls) {
        return this.leftPos + TEX_W + STRIP_GAP + cls.ordinal() * (BTN_W + STRIP_GAP);
    }

    private static Component clsName(RadarContact.Cls cls) {
        return Component.translatable("gui.tacz_sewv.fpas.cls." + cls.key());
    }

    private void click(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    private List<RadarContact> contacts() {
        ZoneBeaconBlockEntity be = this.menu.beacon();
        return be == null || !mti() || !this.menu.powered() ? List.of() : RadarContactsClient.forPos(be.getBlockPos());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (this.menu.mode() != this.builtFor) {
            rebuildWidgets();
            return;
        }
        this.smaller.active = this.menu.radius() > 2;
        this.larger.active = this.menu.radius() < this.menu.maxRadius();
        int[] count = counts();
        for (int i = 0; i < this.attack.size(); i++) this.attack.get(i).active = count[i] > 0;
    }

    private int[] counts() {
        int[] count = new int[RadarContact.Cls.values().length];
        for (RadarContact c : contacts()) count[c.cls().ordinal()]++;
        return count;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos, y = this.topPos;
        g.blit(TEXTURE, x, y, 0, 0, TEX_W, TEX_H, TEX_W, TEX_H);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + BAR_W, y + BAR_Y + BAR_H, BAR_BG);
        int cap = Math.max(1, this.menu.capacity());
        int fill = (int) ((long) BAR_W * Math.min(cap, this.menu.energy()) / cap);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + fill, y + BAR_Y + BAR_H, BAR_ON);
        if (mti()) renderScope(g, x + TEX_W, y);
    }

    /** Dot position for a contact on the scope, clamped to the rim. */
    private int[] dot(RadarContact c, int sx, int sy) {
        ZoneBeaconBlockEntity be = this.menu.beacon();
        double range = this.menu.radius() * 16 + 8;
        double dx = (c.x() - (be.getBlockPos().getX() + 0.5)) / range * SCOPE_R;
        double dz = (c.z() - (be.getBlockPos().getZ() + 0.5)) / range * SCOPE_R;
        double len = Math.hypot(dx, dz);
        if (len > SCOPE_R) {
            dx *= SCOPE_R / len;
            dz *= SCOPE_R / len;
        }
        return new int[] {sx + SCOPE_CX + (int) Math.round(dx), sy + SCOPE_CY + (int) Math.round(dz)};
    }

    /** SBW's FuMO25 scope: background, sweep, dots, then the grid added on top. North is up. */
    private void renderScope(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + SCOPE_W, sy + TEX_H, PANEL_BG);
        g.blit(RADAR, sx, sy, 0, 0, SCOPE_W, SCOPE_H, ATLAS_W, ATLAS_H);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (this.menu.powered()) {
            g.pose().pushPose();
            g.pose().rotateAround(Axis.ZP.rotationDegrees(System.currentTimeMillis() % 36000000 / 30f),
                    sx + 9 + 145 / 2f, sy + 12 + 145 / 2f, 0f);
            g.blit(SCAN, sx + 9, sy + 12, 0, 0, 145, 145, 145, 145);
            g.pose().popPose();
        }
        if (this.menu.beacon() != null) {
            for (RadarContact c : contacts()) {
                int[] p = dot(c, sx, sy);
                g.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFF000000);
                g.fill(p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1, 0xFF000000 | c.cls().rgb);
            }
        }
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        g.blit(RADAR, sx + 8, sy + 11, 0, 167, 147, 147, ATLAS_W, ATLAS_H);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        // Legend: each class's colour under its attack button.
        for (RadarContact.Cls cls : RadarContact.Cls.values()) {
            int bx = stripX(cls);
            g.fill(bx, sy + SCOPE_H + 19, bx + BTN_W, sy + SCOPE_H + 22, 0xFF000000 | cls.rgb);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT, false);
        int r = this.menu.radius();
        int side = 2 * r + 1;
        g.drawCenteredString(this.font, String.valueOf(r), 45, 24, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.fpas.mode." + this.builtFor.name()
                .toLowerCase(Locale.ROOT)), 126, 25, mti() ? 0x2E7D32 : 0x1565C0, false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.cover", side, side), BAR_X, 44, TEXT,
                false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.upkeep", this.menu.upkeep()), BAR_X, 55,
                TEXT, false);
        String state = !this.menu.powered() ? "gui.tacz_sewv.zone_beacon.inactive"
                : mti() ? "gui.tacz_sewv.fpas.radar_active" : "gui.tacz_sewv.zone_beacon.active";
        g.drawString(this.font, Component.translatable(state), BAR_X, 70, this.menu.powered() ? 0x2E7D32 : 0xB71C1C,
                false);
        if (!mti()) return;
        int[] count = counts();
        for (RadarContact.Cls cls : RadarContact.Cls.values()) {
            int cx = stripX(cls) - this.leftPos + BTN_W / 2;
            g.drawCenteredString(this.font, String.valueOf(count[cls.ordinal()]), cx, SCOPE_H + 24, 0xFFFFFF);
        }
    }

    @Nullable
    private RadarContact hovered(int mouseX, int mouseY) {
        if (!mti() || this.menu.beacon() == null) return null;
        int sx = this.leftPos + TEX_W, sy = this.topPos;
        for (RadarContact c : contacts()) {
            int[] p = dot(c, sx, sy);
            if (Math.abs(mouseX - p[0]) <= HOVER_PX && Math.abs(mouseY - p[1]) <= HOVER_PX) return c;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int barX = this.leftPos + BAR_X, barY = this.topPos + BAR_Y;
        if (mouseX >= barX && mouseX < barX + BAR_W && mouseY >= barY && mouseY < barY + BAR_H) {
            g.renderTooltip(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.energy",
                    this.menu.energy(), this.menu.capacity()), mouseX, mouseY);
        }
        RadarContact c = hovered(mouseX, mouseY);
        ZoneBeaconBlockEntity be = this.menu.beacon();
        if (c != null && be != null) {
            int dist = (int) Math.round(Math.hypot(c.x() - be.getBlockPos().getX() - 0.5,
                    c.z() - be.getBlockPos().getZ() - 0.5));
            g.renderTooltip(this.font, Component.translatable("gui.tacz_sewv.fpas.contact", clsName(c.cls()), dist),
                    mouseX, mouseY);
        }
        this.renderTooltip(g, mouseX, mouseY);
    }

    /** One FuMO25 button sprite, sending a menu button id. Dimmed while inactive. */
    private final class AtlasButton extends AbstractButton {
        private final int v;
        private final int id;

        AtlasButton(int x, int y, int v, int id) {
            super(x, y, BTN_W, BTN_H, Component.empty());
            this.v = v;
            this.id = id;
        }

        @Override
        public void onPress() {
            click(this.id);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (!this.active) g.setColor(0.45F, 0.45F, 0.45F, 1.0F);
            g.blit(RADAR, getX(), getY(), BTN_U, this.v + (this.active && isHovered() ? 16 : 0), BTN_W, BTN_H,
                    ATLAS_W, ATLAS_H);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
