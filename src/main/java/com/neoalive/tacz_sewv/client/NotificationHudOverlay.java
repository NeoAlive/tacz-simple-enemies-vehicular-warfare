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
            boolean front = i == stackSize - 1;
            int y = Math.round(slot.drawY(i, stackSize) * scale);
            drawTicket(g, font, x, y, scale, item, slot, front);
        }
    }

    private static void drawTicket(GuiGraphics g, Font font, int screenX, int screenY, float scale,
                                   NotificationHud.Item item, NotificationHud.Slot slot, boolean front) {
        NotificationKind kind = item.kind;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(screenX, screenY, 0);
        pose.scale(scale, scale, 1f);

        int w = NotificationHud.TICKET_W;
        int h = NotificationHud.TICKET_H;
        int railW = NotificationHud.RAIL_W;

        if (!front) {
            // Opaque kind strip only — no translucent panel/text muddying the front card.
            g.fill(0, 0, w, h, kind.accentArgb());
            pose.popPose();
            return;
        }

        float barT = slot.barT();
        float textReveal = slot.textReveal();
        float boxReveal = slot.boxReveal();

        // Soft underlay under the settled card footprint.
        g.fill(1, 1, w + 1, h + 1, NotificationHud.SHADOW);

        // Kind rail (top of hierarchy) — always full; timer depletes top → bottom.
        g.fill(0, 0, railW, h, kind.iconTileArgb());
        int filledH = Math.max(0, Math.round(Mth.lerp(barT, h, 0)));
        if (filledH > 0) {
            g.fill(0, 0, railW, filledH, kind.accentArgb());
        }

        String icon = kind.icon();
        int iconW = font.width(icon);
        g.drawString(font, icon, (railW - iconW) / 2, 2, NotificationHud.ICON_COLOR, false);

        int contentW = w - railW;

        // Box (bottom of hierarchy): L→R wipe from the rail, lags the text.
        if (boxReveal > 0.001f) {
            int boxW = Math.max(1, Math.round(contentW * boxReveal));
            g.fill(railW, 0, railW + boxW, h, NotificationHud.BODY_BG);
        }

        // Text (middle): L→R scissor wipe from the rail so it pops out ahead of the box.
        if (textReveal > 0.001f) {
            int revealPx = Math.max(1, Math.round(contentW * textReveal * scale));
            int scissorX = screenX + Math.round(railW * scale);
            int scissorY = screenY;
            int scissorH = Math.round(h * scale);
            g.enableScissor(scissorX, scissorY, scissorX + revealPx, scissorY + scissorH);

            int contentX = railW + NotificationHud.TITLE_PAD_X;
            int titleBudget = Math.max(1, w - contentX - 6);
            String title = font.plainSubstrByWidth(item.title.getString(), titleBudget);
            g.drawString(font, title, contentX, 3, NotificationHud.TITLE_COLOR, false);

            int textX = railW + NotificationHud.BODY_PAD_X;
            int textY = 3 + font.lineHeight + NotificationHud.BODY_PAD_Y;
            int textMaxW = w - textX - NotificationHud.BODY_PAD_X;
            int lines = 0;
            for (FormattedCharSequence line : font.split(item.body, textMaxW)) {
                if (lines >= NotificationHud.MAX_BODY_LINES) break;
                g.drawString(font, line, textX, textY, NotificationHud.BODY_COLOR, false);
                textY += font.lineHeight;
                lines++;
            }

            g.disableScissor();
        }

        pose.popPose();
    }
}
