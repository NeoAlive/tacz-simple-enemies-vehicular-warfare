package com.neoalive.tacz_sewv.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import com.neoalive.tacz_sewv.init.ModSounds;

/** The grace period's start popup: title, wrapped text with the day count in bold, one OK button. */
public class GraceScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PAD = 12;
    private static final int LINE_H = 10;

    private final Component body;
    private List<FormattedCharSequence> lines = List.of();
    private boolean played;
    private int top;
    private int panelH;

    public GraceScreen(int days) {
        super(Component.translatable("tacz_sewv.grace.popup.title"));
        Component n = Component.translatable("tacz_sewv.grace.popup.n_days", days).withStyle(s -> s.withBold(true));
        Component tail = Component.translatable("tacz_sewv.grace.popup.tail", days).withStyle(s -> s.withBold(true));
        this.body = Component.translatable("tacz_sewv.grace.popup.body", n).append("\n\n").append(tail);
    }

    @Override
    protected void init() {
        if (!played) {
            played = true;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.GRACE_START.get(), 1.0F));
        }
        lines = font.split(body, PANEL_W - PAD * 2);
        panelH = PAD + LINE_H + 8 + lines.size() * LINE_H + 8 + 20 + PAD;
        top = (height - panelH) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> onClose())
                .bounds((width - 80) / 2, top + panelH - PAD - 20, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = (width - PANEL_W) / 2;
        g.fill(left, top, left + PANEL_W, top + panelH, 0xE0101010);
        g.renderOutline(left, top, PANEL_W, panelH, 0xFF8A8A8A);
        g.drawCenteredString(font, title, width / 2, top + PAD, 0xFFE0B040);
        int y = top + PAD + LINE_H + 8;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, left + PAD, y, 0xFFDDDDDD);
            y += LINE_H;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
