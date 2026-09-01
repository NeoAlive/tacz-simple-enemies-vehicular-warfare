package com.neoalive.tacz_sewv.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketAssignFobLiving;
import com.neoalive.tacz_sewv.network.PacketAssignFobVehicle;
import com.neoalive.tacz_sewv.network.PacketFobData;
import com.neoalive.tacz_sewv.network.PacketPlayFobAlarm;
import com.neoalive.tacz_sewv.network.PacketRouteToFob;
import com.neoalive.tacz_sewv.network.PacketToggleFobCommand;

public class QuartersBenchScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int ROW_H = 20;

    private FobGuiSnapshot snapshot;
    private int livingScroll;
    private int vehicleScroll;

    public QuartersBenchScreen(FobGuiSnapshot snapshot) {
        super(Component.translatable("gui.tacz_sewv.fob.title"));
        this.snapshot = snapshot;
    }

    public void applySnapshot(FobGuiSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int left = cx - PANEL_W / 2;
        int y = 48;

        this.addRenderableWidget(Button.builder(
                Component.translatable(this.snapshot.fobCommandActive()
                        ? "gui.tacz_sewv.fob.command_on" : "gui.tacz_sewv.fob.command_off"),
                b -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketToggleFobCommand(this.snapshot.commandPos())))
                .bounds(left, y, 170, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.fob.test_alarm"),
                b -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketPlayFobAlarm(this.snapshot.commandPos())))
                .bounds(left + 180, y, 170, 20).build());

        y += 28;
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.fob.route"),
                b -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketRouteToFob(this.snapshot.commandPos())))
                .bounds(left, y, 170, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.fob.refresh"),
                b -> NetworkHandler.CHANNEL.sendToServer(
                        PacketFobData.request(this.snapshot.commandPos())))
                .bounds(left + 180, y, 170, 20).build());

        y += 36;
        List<FobGuiSnapshot.LivingRow> living = visibleLiving();
        for (int i = 0; i < Math.min(6, living.size()); i++) {
            FobGuiSnapshot.LivingRow row = living.get(i);
            int rowY = y + i * ROW_H;
            this.addRenderableWidget(Button.builder(
                    Component.literal((row.assigned() ? "[x] " : "[ ] ") + trim(row.name(), 22)),
                    b -> NetworkHandler.CHANNEL.sendToServer(
                            new PacketAssignFobLiving(this.snapshot.commandPos(), row.uuid(), !row.assigned())))
                    .bounds(left, rowY, PANEL_W, 18).build());
        }

        y += 6 * ROW_H + 16;
        List<FobGuiSnapshot.VehicleRow> vehicles = visibleVehicles();
        for (int i = 0; i < Math.min(5, vehicles.size()); i++) {
            FobGuiSnapshot.VehicleRow row = vehicles.get(i);
            int rowY = y + i * ROW_H;
            String label = (row.assigned() ? "[x] " : "[ ] ") + trim(row.registryId(), 18)
                    + " @ " + row.positionText();
            this.addRenderableWidget(Button.builder(Component.literal(trim(label, 42)),
                    b -> NetworkHandler.CHANNEL.sendToServer(
                            new PacketAssignFobVehicle(this.snapshot.commandPos(), row.uuid(), !row.assigned())))
                    .bounds(left, rowY, PANEL_W, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                b -> this.onClose()).bounds(cx - 50, this.height - 28, 100, 20).build());
    }

    private List<FobGuiSnapshot.LivingRow> visibleLiving() {
        List<FobGuiSnapshot.LivingRow> all = this.snapshot.living();
        if (livingScroll >= all.size()) livingScroll = 0;
        return all.subList(livingScroll, Math.min(all.size(), livingScroll + 6));
    }

    private List<FobGuiSnapshot.VehicleRow> visibleVehicles() {
        List<FobGuiSnapshot.VehicleRow> all = this.snapshot.vehicles();
        if (vehicleScroll >= all.size()) vehicleScroll = 0;
        return all.subList(vehicleScroll, Math.min(all.size(), vehicleScroll + 5));
    }

    private static String trim(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 12, 0xFFFFFF);
        String status = this.snapshot.valid()
                ? Component.translatable("gui.tacz_sewv.fob.valid").getString()
                : this.snapshot.invalidReason();
        graphics.drawCenteredString(this.font, status, cx, 24,
                this.snapshot.valid() ? 0x55FF55 : 0xFF5555);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.fob.threat", this.snapshot.threatScore(),
                        this.snapshot.scrambleActive()).getString(),
                cx - PANEL_W / 2, 36, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.tacz_sewv.fob.units").getString(),
                cx - PANEL_W / 2, 88, 0xAAAAAA, false);
        graphics.drawString(this.font, Component.translatable("gui.tacz_sewv.fob.vehicles").getString(),
                cx - PANEL_W / 2, 88 + 6 * ROW_H + 12, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
