package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.client.editor.IdSearch;
import com.neoalive.tacz_sewv.client.editor.VehiclePoolCatalog;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateVehicleClasses;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Admin UI for vehicle-class cues and faction armor ({@code /sewv pool misc}), laid out like the
 * loadout manager. Three kinds of sheet, because the three kinds of entry mean different things:
 *
 * <ul>
 *   <li><b>Vehicle clues</b> (IFV, anti-air, missile system, artillery) are substrings tested against
 *       a hull's <i>full registry id</i>, so the sheet counts what each clue matches among the loaded
 *       vehicles and the add box previews the match live.</li>
 *   <li><b>Plane weapon clues</b> are tested against a weapon slot's name AND its ammo item id, so a
 *       match count against item ids is only a hint and zero is normal.</li>
 *   <li><b>Armor</b> ids are each worn in the slot the item itself declares, and only if that slot is
 *       still empty, so a second helmet is never applied; medics and engineers wear the helmet only.</li>
 * </ul>
 */
public class MiscEditorScreen extends Screen {

    private static final int PANEL_W_PREF = 460;
    private static final int CAND_LINES = 3;
    private static final int CAND_H = 10;
    private static final int BELOW_GRID = 10 + 20 + 4 + CAND_LINES * CAND_H + 4 + 20 + 6;
    private static final int EXAMPLES = 2;
    private static final int TIP_EXAMPLES = 5;

    private enum Tab {
        IFV, ANTI_AIR, MISSILE_SYSTEM, ARTILLERY,
        PLANE_MISSILE, PLANE_BOMB, PLANE_ROCKET, PLANE_CANNON,
        ARMOR_RU, ARMOR_US, ARMOR_PMC
    }

    private enum Kind { VEHICLE_CLUE, PLANE_CLUE, ARMOR }

    private record Match(int count, List<String> examples) {}

    private final Map<CueKind, List<String>> cues;
    private final Map<CueKind, List<String>> cueDefaults;
    private final Map<TankFaction, List<String>> armor;
    private final Map<TankFaction, List<String>> armorDefaults;
    private final List<String> armorCatalog;

    private Tab tab = Tab.IFV;
    private final TabStrip clueStrip;
    private final TabStrip armorStrip;
    private final Map<Kind, SheetTable> tables = new EnumMap<>(Kind.class);
    private final Map<String, Match> matches = new HashMap<>();
    private final Map<String, String> armorNames = new HashMap<>();

    private List<String> vehicleIds;
    private List<String> vehicleIdsLower;
    private List<String> itemIds;
    private List<String> itemIdsLower;

    private PoolVehicleIdEditBox filterBox;
    private List<String> candidates = List.of();

    private int left;
    private int panelW;
    private int summaryY;
    private int labelY;
    private int candTop;

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

        this.clueStrip = new TabStrip(100, i -> pick(Tab.values()[i]));
        for (Tab t : Tab.values()) {
            if (kindOf(t) == Kind.ARMOR) continue;
            this.clueStrip.add(tabLabel(t), Component.translatable("gui.tacz_sewv.misc.tip.tab." + key(t)));
        }
        this.armorStrip = new TabStrip(100, i -> pick(Tab.values()[8 + i]));
        for (Tab t : Tab.values()) {
            if (kindOf(t) != Kind.ARMOR) continue;
            this.armorStrip.add(tabLabel(t), Component.translatable("gui.tacz_sewv.misc.tip.tab." + key(t)));
        }

        this.tables.put(Kind.VEHICLE_CLUE, new SheetTable(List.of(
                col("misc", "n", 24, true), col("misc", "clue", -2, false),
                col("misc", "matches", 46, true), col("misc", "examples", -3, false)),
                new ClueModel(true), Component.translatable("gui.tacz_sewv.misc.tip.col.st.clue")));
        this.tables.put(Kind.PLANE_CLUE, new SheetTable(List.of(
                col("misc", "n", 24, true), col("misc", "clue", -2, false),
                col("misc", "items", 46, true), col("misc", "examples", -3, false)),
                new ClueModel(false), Component.translatable("gui.tacz_sewv.misc.tip.col.st.plane")));
        this.tables.put(Kind.ARMOR, new SheetTable(List.of(
                col("misc", "n", 24, true), col("misc", "id", -3, false), col("misc", "name", -2, false),
                col("misc", "slot", 44, false), col("misc", "crew", 40, false)),
                new ArmorModel(), Component.translatable("gui.tacz_sewv.misc.tip.col.st.armor")));
    }

    private static SheetTable.Col col(String screen, String key, int width, boolean numeric) {
        return new SheetTable.Col(key, width, numeric, Component.translatable("gui.tacz_sewv." + screen + ".col." + key),
                Component.translatable("gui.tacz_sewv." + screen + ".tip.col." + key));
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

    private static String key(Tab t) {
        return t.name().toLowerCase(Locale.ROOT);
    }

    private static Component tabLabel(Tab t) {
        return Component.translatable("gui.tacz_sewv.misc.tab." + key(t));
    }

    private static Kind kindOf(Tab t) {
        return switch (t) {
            case IFV, ANTI_AIR, MISSILE_SYSTEM, ARTILLERY -> Kind.VEHICLE_CLUE;
            case PLANE_MISSILE, PLANE_BOMB, PLANE_ROCKET, PLANE_CANNON -> Kind.PLANE_CLUE;
            case ARMOR_RU, ARMOR_US, ARMOR_PMC -> Kind.ARMOR;
        };
    }

    private Kind kind() {
        return kindOf(this.tab);
    }

    private SheetTable table() {
        return this.tables.get(kind());
    }

    private boolean isArmorTab() {
        return kind() == Kind.ARMOR;
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
            case PLANE_CANNON -> this.cues.get(CueKind.PLANE_CANNON);
            case ARMOR_RU -> this.armor.get(TankFaction.RU);
            case ARMOR_US -> this.armor.get(TankFaction.US);
            case ARMOR_PMC -> this.armor.get(TankFaction.PMC);
        };
    }

    private void pick(Tab t) {
        this.tab = t;
        this.tables.values().forEach(SheetTable::reset);
        if (this.filterBox != null) this.filterBox.setValue("");
        syncStrips();
        refreshCandidates();
    }

    private void syncStrips() {
        this.clueStrip.select(isArmorTab() ? -1 : this.tab.ordinal());
        this.armorStrip.select(isArmorTab() ? this.tab.ordinal() - 8 : -1);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        VehiclePoolCatalog.ensureLoaded();
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.left = (this.width - this.panelW) / 2;
        ensureIdLists();

        syncStrips();
        int y = this.clueStrip.layout(this::addRenderableWidget, this.left, 22, this.panelW);
        y = this.armorStrip.layout(this::addRenderableWidget, this.left, y, Math.min(this.panelW, 3 * 112));

        this.summaryY = y + 2;
        int headY = this.summaryY + 12;
        int rows = SheetTable.rowsFor(this.height, headY + SheetTable.HEAD_H, BELOW_GRID, 4, 14);
        this.tables.values().forEach(t -> t.layout(this.left, headY, this.panelW, rows));

        SheetTable any = table();
        this.labelY = any.bottom() + 4;
        int addY = this.labelY + 10;
        this.candTop = addY + 24;
        int buttonsY = this.candTop + CAND_LINES * CAND_H + 4;

        String previous = this.filterBox == null ? "" : this.filterBox.getValue();
        this.filterBox = new PoolVehicleIdEditBox(this.font, this.left, addY, this.panelW - 88, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.misc.tip.filter")));
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
            NetworkHandler.CHANNEL.sendToServer(new PacketUpdateVehicleClasses(this.cues, this.armor));
            onClose();
        }).bounds(this.left + 2 * (bw + 4), buttonsY, this.panelW - 2 * (bw + 4), 20).build(), "save"));
        refreshCandidates();
    }

    private static Button tip(Button b, String key) {
        b.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.misc.tip." + key)));
        return b;
    }

    /** Lower-cased once: a clue is tested against these on every frame it is visible. */
    private void ensureIdLists() {
        if (this.vehicleIds == null || this.vehicleIds.isEmpty()) {
            this.vehicleIds = VehiclePoolCatalog.ids();
            this.vehicleIdsLower = this.vehicleIds.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
            this.matches.clear();
        }
        if (this.itemIds == null) {
            List<String> ids = new ArrayList<>();
            for (ResourceLocation rl : ForgeRegistries.ITEMS.getKeys()) ids.add(rl.toString());
            ids.sort(String::compareTo);
            this.itemIds = ids;
            this.itemIdsLower = ids.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }
    }

    // ---------------------------------------------------------------- matching

    /** How many ids contain the clue (case-insensitive), with the first few path-only examples. */
    private Match matchOf(String clue, boolean vehicles) {
        String c = clue.trim().toLowerCase(Locale.ROOT);
        return this.matches.computeIfAbsent((vehicles ? "v|" : "i|") + c, k -> {
            List<String> lower = vehicles ? this.vehicleIdsLower : this.itemIdsLower;
            List<String> orig = vehicles ? this.vehicleIds : this.itemIds;
            List<String> ex = new ArrayList<>();
            int count = 0;
            if (!c.isEmpty()) {
                for (int i = 0; i < lower.size(); i++) {
                    if (!lower.get(i).contains(c)) continue;
                    count++;
                    if (ex.size() < TIP_EXAMPLES) ex.add(orig.get(i));
                }
            }
            return new Match(count, ex);
        });
    }

    private static String shortId(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    @Nullable
    private static EquipmentSlot slotOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl != null && ForgeRegistries.ITEMS.containsKey(rl) ? ForgeRegistries.ITEMS.getValue(rl) : null;
        return item instanceof ArmorItem a ? a.getEquipmentSlot() : null;
    }

    private String armorName(String id) {
        return this.armorNames.computeIfAbsent(id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            Item item = rl != null && ForgeRegistries.ITEMS.containsKey(rl) ? ForgeRegistries.ITEMS.getValue(rl) : null;
            return item == null ? "?" : item.getDescription().getString();
        });
    }

    private static String slotLabel(@Nullable EquipmentSlot s) {
        if (s == null) return "-";
        return switch (s) {
            case HEAD -> "Head";
            case CHEST -> "Chest";
            case LEGS -> "Legs";
            case FEET -> "Feet";
            default -> "-";
        };
    }

    // ---------------------------------------------------------------- add / edit

    /** Clue tabs preview what the typed clue would match; armor tabs autofill from the armor catalog. */
    private void refreshCandidates() {
        if (this.filterBox == null) return;
        String typed = this.filterBox.getValue();
        if (isArmorTab()) {
            List<String> pool = currentList();
            List<String> free = new ArrayList<>();
            for (String id : this.armorCatalog) {
                if (!pool.contains(id)) free.add(id);
            }
            this.candidates = IdSearch.match(typed, free, this::armorName, CAND_LINES);
        } else if (typed.isBlank()) {
            this.candidates = List.of();
        } else {
            this.candidates = matchOf(typed, kind() == Kind.VEHICLE_CLUE).examples().stream()
                    .limit(CAND_LINES).toList();
        }
    }

    private boolean applyTabCompletion() {
        if (!isArmorTab() || this.candidates.isEmpty()) return false;
        String id = this.candidates.get(0);
        this.filterBox.setValue(id);
        this.filterBox.setCursorPosition(id.length());
        return true;
    }

    private void addFromFilter() {
        String typed = this.filterBox.getValue().trim();
        String entry;
        if (isArmorTab()) {
            entry = resolveArmorId(typed);
        } else {
            entry = typed.isEmpty() ? null : typed;
        }
        if (entry == null) return;
        List<String> pool = currentList();
        int existing = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).equalsIgnoreCase(entry)) existing = i;
        }
        if (existing < 0) {
            pool.add(entry);
            existing = pool.size() - 1;
        }
        table().select(existing);
        this.filterBox.setValue("");
        refreshCandidates();
    }

    @Nullable
    private String resolveArmorId(String typed) {
        if (!typed.isEmpty()) {
            for (String id : this.armorCatalog) {
                if (id.equalsIgnoreCase(typed)) return id;
            }
            if (ResourceLocation.tryParse(typed) != null && typed.contains(":")) return typed;
        }
        return this.candidates.isEmpty() ? null : this.candidates.get(0);
    }

    private void removeSelected() {
        List<String> pool = currentList();
        int sel = table().selected();
        if (sel < 0 || sel >= pool.size()) return;
        pool.remove(sel);
        table().clampScroll();
        refreshCandidates();
    }

    private void resetCurrent() {
        switch (this.tab) {
            case IFV -> resetCue(CueKind.IFV);
            case ANTI_AIR -> resetCue(CueKind.ANTI_AIR);
            case MISSILE_SYSTEM -> resetCue(CueKind.MISSILE_SYSTEM);
            case ARTILLERY -> resetCue(CueKind.ARTILLERY);
            case PLANE_MISSILE -> resetCue(CueKind.PLANE_MISSILE);
            case PLANE_BOMB -> resetCue(CueKind.PLANE_BOMB);
            case PLANE_ROCKET -> resetCue(CueKind.PLANE_ROCKET);
            case PLANE_CANNON -> resetCue(CueKind.PLANE_CANNON);
            case ARMOR_RU -> this.armor.put(TankFaction.RU, new ArrayList<>(this.armorDefaults.get(TankFaction.RU)));
            case ARMOR_US -> this.armor.put(TankFaction.US, new ArrayList<>(this.armorDefaults.get(TankFaction.US)));
            case ARMOR_PMC -> this.armor.put(TankFaction.PMC, new ArrayList<>(this.armorDefaults.get(TankFaction.PMC)));
        }
        table().reset();
        refreshCandidates();
    }

    private void resetCue(CueKind kind) {
        this.cues.put(kind, new ArrayList<>(this.cueDefaults.get(kind)));
    }

    private boolean isDefault(String entry) {
        List<String> defaults = switch (this.tab) {
            case IFV -> this.cueDefaults.get(CueKind.IFV);
            case ANTI_AIR -> this.cueDefaults.get(CueKind.ANTI_AIR);
            case MISSILE_SYSTEM -> this.cueDefaults.get(CueKind.MISSILE_SYSTEM);
            case ARTILLERY -> this.cueDefaults.get(CueKind.ARTILLERY);
            case PLANE_MISSILE -> this.cueDefaults.get(CueKind.PLANE_MISSILE);
            case PLANE_BOMB -> this.cueDefaults.get(CueKind.PLANE_BOMB);
            case PLANE_ROCKET -> this.cueDefaults.get(CueKind.PLANE_ROCKET);
            case PLANE_CANNON -> this.cueDefaults.get(CueKind.PLANE_CANNON);
            case ARMOR_RU -> this.armorDefaults.get(TankFaction.RU);
            case ARMOR_US -> this.armorDefaults.get(TankFaction.US);
            case ARMOR_PMC -> this.armorDefaults.get(TankFaction.PMC);
        };
        return defaults.stream().anyMatch(d -> d.equalsIgnoreCase(entry));
    }

    // ---------------------------------------------------------------- sheet models

    /** Vehicle clues (matched against loaded hull ids) and plane clues (matched against item ids). */
    private final class ClueModel implements SheetTable.Model {

        private final boolean vehicles;

        ClueModel(boolean vehicles) {
            this.vehicles = vehicles;
        }

        @Override
        public int size() {
            return currentList().size();
        }

        @Override
        public String text(int row, int col) {
            String clue = currentList().get(row);
            Match m = matchOf(clue, this.vehicles);
            return switch (col) {
                case 0 -> Integer.toString(row + 1);
                case 1 -> clue;
                case 2 -> Integer.toString(m.count());
                case 3 -> m.examples().stream().limit(EXAMPLES).map(MiscEditorScreen::shortId)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                default -> "";
            };
        }

        @Override
        public int state(int row) {
            String clue = currentList().get(row);
            if (clue.isBlank()) return SheetTable.BAD;
            // Plane clues also match weapon slot names, which no registry lists: zero proves nothing.
            return this.vehicles && matchOf(clue, true).count() == 0 ? SheetTable.WARN : SheetTable.LIVE;
        }

        @Override
        public List<Component> tip(int row) {
            String clue = currentList().get(row);
            Match m = matchOf(clue, this.vehicles);
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(clue).withStyle(ChatFormatting.YELLOW));
            out.add(Component.translatable(this.vehicles
                    ? "gui.tacz_sewv.misc.tip.row.vehicles" : "gui.tacz_sewv.misc.tip.row.items", m.count()));
            if (!m.examples().isEmpty()) {
                out.add(Component.literal(String.join(", ", m.examples())).withStyle(ChatFormatting.GRAY));
            }
            if (this.vehicles && m.count() == 0) out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.none"));
            if (!this.vehicles) out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.slotnames"));
            out.add(Component.translatable(isDefault(clue)
                    ? "gui.tacz_sewv.pool.tip.row.default" : "gui.tacz_sewv.pool.tip.row.custom"));
            return out;
        }
    }

    /**
     * Armor: state LIVE = applied, OFF = shadowed by an earlier piece in the same slot (never worn),
     * BAD = not a registered armor item.
     */
    private final class ArmorModel implements SheetTable.Model {

        @Override
        public int size() {
            return currentList().size();
        }

        /** Index of the earlier entry already filling this entry's slot, or -1. */
        private int shadowedBy(int row) {
            List<String> pool = currentList();
            EquipmentSlot mine = slotOf(pool.get(row));
            if (mine == null) return -1;
            for (int i = 0; i < row; i++) {
                if (slotOf(pool.get(i)) == mine) return i;
            }
            return -1;
        }

        @Override
        public String text(int row, int col) {
            String id = currentList().get(row);
            EquipmentSlot slot = slotOf(id);
            return switch (col) {
                case 0 -> Integer.toString(row + 1);
                case 1 -> id;
                case 2 -> armorName(id);
                case 3 -> slotLabel(slot);
                case 4 -> slot == EquipmentSlot.HEAD && shadowedBy(row) < 0 ? "yes" : "-";
                default -> "";
            };
        }

        @Override
        public int state(int row) {
            if (slotOf(currentList().get(row)) == null) return SheetTable.BAD;
            return shadowedBy(row) >= 0 ? SheetTable.OFF : SheetTable.LIVE;
        }

        @Override
        public List<Component> tip(int row) {
            String id = currentList().get(row);
            EquipmentSlot slot = slotOf(id);
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(armorName(id)).withStyle(ChatFormatting.YELLOW));
            out.add(Component.literal(id).withStyle(ChatFormatting.GRAY));
            if (slot == null) {
                out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.notarmor"));
            } else if (shadowedBy(row) >= 0) {
                out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.shadowed", slotLabel(slot),
                        shadowedBy(row) + 1));
            } else {
                out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.worn", slotLabel(slot)));
                if (slot == EquipmentSlot.HEAD) out.add(Component.translatable("gui.tacz_sewv.misc.tip.row.helmet"));
            }
            out.add(Component.translatable(isDefault(id)
                    ? "gui.tacz_sewv.pool.tip.row.default" : "gui.tacz_sewv.pool.tip.row.custom"));
            return out;
        }
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
        if (table().mouseClicked(mouseX, mouseY)) return true;
        if (isArmorTab() && mouseX >= this.left && mouseX < this.left + this.panelW
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
        return table().mouseDragged(mouseY) || super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.tables.values().forEach(SheetTable::mouseReleased);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        table().mouseScrolled(delta);
        return true;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.clueStrip.renderSelection(g);
        this.armorStrip.renderSelection(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        renderSummary(g);
        table().render(g, this.font, mouseX, mouseY);

        Component label = Component.translatable(isArmorTab() ? "gui.tacz_sewv.misc.lbl.add.armor"
                : "gui.tacz_sewv.misc.lbl.add.clue");
        g.drawString(this.font, label, this.left, this.labelY, 0xFF909090, false);
        if (!this.candidates.isEmpty()) {
            Component hint = Component.translatable(isArmorTab() ? "gui.tacz_sewv.pool.lbl.complete"
                    : "gui.tacz_sewv.misc.lbl.preview");
            g.drawString(this.font, hint, this.left + this.panelW - this.font.width(hint), this.labelY, 0xFF909090, false);
        }
        renderCandidates(g);

        List<Component> tip = table().hoverTip(mouseX, mouseY);
        if (tip != null) SheetTable.drawTip(g, this.font, tip, mouseX, mouseY);
    }

    private void renderSummary(GuiGraphics g) {
        List<String> pool = currentList();
        int good = 0;
        Component text;
        if (isArmorTab()) {
            for (int i = 0; i < pool.size(); i++) {
                if (armorApplies(i)) good++;
            }
            text = Component.translatable("gui.tacz_sewv.misc.summary.armor", tabLabel(this.tab), pool.size(), good);
        } else if (kind() == Kind.VEHICLE_CLUE) {
            for (String clue : pool) {
                if (matchOf(clue, true).count() > 0) good++;
            }
            text = Component.translatable("gui.tacz_sewv.misc.summary.clue", tabLabel(this.tab), pool.size(), good);
        } else {
            text = Component.translatable("gui.tacz_sewv.misc.summary.plane", tabLabel(this.tab), pool.size());
        }
        g.drawString(this.font, text, this.left, this.summaryY, 0xFFA0A0A0, false);
    }

    private boolean armorApplies(int row) {
        List<String> pool = currentList();
        EquipmentSlot mine = slotOf(pool.get(row));
        if (mine == null) return false;
        for (int i = 0; i < row; i++) {
            if (slotOf(pool.get(i)) == mine) return false;
        }
        return true;
    }

    private void renderCandidates(GuiGraphics g) {
        for (int i = 0; i < this.candidates.size(); i++) {
            String id = this.candidates.get(i);
            String line = isArmorTab() ? id + "  -  " + armorName(id) : id;
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.panelW), this.left + 2,
                    this.candTop + i * CAND_H, isArmorTab() && i == 0 ? 0xFFFFFF55 : 0xFFB0B0B0, false);
        }
        if (this.candidates.isEmpty() && !this.filterBox.getValue().isBlank()) {
            g.drawString(this.font, Component.translatable(isArmorTab()
                    ? "gui.tacz_sewv.pool.no_match" : "gui.tacz_sewv.misc.no_preview"),
                    this.left + 2, this.candTop, isArmorTab() ? 0xFFFF6666 : 0xFFFFC04D, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
