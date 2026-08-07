package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveTeamBase;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Op config UI for a team_base. Snapshot edited locally; Save pushes {@link PacketSaveTeamBase}. */
public class TeamBaseScreen extends Screen {

    private static final int PANEL_W = 340;
    private static final int LIST_ROWS = 6;

    private final BlockPos pos;
    private String assignedTeam;
    private boolean playerOwned;
    private boolean spawnPlayerOwnedTanksWithNpc;
    private TankFaction crewFaction;
    private int aiVehicleCount;
    private int timeToCaptureSeconds;
    private int radiusInBlocks;
    private String ownedTeam;
    private boolean invisible;
    private boolean endInvasionOnCapture;
    private final List<String> vehiclePool;
    private final List<String> teams;
    private final List<String> catalog;

    private EditBox timeBox;
    private EditBox radiusBox;
    private EditBox filterBox;
    private Button assignedButton;
    private Button playerOwnedButton;
    private Button spawnNpcButton;
    private Button factionButton;
    private Button ownedTeamButton;
    private Button invisibleButton;
    private Button endOnCaptureButton;
    private Button aiCountLabel;
    private int scroll;
    private int selected = -1;
    private int timeLabelY;
    private int poolLabelY;
    private int listTop;
    private List<String> filteredCatalog = List.of();

    public TeamBaseScreen(BlockPos pos, String assignedTeam, boolean playerOwned,
                          boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                          int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                          String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                          List<String> vehiclePool, List<String> teams, List<String> catalog) {
        super(Component.translatable("gui.tacz_sewv.invasion.team_base.title"));
        this.pos = pos;
        this.assignedTeam = assignedTeam == null ? "" : assignedTeam;
        this.playerOwned = playerOwned;
        this.spawnPlayerOwnedTanksWithNpc = spawnPlayerOwnedTanksWithNpc;
        this.crewFaction = crewFaction == null ? TankFaction.US : crewFaction;
        this.aiVehicleCount = Math.max(0, Math.min(TeamBaseBlockEntity.MAX_AI_VEHICLE_COUNT, aiVehicleCount));
        if (!this.playerOwned && this.aiVehicleCount < 1) {
            this.aiVehicleCount = TeamBaseBlockEntity.DEFAULT_AI_VEHICLE_COUNT;
        }
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.invisible = invisible;
        this.endInvasionOnCapture = endInvasionOnCapture;
        this.vehiclePool = new ArrayList<>(vehiclePool);
        this.teams = new ArrayList<>(teams);
        this.catalog = List.copyOf(catalog);
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int y = 32;

        this.assignedButton = addRenderableWidget(Button.builder(assignedLabel(), b -> {
            this.assignedTeam = cycleTeam(this.assignedTeam);
            this.assignedButton.setMessage(assignedLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 22;

        this.playerOwnedButton = addRenderableWidget(Button.builder(playerOwnedLabel(), b -> {
            this.playerOwned = !this.playerOwned;
            if (!this.playerOwned && this.aiVehicleCount < 1) {
                this.aiVehicleCount = TeamBaseBlockEntity.DEFAULT_AI_VEHICLE_COUNT;
            }
            this.playerOwnedButton.setMessage(playerOwnedLabel());
            this.aiCountLabel.setMessage(aiCountLabel());
        }).bounds(left, y, PANEL_W / 2 - 2, 20).build());
        this.spawnNpcButton = addRenderableWidget(Button.builder(spawnNpcLabel(), b -> {
            this.spawnPlayerOwnedTanksWithNpc = !this.spawnPlayerOwnedTanksWithNpc;
            this.spawnNpcButton.setMessage(spawnNpcLabel());
        }).bounds(left + PANEL_W / 2 + 2, y, PANEL_W / 2 - 2, 20).build());
        y += 22;

        this.factionButton = addRenderableWidget(Button.builder(factionLabel(), b -> {
            TankFaction[] vals = TankFaction.values();
            this.crewFaction = vals[(this.crewFaction.ordinal() + 1) % vals.length];
            this.factionButton.setMessage(factionLabel());
        }).bounds(left, y, PANEL_W / 2 - 2, 20).build());
        this.ownedTeamButton = addRenderableWidget(Button.builder(ownedLabel(), b -> {
            this.ownedTeam = cycleTeam(this.ownedTeam);
            this.ownedTeamButton.setMessage(ownedLabel());
        }).bounds(left + PANEL_W / 2 + 2, y, PANEL_W / 2 - 2, 20).build());
        y += 22;

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustAiCount(-1))
                .bounds(left, y, 20, 20).build());
        this.aiCountLabel = addRenderableWidget(Button.builder(aiCountLabel(), b -> {})
                .bounds(left + 22, y, PANEL_W - 44, 20).build());
        this.aiCountLabel.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustAiCount(1))
                .bounds(left + PANEL_W - 20, y, 20, 20).build());
        y += 24;

        this.timeLabelY = y;
        this.timeBox = new EditBox(this.font, left + 90, y, 70, 20, Component.literal("time"));
        this.timeBox.setValue(Integer.toString(this.timeToCaptureSeconds));
        this.timeBox.setMaxLength(8);
        addRenderableWidget(this.timeBox);
        this.radiusBox = new EditBox(this.font, left + 250, y, 70, 20, Component.literal("radius"));
        this.radiusBox.setValue(Integer.toString(this.radiusInBlocks));
        this.radiusBox.setMaxLength(8);
        addRenderableWidget(this.radiusBox);
        y += 24;

        this.invisibleButton = addRenderableWidget(Button.builder(invisibleLabel(), b -> {
            this.invisible = !this.invisible;
            this.invisibleButton.setMessage(invisibleLabel());
        }).bounds(left, y, PANEL_W / 2 - 2, 20).build());
        this.endOnCaptureButton = addRenderableWidget(Button.builder(endOnCaptureLabel(), b -> {
            this.endInvasionOnCapture = !this.endInvasionOnCapture;
            this.endOnCaptureButton.setMessage(endOnCaptureLabel());
        }).bounds(left + PANEL_W / 2 + 2, y, PANEL_W / 2 - 2, 20).build());
        y += 26;

        this.poolLabelY = y;
        y += 12;
        this.listTop = y;
        int listBottom = this.listTop + LIST_ROWS * 12;

        this.filterBox = new EditBox(this.font, left, listBottom + 4, PANEL_W - 90, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        addRenderableWidget(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + PANEL_W - 84, listBottom + 4, 84, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + PANEL_W - 20, this.listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.scroll + LIST_ROWS < this.vehiclePool.size()) this.scroll++;
        }).bounds(left + PANEL_W - 20, listBottom - 20, 20, 20).build());

        int saveY = listBottom + 52;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.save"), b -> save())
                .bounds(left, saveY, PANEL_W / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 4, saveY, PANEL_W / 2 - 4, 20).build());
    }

    private void adjustAiCount(int delta) {
        int min = this.playerOwned ? 0 : 1;
        this.aiVehicleCount = Math.max(min, Math.min(TeamBaseBlockEntity.MAX_AI_VEHICLE_COUNT,
                this.aiVehicleCount + delta));
        this.aiCountLabel.setMessage(aiCountLabel());
    }

    private Component assignedLabel() {
        String name = this.assignedTeam.isEmpty() ? "—" : this.assignedTeam;
        return Component.translatable("gui.tacz_sewv.invasion.assigned_team", name);
    }

    private Component playerOwnedLabel() {
        return Component.translatable(this.playerOwned
                ? "gui.tacz_sewv.invasion.player_owned.on"
                : "gui.tacz_sewv.invasion.player_owned.off");
    }

    private Component spawnNpcLabel() {
        return Component.translatable(this.spawnPlayerOwnedTanksWithNpc
                ? "gui.tacz_sewv.invasion.spawn_npc.on"
                : "gui.tacz_sewv.invasion.spawn_npc.off");
    }

    private Component factionLabel() {
        return Component.translatable("gui.tacz_sewv.invasion.crew_faction", this.crewFaction.name());
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

    private Component endOnCaptureLabel() {
        return Component.translatable(this.endInvasionOnCapture
                ? "gui.tacz_sewv.invasion.end_on_capture.on"
                : "gui.tacz_sewv.invasion.end_on_capture.off");
    }

    private Component aiCountLabel() {
        if (this.aiVehicleCount <= 0) {
            return Component.translatable("gui.tacz_sewv.invasion.ai_vehicle_count.off");
        }
        return Component.translatable("gui.tacz_sewv.invasion.ai_vehicle_count", this.aiVehicleCount);
    }

    private String cycleTeam(String current) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(this.teams);
        int idx = options.indexOf(current);
        if (idx < 0) idx = 0;
        return options.get((idx + 1) % options.size());
    }

    private void refreshFilter() {
        String q = this.filterBox != null ? this.filterBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<String> out = new ArrayList<>();
        for (String id : this.catalog) {
            if (this.vehiclePool.contains(id)) continue;
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(id);
        }
        this.filteredCatalog = out;
    }

    private void addFromFilter() {
        refreshFilter();
        String typed = this.filterBox != null ? this.filterBox.getValue().trim() : "";
        String id = null;
        if (!typed.isEmpty()) {
            String lower = typed.toLowerCase(Locale.ROOT);
            for (String cand : this.filteredCatalog) {
                if (cand.equalsIgnoreCase(typed) || cand.toLowerCase(Locale.ROOT).endsWith(":" + lower)) {
                    id = cand;
                    break;
                }
            }
            if (id == null && ResourceLocation.tryParse(typed) != null) id = typed;
        }
        if (id == null) id = this.filteredCatalog.isEmpty() ? null : this.filteredCatalog.get(0);
        if (id == null) return;
        if (!this.vehiclePool.contains(id)) this.vehiclePool.add(id);
        this.selected = this.vehiclePool.indexOf(id);
        if (this.selected >= this.scroll + LIST_ROWS) {
            this.scroll = this.selected - LIST_ROWS + 1;
        }
        refreshFilter();
    }

    private void removeSelected() {
        if (this.selected < 0 || this.selected >= this.vehiclePool.size()) return;
        this.vehiclePool.remove(this.selected);
        if (this.selected >= this.vehiclePool.size()) this.selected = this.vehiclePool.size() - 1;
        refreshFilter();
    }

    private void save() {
        try {
            this.timeToCaptureSeconds = Math.max(1, Integer.parseInt(this.timeBox.getValue().trim()));
            this.radiusInBlocks = Math.max(1, Integer.parseInt(this.radiusBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveTeamBase(
                this.pos, this.assignedTeam, this.playerOwned, this.spawnPlayerOwnedTanksWithNpc,
                this.crewFaction, this.aiVehicleCount, this.timeToCaptureSeconds, this.radiusInBlocks,
                this.ownedTeam, this.invisible, this.endInvasionOnCapture, this.vehiclePool));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - PANEL_W) / 2;
        if (mouseX >= left && mouseX < left + PANEL_W - 24
                && mouseY >= this.listTop && mouseY < this.listTop + LIST_ROWS * 12) {
            int row = (int) ((mouseY - this.listTop) / 12) + this.scroll;
            if (row >= 0 && row < this.vehiclePool.size()) {
                this.selected = row;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + LIST_ROWS < this.vehiclePool.size()) this.scroll++;
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        graphics.drawString(this.font, "Time (s)", left, this.timeLabelY + 6, 0xA0A0A0, false);
        graphics.drawString(this.font, "Radius", left + 170, this.timeLabelY + 6, 0xA0A0A0, false);

        graphics.fill(left - 2, this.listTop - 2, left + PANEL_W - 22, this.listTop + LIST_ROWS * 12 + 2, 0x88000000);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.invasion.vehicle_pool", this.vehiclePool.size()),
                left, this.poolLabelY, 0xA0A0A0, false);

        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = i + this.scroll;
            if (idx >= this.vehiclePool.size()) break;
            int color = idx == this.selected ? 0xFFFFAA00 : 0xFFE0E0E0;
            graphics.drawString(this.font, this.vehiclePool.get(idx), left + 4, this.listTop + i * 12 + 2, color, false);
        }

        if (this.teams.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tacz_sewv.invasion.no_teams"),
                    this.width / 2, this.height - 16, 0xFFAA00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
