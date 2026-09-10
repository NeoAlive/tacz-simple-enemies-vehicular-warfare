package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.block.SpawnProbeCategory;
import com.neoalive.tacz_sewv.block.SpawnProbeInfantryEntry;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveSpawnProbe;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Op config UI for a spawn_probe. Exclusive Vehicle|Infantry category; Faction Type always saved.
 * Snapshot edited locally; Apply pushes {@link PacketSaveSpawnProbe}.
 * Layout shrinks list rows via {@link GuiFit} so bottom buttons stay on-screen at high GUI scale.
 */
public class SpawnProbeScreen extends Screen {

    private static final int PANEL_W_PREF = 340;
    private static final int LIST_ROWS_PREF = 8;
    private static final int LIST_ROW_H = 12;
    private static final int BTN_H = 20;
    private static final int BOTTOM_MARGIN = 8;

    private final BlockPos pos;
    private SpawnProbeCategory category;
    private TankFaction factionType;
    private final List<String> vehicleList;
    private final List<SpawnProbeInfantryEntry> infantryList;
    private final List<String> vehicleCatalog;
    private final List<String> infantryCatalog;
    private boolean preCrewedSpawn;

    private int panelW;
    private int panelLeft;
    private int listRows;
    private EditBox filterBox;
    private Button factionButton;
    private Button categoryButton;
    private Button preCrewedButton;
    private Button qtyMinusButton;
    private Button qtyPlusButton;
    private ChanceSlider chanceSlider;
    private List<String> filteredCatalog = List.of();
    private int selected = -1;
    private int scroll;
    private int listTop;
    private int editStripY;

    public SpawnProbeScreen(BlockPos pos, SpawnProbeCategory category, TankFaction factionType,
                            List<String> vehicleList, boolean preCrewedSpawn,
                            List<SpawnProbeInfantryEntry> infantryList,
                            List<String> vehicleCatalog, List<String> infantryCatalog) {
        super(Component.translatable("gui.tacz_sewv.spawn_probe.title"));
        this.pos = pos;
        this.category = category == null ? SpawnProbeCategory.VEHICLE : category;
        this.factionType = factionType == null ? TankFaction.RU : factionType;
        this.vehicleList = new ArrayList<>(vehicleList);
        this.preCrewedSpawn = preCrewedSpawn;
        this.infantryList = new ArrayList<>(infantryList);
        this.vehicleCatalog = List.copyOf(vehicleCatalog);
        this.infantryCatalog = List.copyOf(infantryCatalog);
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.panelLeft = (this.width - this.panelW) / 2;

        int left = this.panelLeft;
        int y = Math.min(32, Math.max(8, this.height / 16));

        this.factionButton = addRenderableWidget(Button.builder(factionLabel(), b -> {
            this.factionType = nextFaction(this.factionType);
            this.factionButton.setMessage(factionLabel());
        }).bounds(left, y, this.panelW, BTN_H).build());
        y += 24;

        this.categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> {
            this.category = this.category == SpawnProbeCategory.VEHICLE
                    ? SpawnProbeCategory.INFANTRY : SpawnProbeCategory.VEHICLE;
            this.selected = -1;
            this.scroll = 0;
            rebuildWidgets();
        }).bounds(left, y, this.panelW, BTN_H).build());
        y += 24;

        if (this.category == SpawnProbeCategory.VEHICLE) {
            this.preCrewedButton = addRenderableWidget(Button.builder(preCrewedLabel(), b -> {
                this.preCrewedSpawn = !this.preCrewedSpawn;
                this.preCrewedButton.setMessage(preCrewedLabel());
            }).bounds(left, y, this.panelW, BTN_H).build());
            y += 24;
        } else {
            this.preCrewedButton = null;
        }

        // Space below the list: filter + remove + (infantry strip) + apply + margins.
        int chromeBelow = this.category == SpawnProbeCategory.INFANTRY ? 124 : 96;
        this.listTop = y + 12;
        int available = this.height - this.listTop - chromeBelow - BOTTOM_MARGIN;
        this.listRows = Mth.clamp(available / LIST_ROW_H, 1, LIST_ROWS_PREF);
        if (this.scroll + this.listRows > listSize() && listSize() > 0) {
            this.scroll = Math.max(0, listSize() - this.listRows);
        }
        int listBottom = this.listTop + this.listRows * LIST_ROW_H;

        int addW = Math.min(80, Math.max(56, this.panelW / 5));
        this.filterBox = new EditBox(this.font, left, listBottom + 4, this.panelW - addW - 4, BTN_H,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        addRenderableWidget(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + this.panelW - addW, listBottom + 4, addW, BTN_H).build());

        int removeW = Math.max(100, this.font.width(Component.translatable("gui.tacz_sewv.pool.remove")) + 16);
        removeW = Math.min(removeW, this.panelW / 2);
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 28, removeW, BTN_H).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + this.panelW - 20, this.listTop, 20, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.scroll + this.listRows < listSize()) this.scroll++;
        }).bounds(left + this.panelW - 20, Math.max(this.listTop, listBottom - BTN_H), 20, BTN_H).build());

        this.editStripY = listBottom + 52;
        if (this.category == SpawnProbeCategory.INFANTRY) {
            this.qtyMinusButton = addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustQty(-1))
                    .bounds(left, this.editStripY, 20, BTN_H).build());
            this.qtyPlusButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustQty(1))
                    .bounds(left + 72, this.editStripY, 20, BTN_H).build());
            int sliderW = Math.max(80, this.panelW - 100);
            this.chanceSlider = addRenderableWidget(
                    new ChanceSlider(left + 100, this.editStripY, sliderW, BTN_H));
            syncInfantryEditors();
        } else {
            this.qtyMinusButton = null;
            this.qtyPlusButton = null;
            this.chanceSlider = null;
        }

        int applyY = this.category == SpawnProbeCategory.INFANTRY ? this.editStripY + 28 : listBottom + 52;
        // Keep Apply/Cancel fully on-screen — clipped descenders were the bottom overflowing.
        applyY = Math.min(applyY, this.height - BTN_H - BOTTOM_MARGIN);
        int half = this.panelW / 2 - 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.spawn_probe.apply"), b -> apply())
                .bounds(left, applyY, half, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + this.panelW / 2 + 4, applyY, half, BTN_H).build());
    }

    private Component factionLabel() {
        return Component.translatable("gui.tacz_sewv.spawn_probe.faction", this.factionType.name());
    }

    private Component categoryLabel() {
        return Component.translatable(this.category == SpawnProbeCategory.VEHICLE
                ? "gui.tacz_sewv.spawn_probe.category.vehicle"
                : "gui.tacz_sewv.spawn_probe.category.infantry");
    }

    private Component preCrewedLabel() {
        return Component.translatable(this.preCrewedSpawn
                ? "gui.tacz_sewv.spawn_probe.pre_crewed.on"
                : "gui.tacz_sewv.spawn_probe.pre_crewed.off");
    }

    private static TankFaction nextFaction(TankFaction current) {
        TankFaction[] values = TankFaction.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private int listSize() {
        return this.category == SpawnProbeCategory.VEHICLE
                ? this.vehicleList.size() : this.infantryList.size();
    }

    private List<String> activeCatalog() {
        return this.category == SpawnProbeCategory.VEHICLE ? this.vehicleCatalog : this.infantryCatalog;
    }

    private boolean listContainsId(String id) {
        if (this.category == SpawnProbeCategory.VEHICLE) {
            return this.vehicleList.contains(id);
        }
        for (SpawnProbeInfantryEntry e : this.infantryList) {
            if (e.id().equals(id)) return true;
        }
        return false;
    }

    private void refreshFilter() {
        String q = this.filterBox != null ? this.filterBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<String> out = new ArrayList<>();
        for (String id : activeCatalog()) {
            if (listContainsId(id)) continue;
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
            if (id == null && ResourceLocation.tryParse(typed) != null
                    && (this.category == SpawnProbeCategory.VEHICLE
                    || this.filteredCatalog.contains(typed)
                    || this.infantryCatalog.contains(typed))) {
                id = typed;
            }
        }
        if (id == null) id = this.filteredCatalog.isEmpty() ? null : this.filteredCatalog.get(0);
        if (id == null) return;

        if (this.category == SpawnProbeCategory.VEHICLE) {
            if (!this.vehicleList.contains(id)) this.vehicleList.add(id);
            this.selected = this.vehicleList.indexOf(id);
        } else {
            if (listContainsId(id)) return;
            this.infantryList.add(new SpawnProbeInfantryEntry(
                    id, SpawnProbeInfantryEntry.MIN_COUNT, SpawnProbeInfantryEntry.DEFAULT_CHANCE));
            this.selected = this.infantryList.size() - 1;
            syncInfantryEditors();
        }
        if (this.selected >= this.scroll + this.listRows) {
            this.scroll = this.selected - this.listRows + 1;
        }
        refreshFilter();
    }

    private void removeSelected() {
        if (this.selected < 0 || this.selected >= listSize()) return;
        if (this.category == SpawnProbeCategory.VEHICLE) {
            this.vehicleList.remove(this.selected);
        } else {
            this.infantryList.remove(this.selected);
            syncInfantryEditors();
        }
        if (this.selected >= listSize()) this.selected = listSize() - 1;
        refreshFilter();
    }

    private void adjustQty(int delta) {
        if (this.category != SpawnProbeCategory.INFANTRY) return;
        if (this.selected < 0 || this.selected >= this.infantryList.size()) return;
        SpawnProbeInfantryEntry cur = this.infantryList.get(this.selected);
        this.infantryList.set(this.selected, cur.withCount(cur.count() + delta));
    }

    private void syncInfantryEditors() {
        boolean has = this.category == SpawnProbeCategory.INFANTRY
                && this.selected >= 0 && this.selected < this.infantryList.size();
        if (this.qtyMinusButton != null) this.qtyMinusButton.active = has;
        if (this.qtyPlusButton != null) this.qtyPlusButton.active = has;
        if (this.chanceSlider != null) {
            this.chanceSlider.active = has;
            if (has) {
                this.chanceSlider.setChance(this.infantryList.get(this.selected).chance());
            } else {
                this.chanceSlider.setChance(SpawnProbeInfantryEntry.DEFAULT_CHANCE);
            }
        }
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveSpawnProbe(
                this.pos, this.category, this.factionType,
                this.vehicleList, this.preCrewedSpawn, this.infantryList));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (mouseX >= this.panelLeft && mouseX < this.panelLeft + this.panelW - 20
                    && mouseY >= this.listTop && mouseY < this.listTop + this.listRows * LIST_ROW_H) {
                int row = (int) ((mouseY - this.listTop) / LIST_ROW_H);
                int idx = this.scroll + row;
                if (idx >= 0 && idx < listSize()) {
                    this.selected = idx;
                    syncInfantryEditors();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + this.listRows < listSize()) this.scroll++;
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = this.panelLeft;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        if (this.category == SpawnProbeCategory.VEHICLE) {
            graphics.drawString(this.font,
                    Component.translatable("gui.tacz_sewv.spawn_probe.vehicle_list", this.vehicleList.size()),
                    left, this.listTop - 12, 0xA0A0A0, false);
            for (int i = 0; i < this.listRows; i++) {
                int idx = this.scroll + i;
                if (idx >= this.vehicleList.size()) break;
                int color = idx == this.selected ? 0xFFFFFF : 0xC0C0C0;
                graphics.drawString(this.font, this.vehicleList.get(idx), left + 4, this.listTop + i * LIST_ROW_H + 2,
                        color, false);
            }
            if (this.vehicleCatalog.isEmpty()) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("gui.tacz_sewv.spawn_probe.no_catalog"),
                        this.width / 2, this.height - 24, 0xFFAA00);
            }
        } else {
            graphics.drawString(this.font,
                    Component.translatable("gui.tacz_sewv.spawn_probe.infantry_list", this.infantryList.size()),
                    left, this.listTop - 12, 0xA0A0A0, false);
            for (int i = 0; i < this.listRows; i++) {
                int idx = this.scroll + i;
                if (idx >= this.infantryList.size()) break;
                SpawnProbeInfantryEntry entry = this.infantryList.get(idx);
                int color = idx == this.selected ? 0xFFFFFF : 0xC0C0C0;
                String line = entry.id() + "  x" + entry.count() + "  " + entry.chance() + "%";
                graphics.drawString(this.font, line, left + 4, this.listTop + i * LIST_ROW_H + 2, color, false);
            }
            if (this.selected >= 0 && this.selected < this.infantryList.size()) {
                int qty = this.infantryList.get(this.selected).count();
                graphics.drawCenteredString(this.font, String.valueOf(qty),
                        left + 46, this.editStripY + 6, 0xFFFFFF);
            } else {
                graphics.drawString(this.font,
                        Component.translatable("gui.tacz_sewv.spawn_probe.infantry_select"),
                        left + 24, this.editStripY + 6, 0x808080, false);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class ChanceSlider extends AbstractSliderButton {
        private ChanceSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 1.0D);
            updateMessage();
        }

        void setChance(int chance) {
            this.value = Mth.clamp(chance, SpawnProbeInfantryEntry.MIN_CHANCE,
                    SpawnProbeInfantryEntry.MAX_CHANCE) / 100.0D;
            updateMessage();
        }

        private int chance() {
            return Mth.clamp((int) Math.round(this.value * 100.0D),
                    SpawnProbeInfantryEntry.MIN_CHANCE, SpawnProbeInfantryEntry.MAX_CHANCE);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.tacz_sewv.spawn_probe.chance", chance()));
        }

        @Override
        protected void applyValue() {
            if (SpawnProbeScreen.this.selected < 0
                    || SpawnProbeScreen.this.selected >= SpawnProbeScreen.this.infantryList.size()) {
                return;
            }
            SpawnProbeInfantryEntry cur = SpawnProbeScreen.this.infantryList.get(SpawnProbeScreen.this.selected);
            SpawnProbeScreen.this.infantryList.set(SpawnProbeScreen.this.selected, cur.withChance(chance()));
        }
    }
}
