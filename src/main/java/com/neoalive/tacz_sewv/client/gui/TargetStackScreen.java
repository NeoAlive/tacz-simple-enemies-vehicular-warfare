package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.fml.ModList;

import com.neoalive.tacz_sewv.client.editor.IdSearch;
import com.neoalive.tacz_sewv.entity.ai.targeting.StackEntry;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateTargetStack;
import com.neoalive.tacz_sewv.platform.SewvScreen;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePoolCatalogSource;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/**
 * Target priority stack editor ({@code /sewv targetpriority}), in the pool editor's style. Per
 * faction an ordered Priority list, top first like a resource pack load order, and an Excluded
 * list. Rows can be entity ids, {@code @mod}, {@code cat:}, {@code #tag} or {@code group:}. Edits a
 * local copy; Save sends every faction.
 */
public class TargetStackScreen extends SewvScreen {

    private static final int PANEL_W_PREF = 460;
    private static final int CAND_LINES = 3;
    private static final int CAND_H = 10;
    private static final int BELOW_GRID = 10 + 20 + 4 + CAND_LINES * CAND_H + 4 + 20 + 4 + 20 + 6;
    private static final String[] COLS = {"n", "entry", "kind", "name"};

    private record Info(String kind, String name, int state, @Nullable Component note) {}

    private final Map<TankFaction, List<String>> priority = new EnumMap<>(TankFaction.class);
    private final Map<TankFaction, List<String>> excluded = new EnumMap<>(TankFaction.class);
    private final WorldTargetStack.Stack defaults;

    private TankFaction faction = TankFaction.RU;
    private boolean showExcluded;

    private final TabStrip factionStrip;
    private final TabStrip listStrip;
    private final SheetTable table;
    private final HoverTips hoverTips = new HoverTips();
    private final Map<String, Info> infos = new HashMap<>();
    private List<String> catalog = List.of();
    private List<String> candidates = List.of();
    @Nullable private Component note;

    private PoolVehicleIdEditBox filterBox;
    private Button moveButton;
    private int left;
    private int panelW;
    private int summaryY;
    private int labelY;
    private int candTop;

    public TargetStackScreen(Map<TankFaction, WorldTargetStack.Stack> stacks, WorldTargetStack.Stack defaults) {
        super(Component.translatable("gui.tacz_sewv.target_stack.title"));
        for (TankFaction f : TankFaction.values()) {
            WorldTargetStack.Stack s = stacks.get(f);
            this.priority.put(f, new ArrayList<>(s == null ? List.of() : s.priority()));
            this.excluded.put(f, new ArrayList<>(s == null ? List.of() : s.excluded()));
        }
        this.defaults = defaults;
        this.factionStrip = new TabStrip(50, i -> {
            this.faction = TankFaction.values()[i];
            switched();
        });
        for (TankFaction f : TankFaction.values()) {
            this.factionStrip.add(Component.literal(f.name()), Component.translatable("gui.tacz_sewv.pool.tip.faction"));
        }
        this.listStrip = new TabStrip(80, i -> {
            this.showExcluded = i == 1;
            switched();
        });
        this.listStrip.add(Component.translatable("gui.tacz_sewv.target_stack.list.priority"),
                Component.translatable("gui.tacz_sewv.target_stack.tip.list.priority"));
        this.listStrip.add(Component.translatable("gui.tacz_sewv.target_stack.list.excluded"),
                Component.translatable("gui.tacz_sewv.target_stack.tip.list.excluded"));
        List<SheetTable.Col> cols = new ArrayList<>();
        cols.add(col("n", 24, true));
        cols.add(col("entry", -3, false));
        cols.add(col("kind", 50, false));
        cols.add(col("name", -2, false));
        this.table = new SheetTable(cols, new Model(), Component.translatable("gui.tacz_sewv.target_stack.tip.col.st"));
    }

    private static SheetTable.Col col(String key, int width, boolean numeric) {
        return new SheetTable.Col(key, width, numeric, Component.translatable("gui.tacz_sewv.target_stack.col." + key),
                Component.translatable("gui.tacz_sewv.target_stack.tip.col." + key));
    }

    private List<String> rows() {
        return (this.showExcluded ? this.excluded : this.priority).get(this.faction);
    }

    private void switched() {
        this.table.reset();
        this.note = null;
        if (this.moveButton != null) this.moveButton.setMessage(moveLabel());
        refreshCandidates();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.hoverTips.clear();
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.left = (this.width - this.panelW) / 2;
        buildCatalog();

        this.factionStrip.select(this.faction.ordinal());
        this.listStrip.select(this.showExcluded ? 1 : 0);
        int y = this.factionStrip.layout(this::addRenderableWidget, this.left, 22, Math.min(176, this.panelW));
        y = this.listStrip.layout(this::addRenderableWidget, this.left, y, Math.min(240, this.panelW));

        this.summaryY = y + 2;
        int headY = this.summaryY + 12;
        int rows = SheetTable.rowsFor(this.height, headY + SheetTable.HEAD_H, BELOW_GRID, 4, 16);
        this.table.layout(this.left, headY, this.panelW, rows);

        this.labelY = this.table.bottom() + 4;
        int addY = this.labelY + 10;
        this.candTop = addY + 24;
        int orderY = this.candTop + CAND_LINES * CAND_H + 4;
        int buttonsY = orderY + 24;

        String previous = this.filterBox == null ? "" : this.filterBox.getValue();
        this.filterBox = new PoolVehicleIdEditBox(this.font, this.left, addY, this.panelW - 88, 20,
                Component.translatable("gui.tacz_sewv.pool.filter"));
        this.filterBox.setMaxLength(128);
        this.filterBox.setHint(Component.translatable("gui.tacz_sewv.target_stack.hint.search"));
        this.filterBox.setResponder(s -> refreshCandidates());
        this.filterBox.setTabCompleter(this::applyTabCompletion);
        addRenderableWidget(this.hoverTips.add(this.filterBox, Component.translatable("gui.tacz_sewv.target_stack.tip.filter")));
        setInitialFocus(this.filterBox);
        this.filterBox.setValue(previous);
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.add"), b -> addFromFilter())
                .bounds(this.left + this.panelW - 84, addY, 84, 20).build(), "add"));

        int ow = (this.panelW - 4 * 4) / 5;
        addRenderableWidget(tip(Button.builder(Component.literal("▲"), b -> move(-1))
                .bounds(this.left, orderY, ow, 20).build(), "up"));
        addRenderableWidget(tip(Button.builder(Component.literal("▼"), b -> move(1))
                .bounds(this.left + (ow + 4), orderY, ow, 20).build(), "down"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.target_stack.top"), b -> moveTo(true))
                .bounds(this.left + 2 * (ow + 4), orderY, ow, 20).build(), "top"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.target_stack.bottom"), b -> moveTo(false))
                .bounds(this.left + 3 * (ow + 4), orderY, ow, 20).build(), "bottom"));
        this.moveButton = tip(Button.builder(moveLabel(), b -> moveAcross())
                .bounds(this.left + 4 * (ow + 4), orderY, this.panelW - 4 * (ow + 4), 20).build(), "move");
        addRenderableWidget(this.moveButton);

        int bw = (this.panelW - 2 * 4) / 3;
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.remove"), b -> removeSelected())
                .bounds(this.left, buttonsY, bw, 20).build(), "remove"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.reset"), b -> resetCurrent())
                .bounds(this.left + bw + 4, buttonsY, bw, 20).build(), "reset"));
        addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.pool.save"), b -> save())
                .bounds(this.left + 2 * (bw + 4), buttonsY, this.panelW - 2 * (bw + 4), 20).build(), "save"));
        refreshCandidates();
    }

    private Component moveLabel() {
        return Component.translatable(this.showExcluded
                ? "gui.tacz_sewv.target_stack.to_priority" : "gui.tacz_sewv.target_stack.to_excluded");
    }

    private static Button tip(Button b, String key) {
        b.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.target_stack.tip." + key)));
        return b;
    }

    /** Groups, categories, mods, entity ids and entity tags, as typed rows. Built from the client's registries. */
    private void buildCatalog() {
        Set<String> out = new LinkedHashSet<>();
        for (StackEntry.Group g : StackEntry.Group.values()) out.add("group:" + g.key);
        for (MobCategory c : MobCategory.values()) {
            String name = c.getName();
            if (name != null && !name.isBlank()) out.add("cat:" + name.toLowerCase(Locale.ROOT));
        }
        List<String> mods = new ArrayList<>();
        ModList.get().forEachModFile(f -> f.getModInfos().forEach(m -> mods.add("@" + m.getModId())));
        Collections.sort(mods);
        out.addAll(mods);
        List<String> types = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) types.add(id.toString());
        Collections.sort(types);
        out.addAll(types);
        List<String> tags = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.getTagNames().forEach(t -> tags.add("#" + t.location()));
        Collections.sort(tags);
        out.addAll(tags);
        this.catalog = List.copyOf(out);
    }

    // ---------------------------------------------------------------- edits

    private void refreshCandidates() {
        if (this.filterBox == null) return;
        Set<String> taken = new LinkedHashSet<>(this.priority.get(this.faction));
        taken.addAll(this.excluded.get(this.faction));
        List<String> free = new ArrayList<>();
        for (String id : this.catalog) {
            if (!taken.contains(id)) free.add(id);
        }
        this.candidates = IdSearch.match(this.filterBox.getValue(), free, this::nameOf, CAND_LINES);
    }

    private boolean applyTabCompletion() {
        if (this.candidates.isEmpty()) return false;
        String id = this.candidates.get(0);
        this.filterBox.setValue(id);
        this.filterBox.setCursorPosition(id.length());
        return true;
    }

    private void addFromFilter() {
        String typed = this.filterBox.getValue().trim();
        StackEntry e = StackEntry.parse(typed);
        if (e == null && !this.candidates.isEmpty()) e = StackEntry.parse(this.candidates.get(0));
        if (e == null) return;
        if (!tryAdd(rows(), e)) return;
        this.filterBox.setValue("");
        refreshCandidates();
    }

    /** Adds {@code e} to {@code list} unless the faction already has it or Excluded may not hold it. */
    private boolean tryAdd(List<String> list, StackEntry e) {
        String text = e.text();
        if (this.priority.get(this.faction).contains(text) || this.excluded.get(this.faction).contains(text)) {
            this.note = Component.translatable("gui.tacz_sewv.target_stack.note.duplicate", text);
            return false;
        }
        if (list == this.excluded.get(this.faction) && !e.excludable(VehiclePoolCatalogSource::isVehicleId)) {
            this.note = Component.translatable("gui.tacz_sewv.target_stack.note.not_excludable", text);
            return false;
        }
        list.add(text);
        this.table.select(list.indexOf(text));
        this.note = null;
        return true;
    }

    private void move(int step) {
        List<String> list = rows();
        int sel = this.table.selected();
        int to = sel + step;
        if (sel < 0 || sel >= list.size() || to < 0 || to >= list.size()) return;
        Collections.swap(list, sel, to);
        this.table.select(to);
    }

    private void moveTo(boolean top) {
        List<String> list = rows();
        int sel = this.table.selected();
        if (sel < 0 || sel >= list.size()) return;
        String row = list.remove(sel);
        if (top) list.add(0, row);
        else list.add(row);
        this.table.select(top ? 0 : list.size() - 1);
    }

    private void moveAcross() {
        List<String> from = rows();
        int sel = this.table.selected();
        if (sel < 0 || sel >= from.size()) return;
        StackEntry e = StackEntry.parse(from.get(sel));
        List<String> to = (this.showExcluded ? this.priority : this.excluded).get(this.faction);
        if (e == null) return;
        if (!this.showExcluded && !e.excludable(VehiclePoolCatalogSource::isVehicleId)) {
            this.note = Component.translatable("gui.tacz_sewv.target_stack.note.not_excludable", e.text());
            return;
        }
        from.remove(sel);
        to.add(e.text());
        this.table.clampScroll();
        this.note = null;
        refreshCandidates();
    }

    private void removeSelected() {
        List<String> list = rows();
        int sel = this.table.selected();
        if (sel < 0 || sel >= list.size()) return;
        list.remove(sel);
        this.table.clampScroll();
        refreshCandidates();
    }

    private void resetCurrent() {
        this.priority.put(this.faction, new ArrayList<>(this.defaults.priority()));
        this.excluded.put(this.faction, new ArrayList<>(this.defaults.excluded()));
        this.table.reset();
        this.note = null;
        refreshCandidates();
    }

    private void save() {
        Map<TankFaction, WorldTargetStack.Stack> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            out.put(f, new WorldTargetStack.Stack(this.priority.get(f), this.excluded.get(f)));
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketUpdateTargetStack(out));
        onClose();
    }

    // ---------------------------------------------------------------- rows

    private Info info(String row) {
        return this.infos.computeIfAbsent(row, TargetStackScreen::resolve);
    }

    private String nameOf(String row) {
        return info(row).name();
    }

    private static Info resolve(String row) {
        StackEntry e = StackEntry.parse(row);
        if (e == null) {
            return new Info("?", "?", SheetTable.BAD, Component.translatable("gui.tacz_sewv.target_stack.note.invalid"));
        }
        String kind = Component.translatable("gui.tacz_sewv.target_stack.kind." + e.kind().name().toLowerCase(Locale.ROOT)).getString();
        return switch (e.kind()) {
            case GROUP -> new Info(kind, Component.translatable("gui.tacz_sewv.target_stack.group." + e.value()).getString(),
                    SheetTable.LIVE, Component.translatable("gui.tacz_sewv.target_stack.tip.group." + e.value()));
            case TYPE -> {
                ResourceLocation id = ResourceLocation.tryParse(e.value());
                EntityType<?> type = id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                        ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
                yield type == null ? missing(kind) : new Info(kind, type.getDescription().getString(), SheetTable.LIVE, null);
            }
            case MOD -> ModList.get().getModContainerById(e.value())
                    .map(c -> new Info(kind, c.getModInfo().getDisplayName(), SheetTable.LIVE, null))
                    .orElseGet(() -> missing(kind));
            case CATEGORY -> {
                boolean known = false;
                for (MobCategory c : MobCategory.values()) {
                    if (e.value().equals(c.getName())) known = true;
                }
                yield known ? new Info(kind, e.value(), SheetTable.LIVE, null) : missing(kind);
            }
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(e.value());
                boolean known = id != null && BuiltInRegistries.ENTITY_TYPE
                        .getTag(TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id)).isPresent();
                yield known ? new Info(kind, e.value(), SheetTable.LIVE, null) : missing(kind);
            }
        };
    }

    private static Info missing(String kind) {
        return new Info(kind, "?", SheetTable.WARN, Component.translatable("gui.tacz_sewv.target_stack.note.missing"));
    }

    private final class Model implements SheetTable.Model {

        @Override
        public int size() {
            return rows().size();
        }

        @Override
        public String text(int row, int col) {
            String entry = rows().get(row);
            Info i = info(entry);
            return switch (COLS[col]) {
                case "n" -> Integer.toString(row + 1);
                case "entry" -> entry;
                case "kind" -> i.kind();
                case "name" -> i.name();
                default -> "";
            };
        }

        @Override
        public int state(int row) {
            return info(rows().get(row)).state();
        }

        @Override
        public List<Component> tip(int row) {
            String entry = rows().get(row);
            Info i = info(entry);
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(i.name()).withStyle(ChatFormatting.YELLOW));
            out.add(Component.literal(entry).withStyle(ChatFormatting.GRAY));
            if (i.note() != null) out.add(i.note());
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        this.table.mouseScrolled(delta);
        return true;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        Component summary = Component.translatable("gui.tacz_sewv.target_stack.summary", this.faction.name(),
                this.priority.get(this.faction).size(), this.excluded.get(this.faction).size());
        g.drawString(this.font, summary, this.left, this.summaryY, 0xFFA0A0A0, false);
        if (this.note != null) {
            int w = this.font.width(this.note);
            g.drawString(this.font, this.note, this.left + this.panelW - w, this.summaryY, 0xFFFFC04D, false);
        }
        this.table.render(g, this.font, mouseX, mouseY);

        g.drawString(this.font, Component.translatable(this.showExcluded
                        ? "gui.tacz_sewv.target_stack.lbl.add_excluded" : "gui.tacz_sewv.target_stack.lbl.add"),
                this.left, this.labelY, 0xFF909090, false);
        for (int i = 0; i < this.candidates.size(); i++) {
            String id = this.candidates.get(i);
            String name = nameOf(id);
            String line = name.equals("?") || name.equals(id) ? id : id + "  -  " + name;
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.panelW), this.left + 2,
                    this.candTop + i * CAND_H, i == 0 ? 0xFFFFFF55 : 0xFFB0B0B0, false);
        }
        if (this.candidates.isEmpty() && !this.filterBox.getValue().isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.pool.no_match"),
                    this.left + 2, this.candTop, 0xFFFF6666, false);
        }

        List<Component> tip = this.table.hoverTip(mouseX, mouseY);
        if (tip != null) SheetTable.drawTip(g, this.font, tip, mouseX, mouseY);
        else this.hoverTips.render(g, this.font, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
