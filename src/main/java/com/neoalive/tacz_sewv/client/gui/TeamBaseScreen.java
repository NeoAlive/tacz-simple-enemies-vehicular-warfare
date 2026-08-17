package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveTeamBase;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Op config UI for a team_base. Snapshot edited locally; Save pushes {@link PacketSaveTeamBase}. */
public class TeamBaseScreen extends Screen {

    private static final int PANEL_W = 400;
    private static final int LIST_ROWS = 4;
    private static final int COL_W = PANEL_W / 2 - 4;

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
    private int spawnDelaySeconds;
    private boolean pointsHaveToBeConquered;
    private PmcOwnerKind pmcOwnerKind;
    private String pmcOwnerValue;
    private final List<String> vehiclePool;
    private final List<String> enemyTeams;
    private final List<String> teams;
    private final List<String> onlinePlayerNames;
    private final List<String> onlinePlayerUuids;
    private final List<String> catalog;
    private final Map<TankFaction, List<String>> armorPools;

    private EditBox timeBox;
    private EditBox radiusBox;
    private EditBox spawnDelayBox;
    private EditBox filterBox;
    private Button assignedButton;
    private Button playerOwnedButton;
    private Button spawnNpcButton;
    private Button factionButton;
    private Button ownedTeamButton;
    private Button pmcOwnerButton;
    private Button invisibleButton;
    private Button endOnCaptureButton;
    private Button pointsConqueredButton;
    private Button aiCountLabel;
    private int scroll;
    private int enemyScroll;
    private int selected = -1;
    private int timeLabelY;
    private int delayLabelY;
    private int poolLabelY;
    private int listTop;
    private List<String> filteredCatalog = List.of();

    public TeamBaseScreen(BlockPos pos, String assignedTeam, boolean playerOwned,
                          boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                          int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                          String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                          int spawnDelaySeconds, boolean pointsHaveToBeConquered,
                          PmcOwnerKind pmcOwnerKind, String pmcOwnerValue,
                          List<String> vehiclePool, List<String> enemyTeams, List<String> teams,
                          List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                          List<String> catalog, Map<TankFaction, List<String>> armorPools) {
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
        this.spawnDelaySeconds = Math.max(0, Math.min(3600, spawnDelaySeconds));
        this.pointsHaveToBeConquered = pointsHaveToBeConquered;
        this.pmcOwnerKind = pmcOwnerKind == null ? PmcOwnerKind.NONE : pmcOwnerKind;
        this.pmcOwnerValue = pmcOwnerValue == null ? "" : pmcOwnerValue;
        this.vehiclePool = new ArrayList<>(vehiclePool);
        this.enemyTeams = new ArrayList<>(enemyTeams);
        this.teams = new ArrayList<>(teams);
        this.onlinePlayerNames = List.copyOf(onlinePlayerNames);
        this.onlinePlayerUuids = List.copyOf(onlinePlayerUuids);
        this.catalog = List.copyOf(catalog);
        Map<TankFaction, List<String>> armor = new EnumMap<>(TankFaction.class);
        if (armorPools != null) {
            for (TankFaction faction : TankFaction.values()) {
                List<String> list = armorPools.get(faction);
                armor.put(faction, list == null ? List.of() : List.copyOf(list));
            }
        }
        this.armorPools = armor;
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
            if (this.crewFaction != TankFaction.PMC) {
                this.pmcOwnerKind = PmcOwnerKind.NONE;
                this.pmcOwnerValue = "";
            }
            this.factionButton.setMessage(factionLabel());
            refreshPmcOwnerButton();
        }).bounds(left, y, PANEL_W / 2 - 2, 20).build());
        this.ownedTeamButton = addRenderableWidget(Button.builder(ownedLabel(), b -> {
            this.ownedTeam = cycleTeam(this.ownedTeam);
            this.ownedTeamButton.setMessage(ownedLabel());
        }).bounds(left + PANEL_W / 2 + 2, y, PANEL_W / 2 - 2, 20).build());
        y += 22;

        this.pmcOwnerButton = addRenderableWidget(Button.builder(pmcOwnerLabel(), b -> {
            cyclePmcOwner();
            this.pmcOwnerButton.setMessage(pmcOwnerLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        refreshPmcOwnerButton();
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
        y += 22;

        this.pointsConqueredButton = addRenderableWidget(Button.builder(pointsConqueredLabel(), b -> {
            this.pointsHaveToBeConquered = !this.pointsHaveToBeConquered;
            this.pointsConqueredButton.setMessage(pointsConqueredLabel());
        }).bounds(left, y, PANEL_W / 2 - 2, 20).build());
        this.delayLabelY = y;
        this.spawnDelayBox = new EditBox(this.font, left + PANEL_W / 2 + 90, y, 70, 20,
                Component.literal("spawnDelay"));
        this.spawnDelayBox.setValue(Integer.toString(this.spawnDelaySeconds));
        this.spawnDelayBox.setMaxLength(8);
        addRenderableWidget(this.spawnDelayBox);
        y += 26;

        this.poolLabelY = y;
        y += 12;
        this.listTop = y;
        int listBottom = this.listTop + LIST_ROWS * 12;
        int rightCol = left + COL_W + 8;

        this.filterBox = new EditBox(this.font, left, listBottom + 4, COL_W - 84, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        addRenderableWidget(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + COL_W - 80, listBottom + 4, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 28, 100, 20).build());
        // Same source as /sewv pool Armor for the selected crew faction.
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.reset"), b -> fillFromArmorPool())
                .bounds(left + 104, listBottom + 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + COL_W - 20, this.listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.scroll + LIST_ROWS < this.vehiclePool.size()) this.scroll++;
        }).bounds(left + COL_W - 20, listBottom - 20, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.enemyScroll > 0) this.enemyScroll--;
        }).bounds(rightCol + COL_W - 20, this.listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.enemyScroll + LIST_ROWS < this.teams.size()) this.enemyScroll++;
        }).bounds(rightCol + COL_W - 20, listBottom - 20, 20, 20).build());

        int saveY = listBottom + 52;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.save"), b -> save())
                .bounds(left, saveY, PANEL_W / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 4, saveY, PANEL_W / 2 - 4, 20).build());
    }

    private void refreshPmcOwnerButton() {
        boolean pmc = this.crewFaction == TankFaction.PMC;
        this.pmcOwnerButton.active = pmc;
        this.pmcOwnerButton.visible = pmc;
        this.pmcOwnerButton.setMessage(pmcOwnerLabel());
    }

    private void cyclePmcOwner() {
        // Options: NONE, each online player, each scoreboard team.
        List<PmcOwnerKind> kinds = new ArrayList<>();
        List<String> values = new ArrayList<>();
        kinds.add(PmcOwnerKind.NONE);
        values.add("");
        int n = Math.min(this.onlinePlayerNames.size(), this.onlinePlayerUuids.size());
        for (int i = 0; i < n; i++) {
            kinds.add(PmcOwnerKind.PLAYER);
            values.add(this.onlinePlayerUuids.get(i));
        }
        for (String team : this.teams) {
            kinds.add(PmcOwnerKind.TEAM);
            values.add(team);
        }
        int idx = 0;
        for (int i = 0; i < kinds.size(); i++) {
            if (kinds.get(i) == this.pmcOwnerKind && values.get(i).equals(this.pmcOwnerValue)) {
                idx = i;
                break;
            }
        }
        int next = (idx + 1) % kinds.size();
        this.pmcOwnerKind = kinds.get(next);
        this.pmcOwnerValue = values.get(next);
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

    private Component pmcOwnerLabel() {
        if (this.crewFaction != TankFaction.PMC || this.pmcOwnerKind == PmcOwnerKind.NONE) {
            return Component.translatable("gui.tacz_sewv.invasion.pmc_owner.empty");
        }
        MutableComponent value;
        if (this.pmcOwnerKind == PmcOwnerKind.PLAYER) {
            String display = playerNameForUuid(this.pmcOwnerValue);
            value = Component.literal(display).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
        } else {
            value = Component.literal(this.pmcOwnerValue).withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
        }
        return Component.translatable("gui.tacz_sewv.invasion.pmc_owner", value);
    }

    private String playerNameForUuid(String uuid) {
        int n = Math.min(this.onlinePlayerNames.size(), this.onlinePlayerUuids.size());
        for (int i = 0; i < n; i++) {
            if (this.onlinePlayerUuids.get(i).equals(uuid)) return this.onlinePlayerNames.get(i);
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid;
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

    private Component pointsConqueredLabel() {
        return Component.translatable(this.pointsHaveToBeConquered
                ? "gui.tacz_sewv.invasion.points_required.on"
                : "gui.tacz_sewv.invasion.points_required.off");
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

    /** Replace the base pool with the world GROUND (armor) pool for the current crew faction. */
    private void fillFromArmorPool() {
        List<String> src = this.armorPools.getOrDefault(this.crewFaction, List.of());
        this.vehiclePool.clear();
        this.vehiclePool.addAll(src);
        this.selected = -1;
        this.scroll = 0;
        refreshFilter();
    }

    private void save() {
        try {
            this.timeToCaptureSeconds = Math.max(1, Integer.parseInt(this.timeBox.getValue().trim()));
            this.radiusInBlocks = Math.max(1, Integer.parseInt(this.radiusBox.getValue().trim()));
            this.spawnDelaySeconds = Math.max(0, Math.min(3600,
                    Integer.parseInt(this.spawnDelayBox.getValue().trim())));
        } catch (NumberFormatException e) {
            return;
        }
        PmcOwnerKind kind = this.crewFaction == TankFaction.PMC ? this.pmcOwnerKind : PmcOwnerKind.NONE;
        String value = this.crewFaction == TankFaction.PMC ? this.pmcOwnerValue : "";
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveTeamBase(
                this.pos, this.assignedTeam, this.playerOwned, this.spawnPlayerOwnedTanksWithNpc,
                this.crewFaction, this.aiVehicleCount, this.timeToCaptureSeconds, this.radiusInBlocks,
                this.ownedTeam, this.invisible, this.endInvasionOnCapture,
                this.spawnDelaySeconds, this.pointsHaveToBeConquered, kind, value,
                this.vehiclePool, this.enemyTeams));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - PANEL_W) / 2;
        int rightCol = left + COL_W + 8;
        if (mouseY >= this.listTop && mouseY < this.listTop + LIST_ROWS * 12) {
            if (mouseX >= left && mouseX < left + COL_W - 24) {
                int row = (int) ((mouseY - this.listTop) / 12) + this.scroll;
                if (row >= 0 && row < this.vehiclePool.size()) {
                    this.selected = row;
                    return true;
                }
            }
            if (mouseX >= rightCol && mouseX < rightCol + COL_W - 24) {
                int row = (int) ((mouseY - this.listTop) / 12) + this.enemyScroll;
                if (row >= 0 && row < this.teams.size()) {
                    toggleEnemy(this.teams.get(row));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void toggleEnemy(String team) {
        if (team == null || team.isEmpty()) return;
        if (team.equals(this.assignedTeam)) return; // cannot mark own assigned team
        if (this.enemyTeams.contains(team)) this.enemyTeams.remove(team);
        else this.enemyTeams.add(team);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = (this.width - PANEL_W) / 2;
        int rightCol = left + COL_W + 8;
        if (mouseX >= rightCol) {
            if (delta > 0 && this.enemyScroll > 0) this.enemyScroll--;
            else if (delta < 0 && this.enemyScroll + LIST_ROWS < this.teams.size()) this.enemyScroll++;
            return true;
        }
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + LIST_ROWS < this.vehiclePool.size()) this.scroll++;
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        int rightCol = left + COL_W + 8;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        graphics.drawString(this.font, "Time (s)", left, this.timeLabelY + 6, 0xA0A0A0, false);
        graphics.drawString(this.font, "Radius", left + 170, this.timeLabelY + 6, 0xA0A0A0, false);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.invasion.spawn_delay"),
                left + PANEL_W / 2 + 2, this.delayLabelY + 6, 0xA0A0A0, false);

        graphics.fill(left - 2, this.listTop - 2, left + COL_W - 2, this.listTop + LIST_ROWS * 12 + 2, 0x88000000);
        graphics.fill(rightCol - 2, this.listTop - 2, rightCol + COL_W - 2, this.listTop + LIST_ROWS * 12 + 2, 0x88000000);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.invasion.vehicle_pool", this.vehiclePool.size()),
                left, this.poolLabelY, 0xA0A0A0, false);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.invasion.enemy_teams"),
                rightCol, this.poolLabelY, 0xA0A0A0, false);

        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = i + this.scroll;
            if (idx >= this.vehiclePool.size()) break;
            int color = idx == this.selected ? 0xFFFFAA00 : 0xFFE0E0E0;
            graphics.drawString(this.font, this.vehiclePool.get(idx), left + 4, this.listTop + i * 12 + 2, color, false);
        }
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = i + this.enemyScroll;
            if (idx >= this.teams.size()) break;
            String team = this.teams.get(idx);
            boolean enemy = this.enemyTeams.contains(team);
            boolean self = team.equals(this.assignedTeam);
            Component label = Component.translatable(enemy
                    ? "gui.tacz_sewv.invasion.enemy_yes" : "gui.tacz_sewv.invasion.enemy_no", team);
            int color = self ? 0xFF666666 : enemy ? 0xFFFF5555 : 0xFF55FF55;
            graphics.drawString(this.font, label, rightCol + 4, this.listTop + i * 12 + 2, color, false);
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
