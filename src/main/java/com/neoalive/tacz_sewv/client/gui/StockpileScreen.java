package com.neoalive.tacz_sewv.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.inventory.StockpileMenu;

public class StockpileScreen extends AbstractContainerScreen<StockpileMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/stockpile_inventory.png");

    public StockpileScreen(StockpileMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 172;
        this.imageHeight = 238;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, 172, 238);
    }

    /** The art has no header or label rows; the default labels would print over the slots. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
