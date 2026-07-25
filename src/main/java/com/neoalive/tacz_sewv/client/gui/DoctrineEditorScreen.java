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
    private static final int ROW_H = 24;
    private static final int COL_W = 120;
    private static final int STEP_BTN_W = 20;

    private final int[] draftedAxes = new int[Doctrine.Axis.VALUES.length];
    private Button confirmButton;

    private final List<StepperReadout> readouts = new ArrayList<>();

    private record StepperReadout(int cx, int y, int index) {}

    public DoctrineEditorScreen() {
        super(Component.translatable("item.tacz_sewv.doctrine_ledger"));
    }

    @Override
    protected void init() {
        this.readouts.clear();
        int leftX = (this.width - COL_W * 2 - 40) / 2;
        int rightX = leftX + COL_W + 40;
        int startY = (this.height - ROW_H * 4 - 40) / 2;

        for (int i = 0; i < Doctrine.Axis.VALUES.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = col == 0 ? leftX : rightX;
            int y = startY + row * ROW_H;
            
            final int axisIndex = i;
            
            addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustAxis(axisIndex, -1))
                    .bounds(x, y, STEP_BTN_W, 20).build());
                    
            addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustAxis(axisIndex, 1))
                    .bounds(x + COL_W - STEP_BTN_W, y, STEP_BTN_W, 20).build());
                    
            this.readouts.add(new StepperReadout(x + COL_W / 2, y, axisIndex));
        }

        int btnY = startY + ROW_H * 4 + 10;
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
        
        int startY = (this.height - ROW_H * 4 - 40) / 2;
        g.drawCenteredString(this.font, this.title, this.width / 2, startY - 25, 0xFFFFFF);
        
        int remaining = MAX_POINTS - getTotalAllocated();
        int color = remaining == 0 ? 0x55FF55 : 0xFFFFFF;
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.doctrine.remaining", remaining), 
                this.width / 2, startY - 12, color);

        for (StepperReadout r : this.readouts) {
            Doctrine.Axis axis = Doctrine.Axis.VALUES[r.index()];
            String name = Component.translatable("gui.tacz_sewv.doctrine.axis." + axis.key).getString();
            
            g.drawCenteredString(this.font, name, r.cx(), r.y() - 10, 0xFFA0A0A0);
            
            int value = this.draftedAxes[r.index()];
            String displayVal = (value > 0 ? "+" : "") + value;
            g.drawCenteredString(this.font, displayVal, r.cx(), r.y() + 6, 0xFFFFFF);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
