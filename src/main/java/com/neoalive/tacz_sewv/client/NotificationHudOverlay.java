package com.neoalive.tacz_sewv.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.gui.GuiFit;
import com.neoalive.tacz_sewv.notify.NotificationKind;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class NotificationHudOverlay {

    private NotificationHudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        NotificationHud.tick(mc.isPaused(), System.currentTimeMillis());
        drawOver(event.getGuiGraphics());
    }

    /**
     * Draws the current ticket stack, if any. Split from the event so a fullscreen screen that hides
     * the HUD (the world map) can repeat it on top; the timer is only advanced from the GUI event.
     */
    public static void drawOver(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !NotificationHud.visible()) return;

        List<NotificationHud.Slot> slots = NotificationHud.slots();
        if (slots.isEmpty()) return;

        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        float scale = Math.min(NotificationHud.DISPLAY_SCALE,
                GuiFit.fitScale(NotificationHud.TICKET_W, screenW));
        int drawW = Math.round(NotificationHud.TICKET_W * scale);
        int x = (screenW - drawW) / 2;

        for (int i = 0; i < slots.size(); i++) {
            NotificationHud.Slot slot = slots.get(i);
            NotificationHud.Item item = slot.item;
            if (item == null) continue;
            int y = Math.round(slot.drawY(i) * scale);
            drawTicket(g, font, x, y, scale, item, slot.barT());
        }
    }

    private static void drawTicket(GuiGraphics g, Font font, int screenX, int screenY, float scale,
                                   NotificationHud.Item item, float barT) {
        NotificationKind kind = item.kind;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(screenX, screenY, 0);
        pose.scale(scale, scale, 1f);

        int w = NotificationHud.TICKET_W;
        int h = NotificationHud.TICKET_H;
        int headerH = NotificationHud.HEADER_H;
        int timerH = NotificationHud.TIMER_H;

        // Depth underlay.
        g.fill(1, 1, w + 1, h + 1, NotificationHud.SHADOW);

        // Header chip.
        g.fill(0, 0, w, headerH, kind.accentArgb());

        // Icon tile.
        int tile = NotificationHud.ICON_TILE;
        int tileX = NotificationHud.ICON_PAD;
        int tileY = (headerH - tile) / 2;
        g.fill(tileX, tileY, tileX + tile, tileY + tile, kind.iconTileArgb());
        String icon = kind.icon();
        int iconW = font.width(icon);
        g.drawString(font, icon,
                tileX + (tile - iconW) / 2,
                tileY + (tile - font.lineHeight) / 2 + 1,
                NotificationHud.ICON_COLOR, false);

        // Title on the header.
        int titleX = tileX + tile + NotificationHud.TITLE_PAD_X;
        int titleBudget = Math.max(1, w - titleX - 6);
        String title = font.plainSubstrByWidth(item.title.getString(), titleBudget);
        g.drawString(font, title, titleX, (headerH - font.lineHeight) / 2 + 1,
                NotificationHud.TITLE_COLOR, false);

        // Hairline timer under the header (full → empty left-to-right).
        int timerY = headerH;
        g.fill(0, timerY, w, timerY + timerH, kind.iconTileArgb());
        int barW = Math.max(0, Math.round(Mth.lerp(barT, w, 0)));
        if (barW > 0) {
            g.fill(0, timerY, barW, timerY + timerH, kind.accentArgb());
        }

        // Body band.
        int bodyTop = headerH + timerH;
        g.fill(0, bodyTop, w, h, NotificationHud.BODY_BG);
        int textX = NotificationHud.BODY_PAD_X;
        int textY = bodyTop + NotificationHud.BODY_PAD_Y;
        int textMaxW = w - textX - NotificationHud.BODY_PAD_X;
        int lines = 0;
        for (FormattedCharSequence line : font.split(item.body, textMaxW)) {
            if (lines >= NotificationHud.MAX_BODY_LINES) break;
            g.drawString(font, line, textX, textY, NotificationHud.BODY_COLOR, false);
            textY += font.lineHeight;
            lines++;
        }

        pose.popPose();
    }
}
