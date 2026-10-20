package com.neoalive.tacz_sewv.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * NeoForge port: keeps the 1.20 background contract for this mod's screens.
 *
 * <p>On 1.20 a screen called {@code renderBackground(g)} at the top of its own {@code render}, drew its
 * panels, then called {@code super.render} for the widgets. On 1.21 {@code Screen.render} draws the
 * background itself, so that order would paint (and blur) the background over the panels — and a
 * second blur in one frame throws. Extending this instead of {@link Screen} keeps every screen's
 * {@code render} as it is on main: the explicit call draws the background, and the pass
 * {@code Screen.render} makes afterwards is skipped. A screen that never makes the explicit call
 * gets the vanilla background from {@code Screen.render} as normal.
 */
public abstract class SewvScreen extends Screen {

    private boolean backgroundDrawn;

    protected SewvScreen(Component title) {
        super(title);
    }

    /** The 1.20 call shape: draws the background now, once for this frame. */
    public void renderBackground(GuiGraphics graphics) {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        super.renderBackground(graphics, 0, 0, partialTick);
        this.backgroundDrawn = true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.backgroundDrawn) {
            this.backgroundDrawn = false;
            return;
        }
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }
}
