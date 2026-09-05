package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class NotificationHudOverlay {

    private NotificationHudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        NotificationHud.tick(mc.isPaused(), System.currentTimeMillis());
        if (mc.options.hideGui || !NotificationHud.visible()) return;

        NotificationHud.Item item = NotificationHud.front();
        if (item == null) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int x = (screenW - NotificationHud.TEX_W) / 2;
        int y = NotificationHud.drawY();

        g.blit(NotificationHud.texture(), x, y, 0, 0,
                NotificationHud.TEX_W, NotificationHud.TEX_H,
                NotificationHud.TEX_W, NotificationHud.TEX_H);

        float t = NotificationHud.barT();
        int barW = Math.max(NotificationHud.BAR_W_EMPTY,
                Math.round(Mth.lerp(t, NotificationHud.BAR_W_FULL, NotificationHud.BAR_W_EMPTY)));
        g.fill(x + NotificationHud.BAR_X, y + NotificationHud.BAR_Y,
                x + NotificationHud.BAR_X + barW, y + NotificationHud.BAR_Y + NotificationHud.BAR_H,
                NotificationHud.BAR_COLOR);

        int tx = x + NotificationHud.TEXT_X;
        int ty = y + NotificationHud.TEXT_Y;
        // Title stays single-line (scaled); body wraps to new lines instead of being trimmed.
        int titleBudget = Math.max(1, (int) (NotificationHud.TEXT_MAX_W / NotificationHud.TITLE_SCALE));
        String title = font.plainSubstrByWidth(item.title().getString(), titleBudget);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(tx, ty, 0);
        pose.scale(NotificationHud.TITLE_SCALE, NotificationHud.TITLE_SCALE, 1f);
        g.drawString(font, title, 0, 0, NotificationHud.CYAN, false);
        pose.popPose();

        int bodyY = ty + Math.round(font.lineHeight * NotificationHud.TITLE_SCALE);
        for (var line : font.split(item.body(), NotificationHud.TEXT_MAX_W)) {
            g.drawString(font, line, tx, bodyY, NotificationHud.BODY_COLOR, false);
            bodyY += font.lineHeight;
        }
    }
}
