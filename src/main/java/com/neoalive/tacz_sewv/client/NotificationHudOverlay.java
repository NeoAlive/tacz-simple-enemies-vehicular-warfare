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
        int stackSize = slots.size();

        // Back → front so peeks show the kind colour of tickets underneath.
        for (int i = 0; i < stackSize; i++) {
            NotificationHud.Slot slot = slots.get(i);
            NotificationHud.Item item = slot.item;
            if (item == null) continue;
            int y = Math.round(slot.drawY(i, stackSize) * scale);
            drawTicket(g, font, x, y, scale, item, slot.barT(), slot.textAlpha());
        }
    }

    private static void drawTicket(GuiGraphics g, Font font, int screenX, int screenY, float scale,
                                   NotificationHud.Item item, float barT, float textAlpha) {
        NotificationKind kind = item.kind;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(screenX, screenY, 0);
        pose.scale(scale, scale, 1f);

        int w = NotificationHud.TICKET_W;
        int h = NotificationHud.TICKET_H;
        int railW = NotificationHud.RAIL_W;

        // Soft underlay.
        g.fill(1, 1, w + 1, h + 1, NotificationHud.SHADOW);

        // Quiet content panel (everything right of the rail).
        g.fill(railW, 0, w, h, NotificationHud.BODY_BG);

        // Kind rail track (dim) + timer fill top → bottom.
        g.fill(0, 0, railW, h, kind.iconTileArgb());
        int filledH = Math.max(0, Math.round(Mth.lerp(barT, h, 0)));
        if (filledH > 0) {
            g.fill(0, 0, railW, filledH, kind.accentArgb());
        }

        // Icon in the upmost rail cell.
        String icon = kind.icon();
        int iconW = font.width(icon);
        int iconX = (railW - iconW) / 2;
        int iconY = 2;
        g.drawString(font, icon, iconX, iconY, NotificationHud.ICON_COLOR, false);

        // Title + body fade in after the card lands.
        if (textAlpha > 0.01f) {
            int titleColor = withAlpha(NotificationHud.TITLE_COLOR, textAlpha);
            int bodyColor = withAlpha(NotificationHud.BODY_COLOR, textAlpha);

            int contentX = railW + NotificationHud.TITLE_PAD_X;
            int titleBudget = Math.max(1, w - contentX - 6);
            String title = font.plainSubstrByWidth(item.title.getString(), titleBudget);
            g.drawString(font, title, contentX, 3, titleColor, false);

            int textX = railW + NotificationHud.BODY_PAD_X;
            int textY = 3 + font.lineHeight + NotificationHud.BODY_PAD_Y;
            int textMaxW = w - textX - NotificationHud.BODY_PAD_X;
            int lines = 0;
            for (FormattedCharSequence line : font.split(item.body, textMaxW)) {
                if (lines >= NotificationHud.MAX_BODY_LINES) break;
                g.drawString(font, line, textX, textY, bodyColor, false);
                textY += font.lineHeight;
                lines++;
            }
        }

        pose.popPose();
    }

    private static int withAlpha(int argb, float alpha) {
        int baseA = (argb >>> 24) & 0xFF;
        int newA = Mth.clamp(Math.round(baseA * alpha), 0, 255);
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
