package com.neoalive.tacz_sewv.client.invasion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.invasion.InvasionHud;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class InvasionHudOverlay {

    private static final float LERP_SPEED = 8f;
    private static final int WIDGET_W = 280;
    private static final int ICON = 14;
    private static final int TOP_Y = 22;
    private static final int LABEL_GAP = 11;
    private static final int BAR_H = 3;
    private static final int BAR_GAP = 2;
    private static final String HOUSE = "\u2302"; // U+2302 HOUSE

    private InvasionHudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        InvasionHud.Snapshot snap = InvasionHudClient.snapshot();
        if (snap == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int n = snap.slots().size();
        if (n < 2) return;

        float xStart = (screenW - WIDGET_W) / 2f;
        float step = (float) WIDGET_W / (n - 1);

        float[] target = InvasionHudClient.targetX();
        float[] current = InvasionHudClient.currentX();
        if (target == null || current == null || target.length != n) return;

        for (int i = 0; i < n; i++) {
            target[i] = xStart + i * step;
        }

        float dt = InvasionHudClient.deltaSeconds();
        if (!InvasionHudClient.entranceDone()) {
            float alpha = 1f - (float) Math.exp(-LERP_SPEED * dt);
            boolean anyNaN = false;
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(current[i])) {
                    current[i] = xStart + WIDGET_W / 2f;
                    anyNaN = true;
                }
                current[i] += (target[i] - current[i]) * alpha;
            }
            if (!anyNaN && InvasionHudClient.settled(current, target)) {
                System.arraycopy(target, 0, current, 0, n);
                InvasionHudClient.markEntranceDone();
            }
        } else {
            System.arraycopy(target, 0, current, 0, n);
        }

        long flashPhase = System.currentTimeMillis() / 250L;
        boolean flashOn = (flashPhase & 1L) == 0L;

        int pointOrdinal = 0;
        for (int i = 0; i < n; i++) {
            InvasionHud.Slot slot = snap.slots().get(i);
            InvasionHud.SlotState state = snap.states().get(i);
            int cx = Math.round(current[i]);
            int cy = TOP_Y;
            int fill = fillColor(snap, state, flashOn);

            String label = slot.kind() == InvasionHud.KIND_BASE
                    ? HOUSE
                    : Integer.toString(++pointOrdinal);
            int labelW = font.width(label);
            g.drawString(font, label, cx - labelW / 2, cy - LABEL_GAP, 0xFFFFFFFF, true);

            drawIcon(g, cx, cy, fill);

            if (state.capturing()) {
                int barW = ICON + 4;
                int bx = cx - barW / 2;
                int by = cy + ICON + BAR_GAP;
                g.fill(bx, by, bx + barW, by + BAR_H, 0x88000000);
                int filled = Math.max(1, Math.round(barW * Math.max(0f, Math.min(1f, state.progress()))));
                int barColor = 0xFF000000 | sideColor(snap, state.conquerSide());
                g.fill(bx, by, bx + filled, by + BAR_H, barColor);
            }
        }
    }

    private static int fillColor(InvasionHud.Snapshot snap, InvasionHud.SlotState state, boolean flashOn) {
        if (state.capturing()) {
            int conquer = sideColor(snap, state.conquerSide());
            return flashOn ? 0xFFFFFFFF : (0xFF000000 | conquer);
        }
        return 0xFF000000 | sideColor(snap, state.ownerSide());
    }

    private static int sideColor(InvasionHud.Snapshot snap, byte side) {
        return switch (side) {
            case InvasionHud.SIDE_A -> snap.colorA();
            case InvasionHud.SIDE_B -> snap.colorB();
            default -> snap.colorNeutral();
        };
    }

    private static void drawIcon(GuiGraphics g, int cx, int cy, int argb) {
        int x0 = cx - ICON / 2;
        int y0 = cy;
        g.fill(x0 - 1, y0 - 1, x0 + ICON + 1, y0 + ICON + 1, 0xAA000000);
        g.fill(x0, y0, x0 + ICON, y0 + ICON, argb);
    }
}
