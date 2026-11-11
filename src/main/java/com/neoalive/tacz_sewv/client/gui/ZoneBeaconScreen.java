package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
 * radar scope (SBW's own FuMO25 art) opens to the right, with a legend and one attack button per
 * contact class underneath.
 */
public class ZoneBeaconScreen extends AbstractContainerScreen<ZoneBeaconMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/fpas_inventory.png");
    private static final ResourceLocation RADAR = new ResourceLocation("superbwarfare", "textures/gui/radar.png");
    private static final ResourceLocation SCAN = new ResourceLocation("superbwarfare", "textures/gui/radar_scan.png");
    private static final int TEX_W = 173;
    private static final int TEX_H = 200;
    /** The scope half of SBW's radar atlas: 163x166 of 358x328, centre (81, 84), radius 74. */
    private static final int SCOPE_W = 163, SCOPE_H = 166, SCOPE_CX = 81, SCOPE_CY = 84, SCOPE_R = 74;
    private static final int BAR_X = 7, BAR_Y = 84, BAR_W = 136, BAR_H = 8;
    private static final int BAR_BG = 0xFF373737;
    private static final int BAR_ON = 0xFFD8402A;
    private static final int TEXT = 0x404040;
    private static final int PANEL_BG = 0xFF1B1F1B;

    private Button smaller;
    private Button larger;
    private Button mode;
    private final List<Button> attack = new ArrayList<>();
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
        this.mode = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.fpas.mode." + this.builtFor.name().toLowerCase()),
                        b -> click(ZoneBeaconMenu.BUTTON_MODE))
                .bounds(x + 93, y + 18, 40, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.fpas.mode.tooltip")))
                .build());
        this.attack.clear();
        if (!mti()) return;
        RadarContact.Cls[] classes = RadarContact.Cls.values();
        int w = SCOPE_W / classes.length;
        for (RadarContact.Cls cls : classes) {
            int id = ZoneBeaconMenu.BUTTON_ATTACK + cls.ordinal();
            Button b = Button.builder(Component.translatable("gui.tacz_sewv.fpas.short." + cls.key()), btn -> click(id))
                    .bounds(x + TEX_W + cls.ordinal() * w + 1, y + SCOPE_H + 14, w - 2, 18)
                    .tooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.fpas.attack",
                            Component.translatable("gui.tacz_sewv.fpas.cls." + cls.key()))))
                    .build();
            this.attack.add(addRenderableWidget(b));
        }
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

    /** SBW's FuMO25 scope: background, sweep, dots, then the grid added on top. North is up. */
    private void renderScope(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + SCOPE_W, sy + TEX_H, PANEL_BG);
        g.blit(RADAR, sx, sy, 0, 0, SCOPE_W, SCOPE_H, 358, 328);
        boolean live = this.menu.powered();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (live) {
            g.pose().pushPose();
            g.pose().rotateAround(Axis.ZP.rotationDegrees(System.currentTimeMillis() % 36000000 / 30f),
                    sx + 9 + 145 / 2f, sy + 12 + 145 / 2f, 0f);
            g.blit(SCAN, sx + 9, sy + 12, 0, 0, 145, 145, 145, 145);
            g.pose().popPose();
        }
        ZoneBeaconBlockEntity be = this.menu.beacon();
        double range = this.menu.radius() * 16 + 8;
        if (be != null) {
            int cx = sx + SCOPE_CX, cy = sy + SCOPE_CY;
            for (RadarContact c : contacts()) {
                double dx = (c.x() - (be.getBlockPos().getX() + 0.5)) / range * SCOPE_R;
                double dz = (c.z() - (be.getBlockPos().getZ() + 0.5)) / range * SCOPE_R;
                double len = Math.hypot(dx, dz);
                if (len > SCOPE_R) {
                    dx *= SCOPE_R / len;
                    dz *= SCOPE_R / len;
                }
                int px = cx + (int) Math.round(dx), py = cy + (int) Math.round(dz);
                g.fill(px - 1, py - 1, px + 2, py + 2, 0xFF000000 | c.cls().rgb);
            }
        }
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        g.blit(RADAR, sx + 8, sy + 11, 0, 167, 147, 147, 358, 328);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT, false);
        int r = this.menu.radius();
        int side = 2 * r + 1;
        g.drawCenteredString(this.font, String.valueOf(r), 45, 24, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.cover", side, side), BAR_X, 44, TEXT,
                false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.upkeep", this.menu.upkeep()), BAR_X, 55,
                TEXT, false);
        String state = !this.menu.powered() ? "gui.tacz_sewv.zone_beacon.inactive"
                : mti() ? "gui.tacz_sewv.fpas.radar_active" : "gui.tacz_sewv.zone_beacon.active";
        g.drawString(this.font, Component.translatable(state), BAR_X, 70, this.menu.powered() ? 0x2E7D32 : 0xB71C1C,
                false);
        if (!mti()) return;
        // Legend: one swatch per class, with its live count.
        int[] count = counts();
        RadarContact.Cls[] classes = RadarContact.Cls.values();
        int w = SCOPE_W / classes.length;
        for (RadarContact.Cls cls : classes) {
            int lx = TEX_W + cls.ordinal() * w + 3, ly = SCOPE_H + 2;
            g.fill(lx, ly + 1, lx + 6, ly + 7, 0xFF000000 | cls.rgb);
            g.drawString(this.font, String.valueOf(count[cls.ordinal()]), lx + 9, ly, 0xFFFFFF, false);
        }
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
        if (mti()) {
            int lx = this.leftPos + TEX_W, ly = this.topPos + SCOPE_H + 2;
            int w = SCOPE_W / RadarContact.Cls.values().length;
            if (mouseY >= ly && mouseY < ly + 9 && mouseX >= lx && mouseX < lx + SCOPE_W) {
                RadarContact.Cls cls = RadarContact.Cls.byOrdinal(Math.min((mouseX - lx) / w, RadarContact.Cls.values().length - 1));
                g.renderTooltip(this.font, Component.translatable("gui.tacz_sewv.fpas.cls." + cls.key()), mouseX, mouseY);
            }
        }
        this.renderTooltip(g, mouseX, mouseY);
    }
}
