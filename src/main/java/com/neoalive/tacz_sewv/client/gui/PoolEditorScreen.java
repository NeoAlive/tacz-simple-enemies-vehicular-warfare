package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.client.editor.GrenadePoolCatalog;
import com.neoalive.tacz_sewv.client.editor.IdSearch;
import com.neoalive.tacz_sewv.client.editor.VehiclePoolCatalog;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateVehiclePools;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePoolCatalogSource;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Creative admin UI for world vehicle pools ({@code /sewv pool vehicles}), laid out like the loadout
 * manager: a sheet of entries with a status dot, class check and spawn chance, and an autofill
 * candidate list under the add box. Edits a local snapshot; Save pushes
 * {@link PacketUpdateVehiclePools}. Opened only via server {@code PacketOpenPoolEditor}.
 *
 * <p>What the sheet knows that the old plain list did not, all of it read from the spawner's own
 * rules: an id that is not registered is skipped at spawn ({@code TankSpawner.pickVehicleType}
 * filters to registered types first), the pick among the rest is uniform, and a hull whose engine
 * class does not fit the pool (a helicopter in Ground, say) is flagged.
 */
public class PoolEditorScreen extends Screen {

    private static final int PANEL_W_PREF = 460;
    private static final int CAND_LINES = 3;
    private static final int CAND_H = 10;
    /** Label + add row, candidates, buttons and padding under the sheet. */
    private static final int BELOW_GRID = 10 + 20 + 4 + CAND_LINES * CAND_H + 4 + 20 + 6;

    private static final String[] COLS = {"n", "id", "name", "class", "def", "pct"};

    /** How one id reads in the sheet; resolved once per (category, id) and kept for the screen's life. */
    private record Info(String name, String engine, int state, @Nullable Component note) {}

    private final Map<TankFaction, Map<Category, List<String>>> pools;
    private final Map<TankFaction, Map<Category, List<String>>> defaults;
    private final List<String> catalog;

    private TankFaction faction = TankFaction.RU;
    private Category category = Category.GROUND;

    private final TabStrip factionStrip;
    private final TabStrip categoryStrip;
    private final SheetTable table;
    private final Map<String, Info> infos = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private PoolVehicleIdEditBox filterBox;
    private List<String> activeCatalogList = List.of();
    private List<String> candidates = List.of();
    private int catalogRetryTicks;

    private int left;
    private int panelW;
    private int summaryY;
    private int labelY;
    private int candTop;
    private int liveCount;
    private int badCount;
    private int warnCount;

    public PoolEditorScreen(Map<TankFaction, Map<Category, List<String>>> pools,
                            Map<TankFaction, Map<Category, List<String>>> defaults,
                            List<String> catalog) {
        super(Component.translatable("gui.tacz_sewv.pool.title"));
        this.pools = deepCopy(pools);
        this.defaults = deepCopy(defaults);
        this.catalog = List.copyOf(catalog);

        this.factionStrip = new TabStrip(50, i -> {
            this.faction = TankFaction.values()[i];
            switched();
        });
        for (TankFaction f : TankFaction.values()) {
            this.factionStrip.add(Component.literal(f.name()), Component.translatable("gui.tacz_sewv.pool.tip.faction"));
        }
        this.categoryStrip = new TabStrip(60, i -> {
            this.category = Category.values()[i];
            switched();
        });
        for (Category c : Category.values()) {
            String key = c.name().toLowerCase(Locale.ROOT);
            this.categoryStrip.add(Component.translatable("gui.tacz_sewv.pool.cat." + key),
                    Component.translatable("gui.tacz_sewv.pool.tip.cat." + key));
        }

        List<SheetTable.Col> cols = new ArrayList<>();
        cols.add(col("n", 24, true));
        cols.add(col("id", -3, false));
        cols.add(col("name", -2, false));
        cols.add(col("class", 46, false));
        cols.add(col("def", 28, false));
        cols.add(col("pct", 34, true));
        this.table = new SheetTable(cols, new PoolModel(), Component.translatable("gui.tacz_sewv.pool.tip.col.st"));
    }

    private static SheetTable.Col col(String key, int width, boolean numeric) {
        return new SheetTable.Col(key, width, numeric, Component.translatable("gui.tacz_sewv.pool.col." + key),
                Component.translatable("gui.tacz_sewv.pool.tip.col." + key));
    }

    private static Map<TankFaction, Map<Category, List<String>>> deepCopy(
            Map<TankFaction, Map<Category, List<String>>> src) {
        Map<TankFaction, Map<Category, List<String>>> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category c : Category.values()) {
                byCat.put(c, new ArrayList<>(src.get(f).get(c)));
            }
            out.put(f, byCat);
        }
        return out;
    }

    private List<String> currentPool() {
        return this.pools.get(this.faction).get(this.category);
    }

    private void switched() {
        this.table.reset();
        this.filterBox.setValue("");
        reloadCatalog();
        refreshCandidates();
    }

    // ---------------------------------------------------------------- catalog

    /** Client scan merged with the server snapshot from the open packet. */
    private void reloadCatalog() {
        this.activeCatalogList = this.category == Category.GRENADE
                ? GrenadePoolCatalog.ids() : VehiclePoolCatalog.mergedWith(this.catalog);
    }

    private String labelOf(String id) {
        return this.names.computeIfAbsent(this.category.name() + "|" + id, k -> info(id).name());
    }

    private Info info(String id) {
        return this.infos.computeIfAbsent(this.category.name() + "|" + id, k -> resolve(id));
    }

    private Info resolve(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return new Info("?", "-", SheetTable.BAD, Component.translatable("gui.tacz_sewv.pool.note.badid"));
        }
        if (this.category == Category.GRENADE) {
            if (!ForgeRegistries.ITEMS.containsKey(rl)) return unregistered();
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            return new Info(item == null ? "?" : item.getDescription().getString(), "-", SheetTable.LIVE, null);
        }
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return unregistered();
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        String name = type == null ? "?" : type.getDescription().getString();
        EngineType engine = VehiclePoolCatalogSource.engineOf(rl);
        Component mismatch = mismatch(engine);
        return new Info(name, engineLabel(engine), mismatch == null ? SheetTable.LIVE : SheetTable.WARN, mismatch);
    }

    private static Info unregistered() {
        return new Info("?", "-", SheetTable.BAD, Component.translatable("gui.tacz_sewv.pool.note.unregistered"));
    }

    private static String engineLabel(@Nullable EngineType e) {
        if (e == null) return "?";
        return switch (e) {
            case WHEEL -> "Wheel";
            case TRACK -> "Track";
            case HELICOPTER -> "Heli";
            case SHIP -> "Ship";
            case AIRCRAFT -> "Plane";
            case FIXED -> "Fixed";
            case EMPTY -> "None";
            default -> e.name().charAt(0) + e.name().substring(1).toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Null when the hull fits this pool. Only the four hull classes are checked: TOW / mortar pools
     * hold emplacements of any engine class, and Ground legitimately takes Fixed mounts.
     */
    @Nullable
    private Component mismatch(@Nullable EngineType e) {
        boolean checked = switch (this.category) {
            case GROUND, SHIP, PLANE, HELI -> true;
            default -> false;
        };
        if (!checked) return null;
        if (e == null || e == EngineType.EMPTY) {
            return Component.translatable("gui.tacz_sewv.pool.note.no_data");
        }
        boolean fits = switch (this.category) {
            case GROUND -> e != EngineType.HELICOPTER && e != EngineType.SHIP
                    && e != EngineType.AIRCRAFT && e != EngineType.AIRSHIP;
            case SHIP -> e == EngineType.SHIP;
            case PLANE -> e == EngineType.AIRCRAFT;
            case HELI -> e == EngineType.HELICOPTER;
            default -> true;
        };
        return fits ? null : Component.translatable("gui.tacz_sewv.pool.note.mismatch", engineLabel(e),
                Component.translatable("gui.tacz_sewv.pool.cat." + this.category.name().toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        VehiclePoolCatalog.ensureLoaded();
        GrenadePoolCatalog.ensureLoaded();
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.left = (this.width - this.panelW) / 2;

        this.factionStrip.select(this.faction.ordinal());
        this.categoryStrip.select(this.category.ordinal());
        int y = this.factionStrip.layout(this::addRenderableWidget, this.left, 22, Math.min(176, this.panelW));
        y = this.categoryStrip.layout(this::addRenderableWidget, this.left, y, this.panelW);

        this.summaryY = y + 2;
        int headY = this.summaryY + 12;
        int rows = SheetTable.rowsFor(this.height, headY + SheetTable.HEAD_H, BELOW_GRID, 4, 14);
        this.table.layout(this.left, headY, this.panelW, rows);

        this.labelY = this.table.bottom() + 4;
        int addY = this.labelY + 10;
        this.candTop = addY + 24;
        int buttonsY = this.candTop + CAND_LINES * CAND_H + 4;

        String previous = this.filterBox == null ? "" : this.filterBox.getValue();
        this.filterBox = new PoolVehicleIdEditBox(this.font, this.left, addY, this.panelW - 88, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setHint(Component.translatable("gui.tacz_sewv.pool.hint.search"));
        this.filterBox.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.pool.tip.filter")));
        this.filterBox.setResponder(s -> refreshCandidates());
        this.filterBox.setTabCompleter(this::applyTabCompletion);
        addRenderableWidget(this.filterBox);
        setInitialFocus(this.filterBox);
        this.filterBox.setValue(previous);

        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(this.left + this.panelW - 84, addY, 84, 20).build(), "add"));

        int bw = (this.panelW - 2 * 4) / 3;
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(this.left, buttonsY, bw, 20).build(), "remove"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.reset"), b -> resetCurrent())
                .bounds(this.left + bw + 4, buttonsY, bw, 20).build(), "reset"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.save"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new PacketUpdateVehiclePools(this.pools));
            onClose();
        }).bounds(this.left + 2 * (bw + 4), buttonsY, this.panelW - 2 * (bw + 4), 20).build(), "save"));

        reloadCatalog();
        refreshCandidates();
    }

    private static Button tip(Button b, String key) {
        b.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.pool.tip." + key)));
        return b;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.category == Category.GRENADE || !this.activeCatalogList.isEmpty()) return;
        if (++this.catalogRetryTicks % 40 != 0) return;
        VehiclePoolCatalog.rebuildIfEmpty();
        reloadCatalog();
        refreshCandidates();
    }

    // ---------------------------------------------------------------- add / edit

    private void refreshCandidates() {
        if (this.filterBox == null) return;
        List<String> pool = currentPool();
        List<String> free = new ArrayList<>();
        for (String id : this.activeCatalogList) {
            if (!pool.contains(id)) free.add(id);
        }
        this.candidates = IdSearch.match(this.filterBox.getValue(), free, this::labelOf, CAND_LINES);
    }

    private boolean applyTabCompletion() {
        if (this.candidates.isEmpty()) return false;
        String id = this.candidates.get(0);
        this.filterBox.setValue(id);
        this.filterBox.setCursorPosition(id.length());
        return true;
    }

    private void addFromFilter() {
        String id = resolveAddId(this.filterBox.getValue().trim());
        if (id == null) return;
        List<String> pool = currentPool();
        if (!pool.contains(id)) pool.add(id);
        this.table.select(pool.indexOf(id));
        this.filterBox.setValue("");
        refreshCandidates();
    }

    /** Exact typed id, else the top candidate; a well-formed pasted id is accepted even if the scan missed it. */
    @Nullable
    private String resolveAddId(String typed) {
        if (!typed.isEmpty()) {
            for (String id : this.activeCatalogList) {
                if (id.equalsIgnoreCase(typed) || id.toLowerCase(Locale.ROOT).endsWith(":" + typed.toLowerCase(Locale.ROOT))) {
                    return id;
                }
            }
            if (ResourceLocation.tryParse(typed) != null && typed.contains(":")) return typed;
        }
        return this.candidates.isEmpty() ? null : this.candidates.get(0);
    }

    private void removeSelected() {
        List<String> pool = currentPool();
        int sel = this.table.selected();
        if (sel < 0 || sel >= pool.size()) return;
        pool.remove(sel);
        this.table.clampScroll();
        refreshCandidates();
    }

    private void resetCurrent() {
        List<String> pool = currentPool();
        pool.clear();
        pool.addAll(this.defaults.get(this.faction).get(this.category));
        this.table.reset();
        refreshCandidates();
    }

    // ---------------------------------------------------------------- sheet model

    private void refreshStats() {
        this.liveCount = 0;
        this.badCount = 0;
        this.warnCount = 0;
        for (String id : currentPool()) {
            switch (info(id).state()) {
                case SheetTable.BAD -> this.badCount++;
                case SheetTable.WARN -> {
                    this.warnCount++;
                    this.liveCount++;
                }
                default -> this.liveCount++;
            }
        }
    }

    private final class PoolModel implements SheetTable.Model {

        @Override
        public int size() {
            return currentPool().size();
        }

        @Override
        public String text(int row, int col) {
            String id = currentPool().get(row);
            Info i = info(id);
            return switch (COLS[col]) {
                case "n" -> Integer.toString(row + 1);
                case "id" -> id;
                case "name" -> i.name();
                case "class" -> i.engine();
                case "def" -> isDefault(id) ? "*" : "";
                case "pct" -> i.state() == SheetTable.BAD || liveCount == 0 ? "-"
                        : String.format(Locale.ROOT, "%.0f%%", 100.0 / liveCount);
                default -> "";
            };
        }

        @Override
        public int state(int row) {
            return info(currentPool().get(row)).state();
        }

        @Override
        public int color(int row, int col) {
            return COLS[col].equals("def") ? 0xFF7FD67F : 0;
        }

        @Override
        public List<Component> tip(int row) {
            String id = currentPool().get(row);
            Info i = info(id);
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(i.name()).withStyle(ChatFormatting.YELLOW));
            out.add(Component.literal(id).withStyle(ChatFormatting.GRAY));
            if (i.note() != null) out.add(i.note());
            if (i.state() != SheetTable.BAD) {
                out.add(Component.translatable("gui.tacz_sewv.pool.tip.row.chance",
                        liveCount == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 / liveCount)));
            }
            out.add(Component.translatable(isDefault(id)
                    ? "gui.tacz_sewv.pool.tip.row.default" : "gui.tacz_sewv.pool.tip.row.custom"));
            return out;
        }
    }

    private boolean isDefault(String id) {
        return this.defaults.get(this.faction).get(this.category).contains(id);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.filterBox != null && this.filterBox.isFocused()
                && (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER)) {
            addFromFilter();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.table.mouseClicked(mouseX, mouseY)) return true;
        if (mouseX >= this.left && mouseX < this.left + this.panelW
                && mouseY >= this.candTop && mouseY < this.candTop + CAND_LINES * CAND_H) {
            int i = (int) ((mouseY - this.candTop) / CAND_H);
            if (i >= 0 && i < this.candidates.size()) {
                this.filterBox.setValue(this.candidates.get(i));
                this.filterBox.setCursorPosition(this.filterBox.getValue().length());
                setFocused(this.filterBox);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return this.table.mouseDragged(mouseY) || super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.table.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.table.mouseScrolled(delta);
        return true;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.factionStrip.renderSelection(g);
        this.categoryStrip.renderSelection(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        refreshStats();
        renderSummary(g);
        this.table.render(g, this.font, mouseX, mouseY);

        g.drawString(this.font, Component.translatable("gui.tacz_sewv.pool.lbl.add"), this.left, this.labelY, 0xFF909090, false);
        if (!this.candidates.isEmpty()) {
            Component hint = Component.translatable("gui.tacz_sewv.pool.lbl.complete");
            g.drawString(this.font, hint, this.left + this.panelW - this.font.width(hint), this.labelY, 0xFF909090, false);
        }
        renderCandidates(g);

        List<Component> tip = this.table.hoverTip(mouseX, mouseY);
        if (tip != null) SheetTable.drawTip(g, this.font, tip, mouseX, mouseY);
    }

    private void renderSummary(GuiGraphics g) {
        Component cat = Component.translatable("gui.tacz_sewv.pool.cat." + this.category.name().toLowerCase(Locale.ROOT));
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.pool.summary", this.faction.name(), cat,
                currentPool().size(), this.liveCount), this.left, this.summaryY, 0xFFA0A0A0, false);
        List<Component> flags = new ArrayList<>();
        if (this.badCount > 0) flags.add(Component.translatable("gui.tacz_sewv.pool.flag.unknown", this.badCount));
        if (this.warnCount > 0) flags.add(Component.translatable("gui.tacz_sewv.pool.flag.class", this.warnCount));
        int x = this.left + this.panelW;
        for (Component f : flags) {
            x -= this.font.width(f) + 8;
            g.drawString(this.font, f, x, this.summaryY, this.badCount > 0 && f == flags.get(0) ? 0xFFFF6666 : 0xFFFFC04D, false);
        }
    }

    private void renderCandidates(GuiGraphics g) {
        boolean pending = this.category != Category.GRENADE && this.activeCatalogList.isEmpty();
        if (pending) {
            g.drawString(this.font, Component.translatable(this.catalog.isEmpty()
                    ? "gui.tacz_sewv.pool.catalog_empty" : "gui.tacz_sewv.pool.catalog_loading"),
                    this.left + 2, this.candTop, 0xFFFFAA55, false);
            return;
        }
        for (int i = 0; i < this.candidates.size(); i++) {
            String id = this.candidates.get(i);
            String label = labelOf(id);
            String line = label.equals("?") ? id : id + "  -  " + label;
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.panelW), this.left + 2,
                    this.candTop + i * CAND_H, i == 0 ? 0xFFFFFF55 : 0xFFB0B0B0, false);
        }
        if (this.candidates.isEmpty() && !this.filterBox.getValue().isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.pool.no_match"),
                    this.left + 2, this.candTop, 0xFFFF6666, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
