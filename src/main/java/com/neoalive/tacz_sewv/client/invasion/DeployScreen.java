package com.neoalive.tacz_sewv.client.invasion;

import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.client.gui.GuiFit;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketInvasionDeployChoice;
import com.neoalive.tacz_sewv.platform.SewvScreen;

/**
 * "How do you deploy?" — one button per option the base offers. The server holds the offer and
 * takes the first option when its timer runs out, so the countdown here is only a display; closing
 * the screen answers with the first option at once rather than leaving the player waiting on it.
 */
public class DeployScreen extends SewvScreen {

    private static final int BTN_W = 200;
    private static final int BTN_H = 24;

    private final List<ForceKind> options;
    private final long deadlineMs;
    private boolean answered;

    public DeployScreen(List<ForceKind> options, int seconds) {
        super(Component.translatable("gui.tacz_sewv.invasion.deploy.title"));
        this.options = List.copyOf(options);
        this.deadlineMs = Util.getMillis() + seconds * 1000L;
    }

    @Override
    protected void init() {
        int w = GuiFit.panelW(BTN_W, this.width);
        int x = (this.width - w) / 2;
        int y = this.height / 2 - this.options.size() * (BTN_H + 4) / 2;
        for (ForceKind kind : this.options) {
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.tacz_sewv.invasion.deploy.as." + kind.key()),
                    b -> choose(kind)).bounds(x, y, w, BTN_H).build());
            y += BTN_H + 4;
        }
    }

    private void choose(ForceKind kind) {
        if (this.answered) return;
        this.answered = true;
        NetworkHandler.CHANNEL.sendToServer(new PacketInvasionDeployChoice(kind));
        super.onClose();
    }

    @Override
    public void onClose() {
        choose(this.options.get(0));
    }

    @Override
    public void tick() {
        // The server times the offer out on its own; this only closes a screen nobody answered.
        if (Util.getMillis() > this.deadlineMs + 1000L) {
            this.answered = true;
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        super.render(g, mx, my, partialTick);
        int top = this.height / 2 - this.options.size() * (BTN_H + 4) / 2;
        g.drawCenteredString(this.font, this.title, this.width / 2, top - 30, 0xFFFFFF);
        long left = Math.max(0, (this.deadlineMs - Util.getMillis() + 999) / 1000);
        g.drawCenteredString(this.font,
                Component.translatable("gui.tacz_sewv.invasion.deploy.countdown", left,
                        Component.translatable("gui.tacz_sewv.invasion.deploy.as." + this.options.get(0).key())),
                this.width / 2, top - 16, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
