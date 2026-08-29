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

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveSpawnProbe;

/**
 * Op config UI for a spawn_probe. Snapshot edited locally; Apply pushes {@link PacketSaveSpawnProbe}.
 */
public class SpawnProbeScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int LIST_ROWS = 8;

    private final BlockPos pos;
    private final List<String> vehicleList;
    private final List<String> catalog;
    private boolean preCrewedSpawn;

    private EditBox filterBox;
    private Button preCrewedButton;
    private List<String> filteredCatalog = List.of();
    private int selected = -1;
    private int scroll;
    private int listTop;

    public SpawnProbeScreen(BlockPos pos, List<String> vehicleList, boolean preCrewedSpawn,
                            List<String> catalog) {
        super(Component.translatable("gui.tacz_sewv.spawn_probe.title"));
        this.pos = pos;
        this.vehicleList = new ArrayList<>(vehicleList);
        this.preCrewedSpawn = preCrewedSpawn;
        this.catalog = List.copyOf(catalog);
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int y = 40;

        this.preCrewedButton = addRenderableWidget(Button.builder(preCrewedLabel(), b -> {
            this.preCrewedSpawn = !this.preCrewedSpawn;
            this.preCrewedButton.setMessage(preCrewedLabel());
        }).bounds(left, y, PANEL_W, 20).build());
        y += 28;

        this.listTop = y + 12;
        int listBottom = this.listTop + LIST_ROWS * 12;

        this.filterBox = new EditBox(this.font, left, listBottom + 4, PANEL_W - 84, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        addRenderableWidget(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + PANEL_W - 80, listBottom + 4, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + PANEL_W - 20, this.listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.scroll + LIST_ROWS < this.vehicleList.size()) this.scroll++;
        }).bounds(left + PANEL_W - 20, listBottom - 20, 20, 20).build());

        int applyY = listBottom + 52;
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.spawn_probe.apply"), b -> apply())
                .bounds(left, applyY, PANEL_W / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 4, applyY, PANEL_W / 2 - 4, 20).build());
    }

    private Component preCrewedLabel() {
        return Component.translatable(this.preCrewedSpawn
                ? "gui.tacz_sewv.spawn_probe.pre_crewed.on"
                : "gui.tacz_sewv.spawn_probe.pre_crewed.off");
    }

    private void refreshFilter() {
        String q = this.filterBox != null ? this.filterBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<String> out = new ArrayList<>();
        for (String id : this.catalog) {
            if (this.vehicleList.contains(id)) continue;
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
        if (!this.vehicleList.contains(id)) this.vehicleList.add(id);
        this.selected = this.vehicleList.indexOf(id);
        if (this.selected >= this.scroll + LIST_ROWS) {
            this.scroll = this.selected - LIST_ROWS + 1;
        }
        refreshFilter();
    }

    private void removeSelected() {
        if (this.selected < 0 || this.selected >= this.vehicleList.size()) return;
        this.vehicleList.remove(this.selected);
        if (this.selected >= this.vehicleList.size()) this.selected = this.vehicleList.size() - 1;
        refreshFilter();
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new PacketSaveSpawnProbe(
                this.pos, this.vehicleList, this.preCrewedSpawn));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = (this.width - PANEL_W) / 2;
            if (mouseX >= left && mouseX < left + PANEL_W - 20
                    && mouseY >= this.listTop && mouseY < this.listTop + LIST_ROWS * 12) {
                int row = (int) ((mouseY - this.listTop) / 12);
                int idx = this.scroll + row;
                if (idx >= 0 && idx < this.vehicleList.size()) {
                    this.selected = idx;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0 && this.scroll > 0) this.scroll--;
        else if (delta < 0 && this.scroll + LIST_ROWS < this.vehicleList.size()) this.scroll++;
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - PANEL_W) / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("gui.tacz_sewv.spawn_probe.vehicle_list", this.vehicleList.size()),
                left, this.listTop - 12, 0xA0A0A0, false);
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = this.scroll + i;
            if (idx >= this.vehicleList.size()) break;
            int color = idx == this.selected ? 0xFFFFFF : 0xC0C0C0;
            graphics.drawString(this.font, this.vehicleList.get(idx), left + 4, this.listTop + i * 12 + 2,
                    color, false);
        }
        if (this.catalog.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tacz_sewv.spawn_probe.no_catalog"),
                    this.width / 2, this.height - 24, 0xFFAA00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
