package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateVehicleClasses;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Admin UI for vehicle-class cues and faction armor loadouts ({@code /sewv pool misc}).
 */
public class MiscEditorScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int LIST_ROWS = 10;

    private enum Tab {
        IFV, ANTI_AIR, MISSILE_SYSTEM, ARTILLERY,
        PLANE_MISSILE, PLANE_BOMB, PLANE_ROCKET,
        ARMOR_RU, ARMOR_US, ARMOR_PMC
    }

    private final Map<CueKind, List<String>> cues;
    private final Map<CueKind, List<String>> cueDefaults;
    private final Map<TankFaction, List<String>> armor;
    private final Map<TankFaction, List<String>> armorDefaults;
    private final List<String> armorCatalog;

    private Tab tab = Tab.IFV;
    private int scroll;
    private int selected = -1;
    private EditBox filterBox;
    private List<String> filteredCatalog = List.of();

    public MiscEditorScreen(Map<CueKind, List<String>> cues,
                            Map<CueKind, List<String>> cueDefaults,
                            Map<TankFaction, List<String>> armor,
                            Map<TankFaction, List<String>> armorDefaults,
                            List<String> armorCatalog) {
        super(Component.translatable("gui.tacz_sewv.misc.title"));
        this.cues = deepCues(cues);
        this.cueDefaults = deepCues(cueDefaults);
        this.armor = deepArmor(armor);
        this.armorDefaults = deepArmor(armorDefaults);
        this.armorCatalog = List.copyOf(armorCatalog);
    }

    private static Map<CueKind, List<String>> deepCues(Map<CueKind, List<String>> src) {
        Map<CueKind, List<String>> out = new EnumMap<>(CueKind.class);
        for (CueKind k : CueKind.values()) out.put(k, new ArrayList<>(src.get(k)));
        return out;
    }

    private static Map<TankFaction, List<String>> deepArmor(Map<TankFaction, List<String>> src) {
        Map<TankFaction, List<String>> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) out.put(f, new ArrayList<>(src.get(f)));
        return out;
    }

    private List<String> currentList() {
        return switch (this.tab) {
            case IFV -> this.cues.get(CueKind.IFV);
            case ANTI_AIR -> this.cues.get(CueKind.ANTI_AIR);
            case MISSILE_SYSTEM -> this.cues.get(CueKind.MISSILE_SYSTEM);
            case ARTILLERY -> this.cues.get(CueKind.ARTILLERY);
            case PLANE_MISSILE -> this.cues.get(CueKind.PLANE_MISSILE);
            case PLANE_BOMB -> this.cues.get(CueKind.PLANE_BOMB);
            case PLANE_ROCKET -> this.cues.get(CueKind.PLANE_ROCKET);
            case ARMOR_RU -> this.armor.get(TankFaction.RU);
            case ARMOR_US -> this.armor.get(TankFaction.US);
            case ARMOR_PMC -> this.armor.get(TankFaction.PMC);
        };
    }

    private boolean isArmorTab() {
        return this.tab == Tab.ARMOR_RU || this.tab == Tab.ARMOR_US || this.tab == Tab.ARMOR_PMC;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int top = 28;
        int x = left;
        int y = top;
        int col = 0;
        for (Tab t : Tab.values()) {
            final Tab tt = t;
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.tacz_sewv.misc.tab." + t.name().toLowerCase(Locale.ROOT)),
                    b -> {
                        this.tab = tt;
                        this.scroll = 0;
                        this.selected = -1;
                        refreshFilter();
                    }).bounds(x, y, 68, 18).build());
            col++;
            x += 72;
            if (col >= 5) {
                col = 0;
                x = left;
                y += 20;
            }
        }

        int listTop = y + 24;
        int listBottom = listTop + LIST_ROWS * 12;

        this.filterBox = new EditBox(this.font, left, listBottom + 8, PANEL_W - 90, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setResponder(s -> refreshFilter());
        addRenderableWidget(this.filterBox);
        refreshFilter();

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(left + PANEL_W - 84, listBottom + 8, 84, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(left, listBottom + 32, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.reset"), b -> resetCurrent())
                .bounds(left + 108, listBottom + 32, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.pool.save"), b -> {
            NetworkHandler.CHANNEL.sendToServer(new PacketUpdateVehicleClasses(this.cues, this.armor));
            onClose();
        }).bounds(left + PANEL_W - 100, listBottom + 32, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            if (this.scroll > 0) this.scroll--;
        }).bounds(left + PANEL_W - 20, listTop, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            if (this.scroll + LIST_ROWS < currentList().size()) this.scroll++;
        }).bounds(left + PANEL_W - 20, listBottom - 20, 20, 20).build());
    }

    private void refreshFilter() {
        String q = this.filterBox != null ? this.filterBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<String> pool = currentList();
        List<String> out = new ArrayList<>();
        if (isArmorTab()) {
            for (String id : this.armorCatalog) {
                if (pool.contains(id)) continue;
                if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
                out.add(id);
            }
        }
        this.filteredCatalog = out;
    }

    private void addFromFilter() {
        refreshFilter();
        String typed = this.filterBox != null ? this.filterBox.getValue().trim() : "";
        String id = resolveAddId(typed);
        if (id == null) return;
        List<String> pool = currentList();
        if (!pool.contains(id)) pool.add(id);
        this.selected = pool.indexOf(id);
        if (this.selected >= this.scroll + LIST_ROWS) this.scroll = Math.max(0, this.selected - LIST_ROWS + 1);
        refreshFilter();
    }

    private String resolveAddId(String typed) {
        if (!typed.isEmpty()) {
            if (isArmorTab()) {
                for (String id : this.filteredCatalog) {
                    if (id.equalsIgnoreCase(typed)) return id;
                }
                if (ResourceLocation.tryParse(typed) != null) return typed;
            } else {
                // Cue tabs: free-text substrings (not necessarily resource locations).
                return typed;
            }
        }
        return this.filteredCatalog.isEmpty() ? null : this.filteredCatalog.get(0);
    }

    private void removeSelected() {
        List<String> pool = currentList();
        if (this.selected < 0 || this.selected >= pool.size()) return;
        pool.remove(this.selected);
        this.selected = -1;
        refreshFilter();
    }

    private void resetCurrent() {
        switch (this.tab) {
            case IFV -> this.cues.put(CueKind.IFV, new ArrayList<>(this.cueDefaults.get(CueKind.IFV)));
            case ANTI_AIR -> this.cues.put(CueKind.ANTI_AIR, new ArrayList<>(this.cueDefaults.get(CueKind.ANTI_AIR)));
            case MISSILE_SYSTEM -> this.cues.put(CueKind.MISSILE_SYSTEM, new ArrayList<>(this.cueDefaults.get(CueKind.MISSILE_SYSTEM)));
            case ARTILLERY -> this.cues.put(CueKind.ARTILLERY, new ArrayList<>(this.cueDefaults.get(CueKind.ARTILLERY)));
            case PLANE_MISSILE -> this.cues.put(CueKind.PLANE_MISSILE, new ArrayList<>(this.cueDefaults.get(CueKind.PLANE_MISSILE)));
            case PLANE_BOMB -> this.cues.put(CueKind.PLANE_BOMB, new ArrayList<>(this.cueDefaults.get(CueKind.PLANE_BOMB)));
            case PLANE_ROCKET -> this.cues.put(CueKind.PLANE_ROCKET, new ArrayList<>(this.cueDefaults.get(CueKind.PLANE_ROCKET)));
            case ARMOR_RU -> this.armor.put(TankFaction.RU, new ArrayList<>(this.armorDefaults.get(TankFaction.RU)));
            case ARMOR_US -> this.armor.put(TankFaction.US, new ArrayList<>(this.armorDefaults.get(TankFaction.US)));
            case ARMOR_PMC -> this.armor.put(TankFaction.PMC, new ArrayList<>(this.armorDefaults.get(TankFaction.PMC)));
        }
        this.selected = -1;
        this.scroll = 0;
        refreshFilter();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int left = (this.width - PANEL_W) / 2;
        int listTop = 28 + 40 + 24;
        g.drawString(this.font, this.title, left, 12, 0xFFFFFF, false);

        List<String> pool = currentList();
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = this.scroll + i;
            if (idx >= pool.size()) break;
            int y = listTop + i * 12;
            int color = idx == this.selected ? 0xFFFFA0 : 0xE0E0E0;
            g.drawString(this.font, pool.get(idx), left, y, color, false);
        }

        String hint = isArmorTab()
                ? Component.translatable("gui.tacz_sewv.misc.hint.armor").getString()
                : Component.translatable("gui.tacz_sewv.misc.hint.cue").getString();
        g.drawString(this.font, hint, left, this.height - 24, 0xA0A0A0, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - PANEL_W) / 2;
        int listTop = 28 + 40 + 24;
        if (mouseX >= left && mouseX < left + PANEL_W - 24
                && mouseY >= listTop && mouseY < listTop + LIST_ROWS * 12) {
            int row = (int) ((mouseY - listTop) / 12);
            int idx = this.scroll + row;
            if (idx >= 0 && idx < currentList().size()) this.selected = idx;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
