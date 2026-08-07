package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveCapturePoint;

/** Op config UI for a capture_point. Snapshot edited locally; Save pushes {@link PacketSaveCapturePoint}. */
public class CapturePointScreen extends Screen {

    private static final int PANEL_W = 280;

    private final BlockPos pos;
    private int pointId;
    private int timeToCaptureSeconds;
    private int radiusInBlocks;
    private String ownedTeam;
    private boolean invisible;
    private final List<String> teams;

    private EditBox pointIdBox;
    private EditBox timeBox;
    private EditBox radiusBox;
    private Button ownedTeamButton;
    private Button invisibleButton;

    public CapturePointScreen(BlockPos pos, int pointId, int timeToCaptureSeconds, int radiusInBlocks,
                              String ownedTeam, boolean invisible, List<String> teams) {
        super(Component.translatable("gui.tacz_sewv.invasion.capture_point.title"));
        this.pos = pos;
        this.pointId = pointId;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.invisible = invisible;
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

        this.ownedTeamButton = addRenderableWidget(Button.builder(ownedLabel(), b -> {
            this.ownedTeam = cycleTeam(this.ownedTeam);
            this.ownedTeamButton.setMessage(ownedLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 24;

        this.invisibleButton = addRenderableWidget(Button.builder(invisibleLabel(), b -> {
            this.invisible = !this.invisible;
            this.invisibleButton.setMessage(invisibleLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.save"), b -> save())
                .bounds(left, y, PANEL_W / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 4, y, PANEL_W / 2 - 4, 20).build());
    }

    private EditBox field(int left, int y, String label, String value) {
        EditBox box = new EditBox(this.font, left + 90, y, PANEL_W - 90, 20, Component.literal(label));
        box.setValue(value);
        box.setMaxLength(32);
        addRenderableWidget(box);
        return box;
    }

    private Component ownedLabel() {
        String name = this.ownedTeam.isEmpty() ? "—" : this.ownedTeam;
        return Component.translatable("gui.tacz_sewv.invasion.owned_team", name);
    }

    private Component invisibleLabel() {
        return Component.translatable(this.invisible
                ? "gui.tacz_sewv.invasion.invisible.on"
                : "gui.tacz_sewv.invasion.invisible.off");
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
        } catch (NumberFormatException e) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveCapturePoint(
                this.pos, this.pointId, this.timeToCaptureSeconds, this.radiusInBlocks,
                this.ownedTeam, this.invisible));
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
