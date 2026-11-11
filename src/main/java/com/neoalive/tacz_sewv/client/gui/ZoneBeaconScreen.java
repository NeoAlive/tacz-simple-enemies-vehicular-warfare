package com.neoalive.tacz_sewv.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.inventory.ZoneBeaconMenu;

/** FPAS panel over {@code fpas_inventory.png}; slot positions live in {@link ZoneBeaconMenu}. */
public class ZoneBeaconScreen extends AbstractContainerScreen<ZoneBeaconMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/fpas_inventory.png");
    private static final int TEX_W = 173;
    private static final int TEX_H = 200;
    private static final int BAR_X = 7, BAR_Y = 84, BAR_W = 136, BAR_H = 8;
    private static final int BAR_BG = 0xFF373737;
    private static final int BAR_ON = 0xFFD8402A;
    private static final int TEXT = 0x404040;

    private Button smaller;
    private Button larger;

    public ZoneBeaconScreen(ZoneBeaconMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = TEX_W;
        this.imageHeight = TEX_H;
        this.titleLabelX = ZoneBeaconMenu.INV_X;
        this.inventoryLabelX = ZoneBeaconMenu.INV_X;
        this.inventoryLabelY = ZoneBeaconMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos, y = this.topPos;
        this.smaller = addRenderableWidget(Button.builder(Component.literal("-"), b -> click(ZoneBeaconMenu.BUTTON_SMALLER))
                .bounds(x + 7, y + 18, 20, 20).build());
        this.larger = addRenderableWidget(Button.builder(Component.literal("+"), b -> click(ZoneBeaconMenu.BUTTON_LARGER))
                .bounds(x + 63, y + 18, 20, 20).build());
    }

    private void click(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        this.smaller.active = this.menu.radius() > 2;
        this.larger.active = this.menu.radius() < this.menu.maxRadius();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos, y = this.topPos;
        g.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, TEX_W, TEX_H);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + BAR_W, y + BAR_Y + BAR_H, BAR_BG);
        int cap = Math.max(1, this.menu.capacity());
        int fill = (int) ((long) BAR_W * Math.min(cap, this.menu.energy()) / cap);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + fill, y + BAR_Y + BAR_H, BAR_ON);
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
        Component status = Component.translatable(this.menu.powered()
                ? "gui.tacz_sewv.zone_beacon.active" : "gui.tacz_sewv.zone_beacon.inactive");
        g.drawString(this.font, status, BAR_X, 70, this.menu.powered() ? 0x2E7D32 : 0xB71C1C, false);
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
        this.renderTooltip(g, mouseX, mouseY);
    }
}
