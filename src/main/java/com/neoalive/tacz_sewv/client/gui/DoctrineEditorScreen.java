package com.neoalive.tacz_sewv.client.gui;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveDoctrine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class DoctrineEditorScreen extends Screen {

    private static final int MAX_POINTS = 20;
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 36;
    private static final int GAP = 8;
    private static final int STEP_BTN_W = 20;

    private final int[] draftedAxes = new int[Doctrine.Axis.VALUES.length];
    private Button confirmButton;

    private final List<StepperReadout> readouts = new ArrayList<>();

    private record StepperReadout(int x, int y, int index) {}

    public DoctrineEditorScreen() {
        super(Component.translatable("item.tacz_sewv.doctrine_ledger"));
    }

    @Override
    protected void init() {
        this.readouts.clear();
        int totalGridHeight = Doctrine.Axis.VALUES.length * PANEL_H + (Doctrine.Axis.VALUES.length - 1) * GAP;
        int startY = Math.max(50, (this.height - totalGridHeight) / 2);
        int startX = (this.width - PANEL_W) / 2;

        for (int i = 0; i < Doctrine.Axis.VALUES.length; i++) {
            int y = startY + i * (PANEL_H + GAP);
            
            final int axisIndex = i;
            
            int btnY = y + 8;
            int btnMinusX = startX + PANEL_W - 12 - 20 - 30 - 20;
            int btnPlusX = startX + PANEL_W - 12 - 20;
            
            addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustAxis(axisIndex, -1))
                    .bounds(btnMinusX, btnY, STEP_BTN_W, 20).build());
                    
            addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustAxis(axisIndex, 1))
                    .bounds(btnPlusX, btnY, STEP_BTN_W, 20).build());
                    
            this.readouts.add(new StepperReadout(startX, y, axisIndex));
        }

        int btnY = startY + totalGridHeight + 20;
        int centerBtnX = this.width / 2;

        this.confirmButton = addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.doctrine.confirm"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new PacketSaveDoctrine(this.draftedAxes));
            onClose();
        }).bounds(centerBtnX - 105, btnY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(centerBtnX + 5, btnY, 100, 20).build());

        updateConfirmButton();
    }

    private void adjustAxis(int index, int delta) {
        int oldVal = this.draftedAxes[index];
        int newVal = Math.max(-Doctrine.AXIS_LIMIT, Math.min(Doctrine.AXIS_LIMIT, oldVal + delta));
        
        if (newVal != oldVal) {
            int currentTotal = getTotalAllocated();
            int newTotal = currentTotal - Math.abs(oldVal) + Math.abs(newVal);
            
            // Only allow if we aren't exceeding the point limit
            if (newTotal <= MAX_POINTS) {
                this.draftedAxes[index] = newVal;
                updateConfirmButton();
            }
        }
    }

    private int getTotalAllocated() {
        int total = 0;
        for (int val : this.draftedAxes) {
            total += Math.abs(val);
        }
        return total;
    }

    private void updateConfirmButton() {
        this.confirmButton.active = (getTotalAllocated() == MAX_POINTS);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        
        int totalGridHeight = Doctrine.Axis.VALUES.length * PANEL_H + (Doctrine.Axis.VALUES.length - 1) * GAP;
        int startY = Math.max(50, (this.height - totalGridHeight) / 2);
        
        g.drawCenteredString(this.font, this.title, this.width / 2, startY - 40, 0xFFFFFF);
        
        int remaining = MAX_POINTS - getTotalAllocated();
        int color = remaining == 0 ? 0x55FF55 : 0xFFFFFF;
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.doctrine.remaining", remaining), 
                this.width / 2, startY - 24, color);

        for (StepperReadout r : this.readouts) {
            // Panel background and border
            g.fill(r.x(), r.y(), r.x() + PANEL_W, r.y() + PANEL_H, 0x44000000);
            g.renderOutline(r.x(), r.y(), PANEL_W, PANEL_H, 0xFF444444);
            
            Doctrine.Axis axis = Doctrine.Axis.VALUES[r.index()];
            String name = Component.translatable("gui.tacz_sewv.doctrine.axis." + axis.key).getString();
            
            g.drawString(this.font, name, r.x() + 12, r.y() + 7, 0xFFFFFF, true);
            
            String desc = axis.description;
            int maxDescWidth = PANEL_W - 90 - 12 - 10;
            String truncatedDesc = this.font.plainSubstrByWidth(desc, maxDescWidth);
            if (!truncatedDesc.equals(desc)) truncatedDesc += "...";
            
            g.drawString(this.font, truncatedDesc, r.x() + 12, r.y() + 20, 0xFFAAAAAA, true);
            
            int value = this.draftedAxes[r.index()];
            String displayVal = (value > 0 ? "+" : "") + value;
            g.drawCenteredString(this.font, displayVal, r.x() + PANEL_W - 47, r.y() + 14, 0xFFFFFF);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
