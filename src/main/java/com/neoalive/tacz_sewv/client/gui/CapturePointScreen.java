package com.neoalive.tacz_sewv.client.gui;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveCapturePoint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Op config UI for a capture_point. Snapshot edited locally; Save pushes {@link PacketSaveCapturePoint}. */
public class CapturePointScreen extends Screen {

    private static final int PANEL_W = 280;

    private final BlockPos pos;
    private int pointId;
    private boolean showBillboard;
    private double billboardYOffset;
    private int timeToCaptureSeconds;
    private int radiusInBlocks;
    private String ownedTeam;
    private final List<String> teams;

    private EditBox pointIdBox;
    private EditBox yOffsetBox;
    private EditBox timeBox;
    private EditBox radiusBox;
    private Button billboardButton;
    private Button ownedTeamButton;

    public CapturePointScreen(BlockPos pos, int pointId, boolean showBillboard, double billboardYOffset,
                              int timeToCaptureSeconds, int radiusInBlocks, String ownedTeam,
                              List<String> teams) {
        super(Component.translatable("gui.tacz_sewv.invasion.capture_point.title"));
        this.pos = pos;
        this.pointId = pointId;
        this.showBillboard = showBillboard;
        this.billboardYOffset = billboardYOffset;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.teams = new ArrayList<>(teams);
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int y = 40;

        this.pointIdBox = field(left, y, "Point ID", Integer.toString(this.pointId));
        y += 28;
        this.timeBox = field(left, y, "Time (s)", Integer.toString(this.timeToCaptureSeconds));
        y += 28;
        this.radiusBox = field(left, y, "Radius", Integer.toString(this.radiusInBlocks));
        y += 28;
        this.yOffsetBox = field(left, y, "Billboard Y", Double.toString(this.billboardYOffset));
        y += 28;

        this.billboardButton = addRenderableWidget(Button.builder(billboardLabel(), b -> {
            this.showBillboard = !this.showBillboard;
            this.billboardButton.setMessage(billboardLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 24;

        this.ownedTeamButton = addRenderableWidget(Button.builder(ownedLabel(), b -> {
            this.ownedTeam = cycleTeam(this.ownedTeam);
            this.ownedTeamButton.setMessage(ownedLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.save"), b -> save())
                .bounds(left, y, PANEL_W / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 4, y, PANEL_W / 2 - 4, 20).build());
    }

    private EditBox field(int left, int y, String label, String value) {
        // Label drawn in render; box sits under a fixed left margin for the label column.
        EditBox box = new EditBox(this.font, left + 90, y, PANEL_W - 90, 20, Component.literal(label));
        box.setValue(value);
        box.setMaxLength(32);
        addRenderableWidget(box);
        return box;
    }

    private Component billboardLabel() {
        return Component.translatable(this.showBillboard
                ? "gui.tacz_sewv.invasion.billboard.on"
                : "gui.tacz_sewv.invasion.billboard.off");
    }

    private Component ownedLabel() {
        String name = this.ownedTeam.isEmpty() ? "—" : this.ownedTeam;
        return Component.translatable("gui.tacz_sewv.invasion.owned_team", name);
    }

    private String cycleTeam(String current) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(this.teams);
        int idx = options.indexOf(current);
        if (idx < 0) idx = 0;
        return options.get((idx + 1) % options.size());
    }

    private void save() {
        try {
            this.pointId = Integer.parseInt(this.pointIdBox.getValue().trim());
            this.timeToCaptureSeconds = Math.max(1, Integer.parseInt(this.timeBox.getValue().trim()));
            this.radiusInBlocks = Math.max(1, Integer.parseInt(this.radiusBox.getValue().trim()));
            this.billboardYOffset = Double.parseDouble(this.yOffsetBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveCapturePoint(
                this.pos, this.pointId, this.showBillboard, this.billboardYOffset,
                this.timeToCaptureSeconds, this.radiusInBlocks, this.ownedTeam));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - PANEL_W) / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        int y = 46;
        graphics.drawString(this.font, "Point ID", left, y, 0xA0A0A0, false);
        y += 28;
        graphics.drawString(this.font, "Time (s)", left, y, 0xA0A0A0, false);
        y += 28;
        graphics.drawString(this.font, "Radius", left, y, 0xA0A0A0, false);
        y += 28;
        graphics.drawString(this.font, "Billboard Y", left, y, 0xA0A0A0, false);
        if (this.teams.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tacz_sewv.invasion.no_teams"),
                    this.width / 2, this.height - 24, 0xFFAA00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
