package com.neoalive.tacz_sewv.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.neoalive.tacz_sewv.inventory.ZoneBeaconMenu;

/** Zone beacon panel. Drawn with fills until the GUI art exists. */
public class ZoneBeaconScreen extends AbstractContainerScreen<ZoneBeaconMenu> {

    private static final int PANEL = 0xFFC6C6C6;
    private static final int EDGE_LIGHT = 0xFFFFFFFF;
    private static final int EDGE_DARK = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int BAR_BG = 0xFF373737;
    private static final int BAR_ON = 0xFFD8402A;
    private static final int TEXT = 0x404040;

    private Button smaller;
    private Button larger;

    public ZoneBeaconScreen(ZoneBeaconMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = ZoneBeaconMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos, y = this.topPos;
        this.smaller = addRenderableWidget(Button.builder(Component.literal("-"), b -> click(ZoneBeaconMenu.BUTTON_SMALLER))
                .bounds(x + 8, y + 18, 20, 20).build());
        this.larger = addRenderableWidget(Button.builder(Component.literal("+"), b -> click(ZoneBeaconMenu.BUTTON_LARGER))
                .bounds(x + 64, y + 18, 20, 20).build());
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
        g.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);
        g.fill(x, y, x + this.imageWidth, y + 1, EDGE_LIGHT);
        g.fill(x, y, x + 1, y + this.imageHeight, EDGE_LIGHT);
        g.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, EDGE_DARK);
        g.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, EDGE_DARK);
        for (Slot s : this.menu.slots) {
            g.fill(x + s.x - 1, y + s.y - 1, x + s.x + 17, y + s.y + 17, SLOT_BG);
        }
        int barX = x + 8, barY = y + 44, barW = 136, barH = 8;
        g.fill(barX, barY, barX + barW, barY + barH, BAR_BG);
        int cap = Math.max(1, this.menu.capacity());
        int fill = (int) ((long) barW * Math.min(cap, this.menu.energy()) / cap);
        g.fill(barX, barY, barX + fill, barY + barH, BAR_ON);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT, false);
        int r = this.menu.radius();
        int side = 2 * r + 1;
        g.drawCenteredString(this.font, String.valueOf(r), 46, 24, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.cover", side, side), 90, 20, TEXT,
                false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.upkeep", this.menu.upkeep()), 90, 30,
                TEXT, false);
        Component status = Component.translatable(this.menu.powered()
                ? "gui.tacz_sewv.zone_beacon.active" : "gui.tacz_sewv.zone_beacon.inactive");
        g.drawString(this.font, status, 8, 56, this.menu.powered() ? 0x2E7D32 : 0xB71C1C, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int barX = this.leftPos + 8, barY = this.topPos + 44;
        if (mouseX >= barX && mouseX < barX + 136 && mouseY >= barY && mouseY < barY + 8) {
            g.renderTooltip(this.font, Component.translatable("gui.tacz_sewv.zone_beacon.energy",
                    this.menu.energy(), this.menu.capacity()), mouseX, mouseY);
        }
        this.renderTooltip(g, mouseX, mouseY);
    }
}
