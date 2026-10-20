package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import com.neoalive.tacz_sewv.client.editor.VehiclePoolCatalog;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.invasion.force.BaseForce;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.invasion.force.InfantrySpec;
import com.neoalive.tacz_sewv.invasion.force.TeamBaseConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveTeamBase;
import com.neoalive.tacz_sewv.platform.SewvScreen;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehicleClassify;
import com.neoalive.tacz_sewv.util.VehiclePoolCatalogSource;

/**
 * Op config UI for a team_base, in four tabs: who the base is (Base), the zone around it (Zone),
 * what it fields (Forces — one sub-tab per {@link ForceKind}) and what its players may deploy as
 * (Deploy). Edits a local {@link TeamBaseConfig}; Save pushes {@link PacketSaveTeamBase}.
 * <p>
 * Switching tab rebuilds the widgets, so every text box writes through to the config as it is
 * typed — nothing lives only in a widget.
 */
public class TeamBaseScreen extends SewvScreen {

    private static final int PANEL_W_PREF = 420;
    private static final int LIST_ROW_H = 12;
    /** Below this scaled height (GUI scale 3-4 on 1080p) rows tighten and the tab strip moves up. */
    private static final int COMPACT_H = 300;

    private enum Tab { BASE, ZONE, FORCES, DEPLOY }

    private final BlockPos pos;
    private final TeamBaseConfig cfg;
    private final List<String> teams;
    private final List<String> onlinePlayerNames;
    private final List<String> onlinePlayerUuids;
    private final List<String> catalog;
    private final Map<TankFaction, Map<ForceKind, List<String>>> worldPools;

    private final HoverTips hoverTips = new HoverTips();
    private final Map<ForceKind, List<String>> fittingCatalog = new EnumMap<>(ForceKind.class);
    private final Map<String, String[]> rowInfo = new HashMap<>();

    private Tab tab = Tab.BASE;
    private ForceKind forceTab = ForceKind.GROUND;

    private int left;
    private int panelW;
    private int row;
    private int contentTop;
    private int footerY;

    // Base tab: hand-drawn enemy checklist.
    private int enemyTop;
    private int enemyRows;
    private int enemyScroll;

    // Forces tab, vehicle sub-tab.
    @Nullable
    private SheetTable poolTable;
    @Nullable
    private PoolVehicleIdEditBox filterBox;
    private int hintY;

    public TeamBaseScreen(BlockPos pos, TeamBaseConfig cfg, List<String> teams,
                          List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                          List<String> catalog, Map<TankFaction, Map<ForceKind, List<String>>> worldPools) {
        super(Component.translatable("gui.tacz_sewv.invasion.team_base.title"));
        this.pos = pos;
        this.cfg = cfg;
        this.teams = new ArrayList<>(teams);
        this.onlinePlayerNames = List.copyOf(onlinePlayerNames);
        this.onlinePlayerUuids = List.copyOf(onlinePlayerUuids);
        this.catalog = List.copyOf(catalog);
        this.worldPools = worldPools == null ? Map.of() : worldPools;
    }

    private BaseForce force() {
        return this.cfg.force;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.hoverTips.clear();
        this.poolTable = null;
        this.filterBox = null;
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.left = (this.width - this.panelW) / 2;
        boolean compact = this.height < COMPACT_H;
        this.row = compact ? 22 : 24;
        int y = compact ? 18 : 24;

        TabStrip tabs = new TabStrip(70, i -> {
            this.tab = Tab.values()[i];
            rebuildWidgets();
        });
        for (Tab t : Tab.values()) {
            String key = t.name().toLowerCase(Locale.ROOT);
            tabs.add(Component.translatable("gui.tacz_sewv.invasion.tab." + key),
                    Component.translatable("gui.tacz_sewv.invasion.tab." + key + ".tip"));
        }
        tabs.select(this.tab.ordinal());
        y = tabs.layout(this::addRenderableWidget, this.left, y, this.panelW) + 4;
        this.contentTop = y;
        this.footerY = this.height - 26;

        switch (this.tab) {
            case BASE -> initBase(y);
            case ZONE -> initZone(y);
            case FORCES -> initForces(y);
            case DEPLOY -> initDeploy(y);
        }

        int half = this.panelW / 2 - 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.save"), b -> save())
                .bounds(this.left, this.footerY, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(this.left + half + 4, this.footerY, half, 20).build());
    }

    private void initBase(int y) {
        int half = this.panelW / 2 - 2;
        int right = this.left + half + 4;
        Button[] assigned = new Button[1];
        assigned[0] = addRenderableWidget(Button.builder(assignedLabel(), b -> {
            this.cfg.assignedTeam = cycleTeam(this.cfg.assignedTeam);
            this.cfg.enemyTeams.remove(this.cfg.assignedTeam);
            assigned[0].setMessage(assignedLabel());
        }).bounds(this.left, y, this.panelW, 20).build());
        y += this.row;

        addRenderableWidget(ToggleButton.create(this.left, y, half,
                Component.translatable("gui.tacz_sewv.invasion.player_owned"),
                () -> this.cfg.playerOwned, v -> this.cfg.playerOwned = v));
        Button[] pmcOwner = new Button[1];
        Button[] faction = new Button[1];
        faction[0] = addRenderableWidget(Button.builder(factionLabel(), b -> {
            TankFaction[] vals = TankFaction.values();
            this.cfg.crewFaction = vals[(this.cfg.crewFaction.ordinal() + 1) % vals.length];
            if (this.cfg.crewFaction != TankFaction.PMC) {
                this.cfg.pmcOwnerKind = PmcOwnerKind.NONE;
                this.cfg.pmcOwnerValue = "";
            }
            faction[0].setMessage(factionLabel());
            pmcOwner[0].active = this.cfg.crewFaction == TankFaction.PMC;
            pmcOwner[0].setMessage(pmcOwnerLabel());
        }).bounds(right, y, half, 20).build());
        y += this.row;

        Button[] owned = new Button[1];
        owned[0] = addRenderableWidget(Button.builder(ownedLabel(), b -> {
            this.cfg.ownedTeam = cycleTeam(this.cfg.ownedTeam);
            owned[0].setMessage(ownedLabel());
        }).bounds(this.left, y, half, 20).build());
        pmcOwner[0] = addRenderableWidget(Button.builder(pmcOwnerLabel(), b -> {
            cyclePmcOwner();
            pmcOwner[0].setMessage(pmcOwnerLabel());
        }).bounds(right, y, half, 20).build());
        pmcOwner[0].active = this.cfg.crewFaction == TankFaction.PMC;
        y += this.row + 4;

        this.enemyTop = y + 12;
        this.enemyRows = Math.max(1, (this.footerY - 14 - this.enemyTop) / LIST_ROW_H);
        clampEnemyScroll();
    }

    private void initZone(int y) {
        int half = this.panelW / 2 - 2;
        int right = this.left + half + 4;
        int boxX = this.left + 110;
        intBox(boxX, y, "time", this.cfg.timeToCaptureSeconds, 1, 100000, v -> this.cfg.timeToCaptureSeconds = v);
        y += this.row;
        intBox(boxX, y, "radius", this.cfg.radiusInBlocks, 1, 512, v -> this.cfg.radiusInBlocks = v);
        y += this.row;
        intBox(boxX, y, "spawn_delay", this.cfg.spawnDelaySeconds, 0, 3600, v -> this.cfg.spawnDelaySeconds = v);
        y += this.row + 4;

        addRenderableWidget(ToggleButton.create(this.left, y, half,
                Component.translatable("gui.tacz_sewv.invasion.invisible"),
                () -> this.cfg.invisible, v -> this.cfg.invisible = v));
        addRenderableWidget(ToggleButton.create(right, y, half,
                Component.translatable("gui.tacz_sewv.invasion.end_on_capture"),
                () -> this.cfg.endInvasionOnCapture, v -> this.cfg.endInvasionOnCapture = v));
        y += this.row;
        addRenderableWidget(ToggleButton.create(this.left, y, this.panelW,
                Component.translatable("gui.tacz_sewv.invasion.points_required"),
                () -> this.cfg.pointsHaveToBeConquered, v -> this.cfg.pointsHaveToBeConquered = v));
    }

    /** Integer text box with its label drawn by {@link #render}; writes through while typing. */
    private void intBox(int x, int y, String key, int value, int min, int max, IntConsumer set) {
        EditBox box = new EditBox(this.font, x, y, 70, 20, Component.translatable("gui.tacz_sewv.invasion.field." + key));
        box.setMaxLength(6);
        box.setValue(Integer.toString(value));
        box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        box.setResponder(s -> {
            if (s.isEmpty()) return;
            try {
                set.accept(Math.max(min, Math.min(max, Integer.parseInt(s))));
            } catch (NumberFormatException ignored) {
                // Longer than an int: leave the last good value.
            }
        });
        addRenderableWidget(this.hoverTips.add(box,
                Component.translatable("gui.tacz_sewv.invasion.field." + key + ".tip")));
    }

    private void initForces(int y) {
        TabStrip sub = new TabStrip(70, i -> {
            this.forceTab = ForceKind.values()[i];
            rebuildWidgets();
        });
        for (ForceKind kind : ForceKind.values()) {
            sub.add(forceTabLabel(kind), Component.translatable("gui.tacz_sewv.invasion.force." + kind.key() + ".tip"));
        }
        sub.select(this.forceTab.ordinal());
        y = sub.layout(this::addRenderableWidget, this.left, y, this.panelW) + 2;

        if (this.forceTab == ForceKind.INFANTRY) {
            initInfantry(y);
        } else {
            initVehicleKind(this.forceTab, y);
        }
    }

    private void initVehicleKind(ForceKind kind, int y) {
        BaseForce force = force();
        Stepper.create(this::addRenderableWidget, this.left, y, this.panelW, 0, BaseForce.MAX_AI_VEHICLES,
                () -> force.aiCount(kind), v -> force.setAiCount(kind, v),
                v -> v <= 0
                        ? Component.translatable("gui.tacz_sewv.invasion.ai_count.off." + kind.key())
                        : Component.translatable("gui.tacz_sewv.invasion.ai_count." + kind.key(), v));
        y += this.row;

        List<String> pool = force.pool(kind);
        List<SheetTable.Col> cols = List.of(
                new SheetTable.Col("n", 24, true, Component.translatable("gui.tacz_sewv.pool.col.n"),
                        Component.translatable("gui.tacz_sewv.pool.tip.col.n")),
                new SheetTable.Col("id", -3, false, Component.translatable("gui.tacz_sewv.pool.col.id"),
                        Component.translatable("gui.tacz_sewv.pool.tip.col.id")),
                new SheetTable.Col("name", -2, false, Component.translatable("gui.tacz_sewv.pool.col.name"),
                        Component.translatable("gui.tacz_sewv.pool.tip.col.name")),
                new SheetTable.Col("class", 46, false, Component.translatable("gui.tacz_sewv.pool.col.class"),
                        Component.translatable("gui.tacz_sewv.pool.tip.col.class")));
        this.poolTable = new SheetTable(cols, new PoolModel(kind, pool),
                Component.translatable("gui.tacz_sewv.pool.tip.col.st"));
        int below = this.row * 2 + 12 + 30;
        int rows = SheetTable.rowsFor(this.height, y + SheetTable.HEAD_H, below, 1, 12);
        this.poolTable.layout(this.left, y, this.panelW, rows);
        y = this.poolTable.bottom() + 4;

        PoolVehicleIdEditBox box = new PoolVehicleIdEditBox(this.font, this.left, y, this.panelW - 88, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        box.setMaxLength(128);
        box.setHint(Component.translatable("gui.tacz_sewv.pool.hint.search"));
        box.setTabCompleter(() -> {
            String s = suggest(kind, box.getValue());
            if (s == null) return false;
            box.setValue(s);
            box.setCursorPosition(s.length());
            return true;
        });
        this.filterBox = addRenderableWidget(this.hoverTips.add(box,
                Component.translatable("gui.tacz_sewv.invasion.tip.filter")));
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter(kind))
                .bounds(this.left + this.panelW - 84, y, 84, 20).build());
        y += this.row;

        int bw = (this.panelW - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> {
            int sel = this.poolTable.selected();
            if (sel < 0 || sel >= pool.size()) return;
            pool.remove(sel);
            this.poolTable.clearSelection();
            this.poolTable.clampScroll();
        }).bounds(this.left, y, bw, 20).build());
        Button fill = addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.fill_world"), b -> {
            pool.clear();
            pool.addAll(worldPool(kind));
            this.poolTable.reset();
        }).bounds(this.left + bw + 4, y, bw, 20).build());
        fill.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                "gui.tacz_sewv.invasion.fill_world.tip", this.cfg.crewFaction.name())));
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.invasion.clear_pool"), b -> {
            pool.clear();
            this.poolTable.reset();
        }).bounds(this.left + 2 * (bw + 4), y, this.panelW - 2 * (bw + 4), 20).build());
        y += this.row;
        this.hintY = y;
    }

    private void initInfantry(int y) {
        InfantrySpec inf = force().infantry();
        int third = (this.panelW - 8) / 3;
        Button[] mode = new Button[1];
        mode[0] = addRenderableWidget(Button.builder(infantryModeLabel(), b -> {
            InfantrySpec.Mode[] vals = InfantrySpec.Mode.values();
            inf.mode = vals[(inf.mode.ordinal() + 1) % vals.length];
            rebuildWidgets();
        }).bounds(this.left, y, this.panelW, 20).build());
        y += this.row;

        int boxX = this.left + 110;
        intBox(boxX, y, "inf_count", inf.fixed, 0, InfantrySpec.MAX, v -> inf.fixed = v);
        setLastBoxActive(inf.mode == InfantrySpec.Mode.FIXED);
        y += this.row;
        intBox(boxX, y, "inf_min", inf.min, 0, InfantrySpec.MAX, v -> inf.min = v);
        setLastBoxActive(inf.mode == InfantrySpec.Mode.RANDOM);
        // Min and max share a row when the panel is wide enough for two label + box pairs.
        boolean twoCols = this.panelW / 2 >= 190;
        if (twoCols) {
            boxX = this.left + this.panelW / 2 + 110;
        } else {
            y += this.row;
        }
        intBox(boxX, y, "inf_max", inf.max, 0, InfantrySpec.MAX, v -> inf.max = v);
        setLastBoxActive(inf.mode == InfantrySpec.Mode.RANDOM);
        y += this.row + 4;

        Button capture = addRenderableWidget(ToggleButton.create(this.left, y, this.panelW,
                Component.translatable("gui.tacz_sewv.invasion.inf_captures"),
                () -> inf.canCapture, v -> inf.canCapture = v));
        capture.active = inf.isEnabled();
        y += this.row;
        this.hintY = y;
    }

    /** Text boxes that do not apply are disabled, not hidden, so the layout never jumps. */
    private void setLastBoxActive(boolean active) {
        for (int i = this.renderables.size() - 1; i >= 0; i--) {
            if (this.renderables.get(i) instanceof EditBox box) {
                box.setEditable(active);
                box.active = active;
                return;
            }
        }
    }

    private void initDeploy(int y) {
        BaseForce force = force();
        boolean on = this.cfg.playerOwned;
        int half = this.panelW / 2 - 2;
        int col = 0;
        for (ForceKind kind : ForceKind.values()) {
            int x = col == 0 ? this.left : this.left + half + 4;
            Button b = addRenderableWidget(ToggleButton.create(x, y, half,
                    Component.translatable("gui.tacz_sewv.invasion.deploy." + kind.key()),
                    () -> force.playerDeploy().contains(kind),
                    v -> {
                        if (v) force.playerDeploy().add(kind);
                        else force.playerDeploy().remove(kind);
                    }));
            b.active = on;
            if (++col == 2) {
                col = 0;
                y += this.row;
            }
        }
        if (col != 0) y += this.row;
        y += 4;
        Button crew = addRenderableWidget(ToggleButton.create(this.left, y, this.panelW,
                Component.translatable("gui.tacz_sewv.invasion.spawn_npc"),
                force::npcCrewForPlayers, force::setNpcCrewForPlayers));
        crew.active = on;
        y += this.row;
        this.hintY = y;
    }

    // ---------------------------------------------------------------- pool helpers

    private List<String> worldPool(ForceKind kind) {
        return this.worldPools.getOrDefault(this.cfg.crewFaction, Map.of()).getOrDefault(kind, List.of());
    }

    /** Catalog ids whose vehicle data fits this kind — computed once per kind. */
    private List<String> fittingCatalog(ForceKind kind) {
        return this.fittingCatalog.computeIfAbsent(kind, k -> {
            List<String> out = new ArrayList<>();
            for (String id : VehiclePoolCatalog.mergedWith(this.catalog)) {
                if (VehicleClassify.sbwIdFits(k.poolCategory(), id)) out.add(id);
            }
            return out;
        });
    }

    @Nullable
    private String suggest(ForceKind kind, String typed) {
        return VehiclePoolCatalog.suggest(typed, fittingCatalog(kind), force().pool(kind));
    }

    private void addFromFilter(ForceKind kind) {
        if (this.filterBox == null || this.poolTable == null) return;
        String typed = this.filterBox.getValue().trim();
        String id = suggest(kind, typed);
        // An exact, well-formed id the catalog does not know is still allowed (addon loaded later).
        if (id == null && !typed.isEmpty() && ResourceLocation.tryParse(typed) != null) id = typed;
        if (id == null) return;
        List<String> pool = force().pool(kind);
        if (!pool.contains(id)) pool.add(id);
        this.poolTable.select(pool.indexOf(id));
        this.filterBox.setValue("");
    }

    /** name, class, state, note-key for an id in {@code kind}'s pool. Cached per (kind, id). */
    private String[] info(ForceKind kind, String id) {
        return this.rowInfo.computeIfAbsent(kind.key() + "|" + id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return new String[] {"?", "-", Integer.toString(SheetTable.BAD), "gui.tacz_sewv.pool.note.badid"};
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                return new String[] {"?", "-", Integer.toString(SheetTable.BAD), "gui.tacz_sewv.invasion.note.unregistered"};
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            String name = type == null ? "?" : type.getDescription().getString();
            var engine = VehiclePoolCatalogSource.engineOf(rl);
            String cls = PoolEditorScreen.engineLabel(engine);
            boolean fits = engine != null && VehicleClassify.fits(kind.poolCategory(), engine);
            return new String[] {name, cls, Integer.toString(fits ? SheetTable.LIVE : SheetTable.WARN),
                    fits ? null : "gui.tacz_sewv.invasion.note.mismatch"};
        });
    }

    private final class PoolModel implements SheetTable.Model {
        private final ForceKind kind;
        private final List<String> pool;

        PoolModel(ForceKind kind, List<String> pool) {
            this.kind = kind;
            this.pool = pool;
        }

        @Override
        public int size() {
            return this.pool.size();
        }

        @Override
        public String text(int row, int col) {
            String id = this.pool.get(row);
            return switch (col) {
                case 0 -> Integer.toString(row + 1);
                case 1 -> id;
                case 2 -> info(this.kind, id)[0];
                default -> info(this.kind, id)[1];
            };
        }

        @Override
        public int state(int row) {
            return Integer.parseInt(info(this.kind, this.pool.get(row))[2]);
        }

        @Override
        public List<Component> tip(int row) {
            String id = this.pool.get(row);
            String note = info(this.kind, id)[3];
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(id));
            if (note != null) {
                out.add(Component.translatable(note,
                        Component.translatable("gui.tacz_sewv.invasion.force." + this.kind.key()))
                        .withStyle(ChatFormatting.GOLD));
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- labels

    private Component forceTabLabel(ForceKind kind) {
        Component name = Component.translatable("gui.tacz_sewv.invasion.force." + kind.key());
        if (kind.isVehicle()) {
            return Component.translatable("gui.tacz_sewv.invasion.force.tab", name, force().aiCount(kind));
        }
        return Component.translatable("gui.tacz_sewv.invasion.force.tab", name, infantryCountText());
    }

    private String infantryCountText() {
        InfantrySpec inf = force().infantry();
        return switch (inf.mode) {
            case NONE -> "0";
            case FIXED -> Integer.toString(inf.fixed);
            case RANDOM -> Math.min(inf.min, inf.max) + "-" + Math.max(inf.min, inf.max);
        };
    }

    private Component infantryModeLabel() {
        return Component.translatable("gui.tacz_sewv.invasion.inf_mode",
                Component.translatable("gui.tacz_sewv.invasion.inf_mode." + force().infantry().mode.name().toLowerCase(Locale.ROOT)));
    }

    /** "Fields: 4 ground · 1 helicopter · 0 plane · 6-10 infantry" — the whole base at a glance. */
    private Component summary() {
        BaseForce f = force();
        MutableComponent out = Component.translatable("gui.tacz_sewv.invasion.summary",
                f.aiCount(ForceKind.GROUND), f.aiCount(ForceKind.ROTOR), f.aiCount(ForceKind.PLANE),
                infantryCountText());
        if (f.infantry().isEnabled() && !f.infantry().canCapture) {
            out.append(Component.translatable("gui.tacz_sewv.invasion.summary.no_capture"));
        }
        return out;
    }

    private Component assignedLabel() {
        return Component.translatable("gui.tacz_sewv.invasion.assigned_team",
                this.cfg.assignedTeam.isEmpty() ? "—" : this.cfg.assignedTeam);
    }

    private Component ownedLabel() {
        return Component.translatable("gui.tacz_sewv.invasion.owned_team",
                this.cfg.ownedTeam.isEmpty() ? "—" : this.cfg.ownedTeam);
    }

    private Component factionLabel() {
        return Component.translatable("gui.tacz_sewv.invasion.crew_faction", this.cfg.crewFaction.name());
    }

    private Component pmcOwnerLabel() {
        if (this.cfg.crewFaction != TankFaction.PMC || this.cfg.pmcOwnerKind == PmcOwnerKind.NONE) {
            return Component.translatable("gui.tacz_sewv.invasion.pmc_owner.empty");
        }
        MutableComponent value;
        if (this.cfg.pmcOwnerKind == PmcOwnerKind.PLAYER) {
            value = Component.literal(playerNameForUuid(this.cfg.pmcOwnerValue))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
        } else {
            value = Component.literal(this.cfg.pmcOwnerValue).withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
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
            if (kinds.get(i) == this.cfg.pmcOwnerKind && values.get(i).equals(this.cfg.pmcOwnerValue)) {
                idx = i;
                break;
            }
        }
        int next = (idx + 1) % kinds.size();
        this.cfg.pmcOwnerKind = kinds.get(next);
        this.cfg.pmcOwnerValue = values.get(next);
    }

    private String cycleTeam(String current) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(this.teams);
        int idx = options.indexOf(current);
        if (idx < 0) idx = 0;
        return options.get((idx + 1) % options.size());
    }

    // ---------------------------------------------------------------- input

    private void save() {
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveTeamBase(this.pos, this.cfg));
        onClose();
    }

    private void clampEnemyScroll() {
        this.enemyScroll = Math.max(0, Math.min(this.enemyScroll, this.teams.size() - this.enemyRows));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (this.tab == Tab.BASE && mx >= this.left && mx < this.left + this.panelW
                && my >= this.enemyTop && my < this.enemyTop + this.enemyRows * LIST_ROW_H) {
            int row = (int) ((my - this.enemyTop) / LIST_ROW_H) + this.enemyScroll;
            if (row >= 0 && row < this.teams.size()) {
                String team = this.teams.get(row);
                if (!team.equals(this.cfg.assignedTeam)) {
                    if (!this.cfg.enemyTeams.remove(team)) this.cfg.enemyTeams.add(team);
                }
                return true;
            }
        }
        if (this.poolTable != null && this.poolTable.mouseClicked(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.poolTable != null && this.poolTable.mouseDragged(my)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.poolTable != null) this.poolTable.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double delta) {
        if (this.tab == Tab.BASE) {
            this.enemyScroll += delta > 0 ? -1 : 1;
            clampEnemyScroll();
            return true;
        }
        if (this.poolTable != null) {
            this.poolTable.mouseScrolled(delta);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, delta);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        super.render(g, mx, my, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height < COMPACT_H ? 5 : 8, 0xFFFFFF);

        switch (this.tab) {
            case BASE -> renderBase(g);
            case ZONE -> renderFieldLabels(g);
            case FORCES -> renderForces(g, mx, my);
            case DEPLOY -> renderDeploy(g);
        }

        g.drawCenteredString(this.font, summary(), this.width / 2, this.footerY - 11, 0xC0C0C0);
        if (!this.hoverTips.render(g, this.font, mx, my) && this.poolTable != null) {
            List<Component> tip = this.poolTable.hoverTip(mx, my);
            if (tip != null && !tip.isEmpty()) SheetTable.drawTip(g, this.font, tip, mx, my);
        }
    }

    /** Labels for every integer box on screen, read off the box's own message. */
    private void renderFieldLabels(GuiGraphics g) {
        for (var r : this.renderables) {
            if (r instanceof EditBox box && !(box instanceof PoolVehicleIdEditBox)) {
                int color = box.active ? 0xA0A0A0 : 0x505050;
                g.drawString(this.font, box.getMessage(), box.getX() - 106, box.getY() + 6, color, false);
            }
        }
    }

    private void renderBase(GuiGraphics g) {
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.invasion.enemy_teams"),
                this.left, this.enemyTop - 11, 0xA0A0A0, false);
        g.fill(this.left - 2, this.enemyTop - 2, this.left + this.panelW + 2,
                this.enemyTop + this.enemyRows * LIST_ROW_H + 2, 0x88000000);
        for (int i = 0; i < this.enemyRows; i++) {
            int idx = i + this.enemyScroll;
            if (idx >= this.teams.size()) break;
            String team = this.teams.get(idx);
            boolean enemy = this.cfg.enemyTeams.contains(team);
            boolean self = team.equals(this.cfg.assignedTeam);
            Component label = Component.translatable(enemy
                    ? "gui.tacz_sewv.invasion.enemy_yes" : "gui.tacz_sewv.invasion.enemy_no", team);
            int color = self ? 0xFF666666 : enemy ? 0xFFFF5555 : 0xFF55FF55;
            g.drawString(this.font, label, this.left + 4, this.enemyTop + i * LIST_ROW_H + 2, color, false);
        }
        if (this.teams.isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.invasion.no_teams"),
                    this.left + 4, this.enemyTop + 2, 0xFFAA00, false);
        }
    }

    private void renderForces(GuiGraphics g, int mx, int my) {
        if (this.forceTab == ForceKind.INFANTRY) {
            renderFieldLabels(g);
        } else if (this.poolTable != null) {
            this.poolTable.render(g, this.font, mx, my);
        }
        drawHint(g, Component.translatable("gui.tacz_sewv.invasion.hint." + this.forceTab.key()));
    }

    private void renderDeploy(GuiGraphics g) {
        Component hint = this.cfg.playerOwned
                ? Component.translatable("gui.tacz_sewv.invasion.hint.deploy")
                : Component.translatable("gui.tacz_sewv.invasion.hint.deploy_ai");
        if (this.cfg.playerOwned && force().deployOptions().isEmpty()) {
            drawHint(g, Component.translatable("gui.tacz_sewv.invasion.hint.deploy_none"), 0xFFAA00);
        } else {
            drawHint(g, hint, 0x909090);
        }
    }

    private void drawHint(GuiGraphics g, Component hint) {
        drawHint(g, hint, 0x909090);
    }

    /**
     * Word-wrapped help under the controls, clipped to the room left above the summary line: at a
     * high GUI scale the last lines are dropped rather than drawn over the footer.
     */
    private void drawHint(GuiGraphics g, Component hint, int color) {
        int y = this.hintY + 2;
        int bottom = this.footerY - 13;
        for (var line : this.font.split(hint, this.panelW)) {
            if (y + this.font.lineHeight > bottom) break;
            g.drawString(this.font, line, this.left, y, color, false);
            y += this.font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
