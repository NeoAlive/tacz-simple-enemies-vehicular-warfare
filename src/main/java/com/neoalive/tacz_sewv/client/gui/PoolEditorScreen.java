package com.neoalive.tacz_sewv.client.gui;

import com.neoalive.tacz_sewv.client.VehiclePoolCatalog;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateVehiclePools;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creative admin UI for world vehicle pools. Edits a local snapshot; Save pushes
 * {@link PacketUpdateVehiclePools}. Opened only via server {@code PacketOpenPoolEditor}.
 */
public class PoolEditorScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int LIST_ROWS = 10;

    private final Map<TankFaction, Map<Category, List<String>>> pools;
    private final Map<TankFaction, Map<Category, List<String>>> defaults;
    private final List<String> catalog;

    private TankFaction faction = TankFaction.RU;
    private Category category = Category.GROUND;
    private int scroll;
    private int selected = -1;
    private EditBox filterBox;
    private List<String> activeCatalogList = List.of();
    private List<String> filteredCatalog = List.of();
    @Nullable
    private String autocompleteSuggestion = null;
    private int catalogRetryTicks = 0;

    public PoolEditorScreen(Map<TankFaction, Map<Category, List<String>>> pools,
                            Map<TankFaction, Map<Category, List<String>>> defaults,
                            List<String> catalog) {
        super(Component.translatable("gui.tacz_sewv.pool.title"));
        this.pools = deepCopy(pools);
        this.defaults = deepCopy(defaults);
        this.catalog = List.copyOf(catalog);
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

    /** Client scan merged with the server snapshot from the open packet. */
    private void reloadCatalog() {
        this.activeCatalogList = VehiclePoolCatalog.mergedWith(this.catalog);
    }

    @Override
    protected void init() {
        VehiclePoolCatalog.ensureLoaded();
        reloadCatalog();
        int left = (this.width - PANEL_W) / 2;
        int top = 28;

        int x = left;
        for (TankFaction f : TankFaction.values()) {
            final TankFaction ff = f;
            addRenderableWidget(Button.builder(Component.literal(f.name()), b -> {
                this.faction = ff;
                this.scroll = 0;
                this.selected = -1;
                refreshFilter();
            }).bounds(x, top, 60, 20).build());
            x += 64;
        }

        x = left;
        int catY = top + 24;
        for (Category c : Category.values()) {
            final Category cc = c;
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.tacz_sewv.pool.cat." + c.name().toLowerCase(Locale.ROOT)),
                    b -> {
                        this.category = cc;
                        this.scroll = 0;
                        this.selected = -1;
                        refreshFilter();
                    }).bounds(x, catY, 72, 20).build());
            x += 76;
        }

        int listTop = catY + 28;
        int listBottom = listTop + LIST_ROWS * 12;

        this.filterBox = new PoolVehicleIdEditBox(this.font, left, listBottom + 8, PANEL_W - 90, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        ((PoolVehicleIdEditBox) this.filterBox).setTabCompleter(this::applyTabCompletion);
        addRenderableWidget(this.filterBox);
        setInitialFocus(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + PANEL_W - 84, listBottom + 8, 84, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 32, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.reset"), b -> resetCurrent())
                .bounds(left + 108, listBottom + 32, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.save"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new PacketUpdateVehiclePools(this.pools));
            onClose();
        }).bounds(left + PANEL_W - 100, listBottom + 32, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + PANEL_W - 20, listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            List<String> pool = currentPool();
            if (this.scroll + LIST_ROWS < pool.size()) this.scroll++;
        }).bounds(left + PANEL_W - 20, listBottom - 20, 20, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.activeCatalogList.isEmpty()) return;
        if (++this.catalogRetryTicks % 40 != 0) return;
        VehiclePoolCatalog.rebuildIfEmpty();
        reloadCatalog();
        refreshFilter();
    }

    private void refreshFilter() {
        String q = this.filterBox != null ? this.filterBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<String> catalog = this.activeCatalogList;
        List<String> pool = currentPool();
        List<String> out = new ArrayList<>();
        for (String id : catalog) {
            if (pool.contains(id)) continue;
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(id);
        }
        this.filteredCatalog = out;
        String typed = this.filterBox != null ? this.filterBox.getValue() : "";
        this.autocompleteSuggestion = VehiclePoolCatalog.suggest(typed, catalog, pool);
    }

    private boolean applyTabCompletion() {
        if (this.filterBox == null || this.autocompleteSuggestion == null) return false;
        this.filterBox.setValue(this.autocompleteSuggestion);
        this.filterBox.setCursorPosition(this.autocompleteSuggestion.length());
        refreshFilter();
        return true;
    }

    private void addFromFilter() {
        refreshFilter();
        String typed = this.filterBox != null ? this.filterBox.getValue().trim() : "";
        String id = resolveAddId(typed);
        if (id == null) return;

        List<String> pool = currentPool();
        if (!pool.contains(id)) {
            pool.add(id);
        }
        this.selected = pool.indexOf(id);
        // New entries are appended; without this they land below the 10-row window and
        // look like Add did nothing.
        ensureSelectedVisible();
        refreshFilter();
    }

    /** Exact typed id, else first catalog match; null when nothing usable. */
    private String resolveAddId(String typed) {
        if (!typed.isEmpty()) {
            String lower = typed.toLowerCase(Locale.ROOT);
            for (String id : this.filteredCatalog) {
                if (id.equalsIgnoreCase(typed) || id.toLowerCase(Locale.ROOT).endsWith(":" + lower)) {
                    return id;
                }
            }
            // Allow pasting a well-formed id even if the catalog scan missed it.
            if (ResourceLocation.tryParse(typed) != null) {
                return typed;
            }
        }
        return this.filteredCatalog.isEmpty() ? null : this.filteredCatalog.get(0);
    }

    private void ensureSelectedVisible() {
        if (this.selected < 0) return;
        if (this.selected < this.scroll) {
            this.scroll = this.selected;
        } else if (this.selected >= this.scroll + LIST_ROWS) {
            this.scroll = this.selected - LIST_ROWS + 1;
        }
    }

    private void removeSelected() {
        List<String> pool = currentPool();
        if (this.selected < 0 || this.selected >= pool.size()) return;
        pool.remove(this.selected);
        if (this.selected >= pool.size()) this.selected = pool.size() - 1;
        refreshFilter();
    }

    private void resetCurrent() {
        List<String> pool = currentPool();
        pool.clear();
        pool.addAll(this.defaults.get(this.faction).get(this.category));
        this.selected = -1;
        this.scroll = 0;
        refreshFilter();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - PANEL_W) / 2;
        int listTop = 28 + 24 + 28;
        if (mouseX >= left && mouseX < left + PANEL_W - 24
                && mouseY >= listTop && mouseY < listTop + LIST_ROWS * 12) {
            int row = (int) ((mouseY - listTop) / 12) + this.scroll;
            List<String> pool = currentPool();
            if (row >= 0 && row < pool.size()) {
                this.selected = row;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<String> pool = currentPool();
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + LIST_ROWS < pool.size()) this.scroll++;
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        int listTop = 28 + 24 + 28;
        List<String> pool = currentPool();
        graphics.fill(left - 2, listTop - 2, left + PANEL_W - 22, listTop + LIST_ROWS * 12 + 2, 0x88000000);

        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = i + this.scroll;
            if (idx >= pool.size()) break;
            int y = listTop + i * 12;
            int color = idx == this.selected ? 0xFFFFAA00 : 0xFFE0E0E0;
            graphics.drawString(this.font, pool.get(idx), left + 4, y + 2, color, false);
        }

        String hint = Component.translatable("gui.tacz_sewv.pool.hint",
                this.faction.name(),
                Component.translatable("gui.tacz_sewv.pool.cat." + this.category.name().toLowerCase(Locale.ROOT)).getString(),
                pool.size()).getString();
        graphics.drawString(this.font, hint, left, listTop + LIST_ROWS * 12 + 58, 0xA0A0A0, false);

        if (this.activeCatalogList.isEmpty()) {
            Component msg = this.catalog.isEmpty()
                    ? Component.translatable("gui.tacz_sewv.pool.catalog_empty")
                    : Component.translatable("gui.tacz_sewv.pool.catalog_loading");
            graphics.drawString(this.font, msg, left, listTop + LIST_ROWS * 12 + 70, 0xFFAA55, false);
        } else if (this.filterBox != null && this.autocompleteSuggestion != null) {
            String typed = this.filterBox.getValue();
            String suffix = VehiclePoolCatalog.completionSuffix(typed, this.autocompleteSuggestion);
            if (!suffix.isEmpty()) {
                int boxX = this.filterBox.getX();
                int boxY = this.filterBox.getY() + (this.filterBox.getHeight() - 8) / 2;
                int typedX = boxX + 4 + this.font.width(typed);
                graphics.drawString(this.font, suffix, typedX, boxY, 0x808080, false);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
