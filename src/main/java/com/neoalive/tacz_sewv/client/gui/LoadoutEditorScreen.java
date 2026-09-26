package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import com.neoalive.tacz_sewv.client.editor.WeaponCatalog;
import com.neoalive.tacz_sewv.loadout.LoadoutManager;
import com.neoalive.tacz_sewv.loadout.LoadoutMerge;
import com.neoalive.tacz_sewv.loadout.LoadoutRow;
import com.neoalive.tacz_sewv.loadout.WeaponCatalogSource;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenLoadoutEditor;
import com.neoalive.tacz_sewv.network.PacketUpdateLoadouts;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * {@code /sewv pool weapons}: one screen over the whole loadout stack, laid out like a small
 * spreadsheet. Inherited rows (SEM's default file, the Berezka config mod's file, any datapack) are
 * shown read-only with their origin and spawn probability; edits go into OUR layer only — Adopt
 * copies an inherited row into it and hides the original, so nothing here ever writes to another
 * mod's data. Opened only via the server's {@code PacketOpenLoadoutEditor}; Save pushes
 * {@code PacketUpdateLoadouts}.
 *
 * <p>Every control carries a short tooltip and every grid cell is explained on hover; custom
 * tooltips go through {@link #drawTip}, which wraps and caps them so a long entry can never
 * overflow the screen.
 */
public class LoadoutEditorScreen extends Screen {

    private static final int PANEL_W_PREF = 460;
    private static final int ROW_H = 12;
    private static final int HEAD_H = 12;
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = 14;
    private static final int BAR_W = 6;
    private static final int CAND_LINES = 3;
    private static final int CAND_H = 10;
    /** Everything below the grid: label + fields, label + fields, candidates, buttons, padding. */
    private static final int BELOW_GRID = 10 + 20 + 4 + 10 + 20 + 4 + CAND_LINES * CAND_H + 4 + 20 + 6;
    private static final int TIP_W = 190;
    private static final int TIP_MAX_LINES = 8;
    /** The keys base SEM reads; the rest need SEM Extended (and only RU/US units equip them). */
    private static final int BASE_KEYS = 3;

    private static final String[] COLS = {"st", "src", "name", "gun", "ammo", "mode", "att", "wt", "pct"};
    private static final int C_GUN = 3;
    /** Columns whose text is right-aligned, like numbers in a sheet. */
    private static final Set<String> NUMERIC = Set.of("ammo", "att", "wt", "pct");

    private static final int LIVE = 0;
    private static final int OFF = 1;
    private static final int BAD = 2;

    private final PacketOpenLoadoutEditor.Data data;
    private final Map<TankFaction, LoadoutMerge.Faction> layers = new EnumMap<>(TankFaction.class);
    private final Set<String> knownGuns = new HashSet<>();
    private final Map<String, String> labels = new HashMap<>();

    private TankFaction faction = TankFaction.RU;
    private int scroll;
    private int selected = -1;
    private int slotIdx;
    /** True while boxes are being filled from a row, so their responders do not write it back. */
    private boolean loading;
    private boolean draggingBar;
    /** Short-lived feedback line (drawn over the title); the sheet itself has no room for messages. */
    @Nullable
    private Component notice;
    private long noticeUntil;

    private Button managedBtn;
    private Button modeBtn;
    private Button fireBtn;
    private Button slotBtn;
    private EditBox nameBox;
    private EditBox ammoBox;
    private EditBox weightBox;
    private PoolVehicleIdEditBox gunBox;
    private PoolVehicleIdEditBox slotBox;
    @Nullable
    private EditBox activeBox;
    private List<String> candidates = List.of();

    // Geometry, all set in init() and read everywhere else so nothing re-derives its own copy.
    private int left;
    private int panelW;
    private int rows = MIN_ROWS;
    private int statusY;
    private int headY;
    private int dataTop;
    private int labelY1;
    private int labelY2;
    private int candTop;
    private int[] colW = new int[COLS.length];
    private int[] colX = new int[COLS.length];
    private int[] formX = new int[5];

    /** One grid row as the sheet shows it — built per visible row per frame, so it stays cheap. */
    private record Cell(boolean inherited, String tag, int tagColor, String file, LoadoutRow row,
                        int state, String pct) {}

    public LoadoutEditorScreen(PacketOpenLoadoutEditor.Data data) {
        super(Component.translatable("gui.tacz_sewv.loadout.title"));
        this.data = data;
        this.knownGuns.addAll(WeaponCatalog.guns());
        for (TankFaction f : TankFaction.values()) this.layers.put(f, data.layers().get(f).copy());
    }

    // ---------------------------------------------------------------- state helpers

    private LoadoutMerge.Faction layer() {
        return this.layers.get(this.faction);
    }

    private List<LoadoutManager.Inherited> inherited() {
        return this.data.inherited().get(this.faction);
    }

    private List<LoadoutRow> ours() {
        return layer().rows;
    }

    private int total() {
        return inherited().size() + ours().size();
    }

    private boolean isInheritedSel() {
        return this.selected >= 0 && this.selected < inherited().size();
    }

    private boolean isOursSel() {
        return this.selected >= inherited().size() && this.selected < total();
    }

    @Nullable
    private LoadoutRow selectedRow() {
        if (isInheritedSel()) return inherited().get(this.selected).row();
        if (isOursSel()) return ours().get(this.selected - inherited().size());
        return null;
    }

    private String slotKey() {
        return LoadoutRow.ID_KEYS.get(this.slotIdx);
    }

    /** Extra slots only do anything with SEM Extended, and its equipper only covers RU/US. */
    private boolean slotUsable() {
        return this.slotIdx < BASE_KEYS || (this.data.extended() && this.faction != TankFaction.PMC);
    }

    private int maxScroll() {
        return Math.max(0, total() - this.rows);
    }

    private void clampScroll() {
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.panelW = GuiFit.panelW(PANEL_W_PREF, this.width);
        this.left = (this.width - this.panelW) / 2;
        int pw = this.panelW;
        int top = 22;

        int x = this.left;
        for (TankFaction f : TankFaction.values()) {
            final TankFaction ff = f;
            addRenderableWidget(tip(Button.builder(Component.literal(f.name()), b -> {
                this.faction = ff;
                this.scroll = 0;
                this.selected = -1;
                refreshAll();
            }).bounds(x, top, 56, 20).build(), "tip.faction"));
            x += 60;
        }
        int rest = pw - 184;
        this.managedBtn = addRenderableWidget(tip(Button.builder(Component.empty(), b -> {
            layer().managed = !layer().managed;
            refreshAll();
        }).bounds(this.left + 184, top, Math.min(96, rest / 2), 20).build(), "tip.managed"));
        int modeX = this.left + 184 + Math.min(96, rest / 2) + 4;
        this.modeBtn = addRenderableWidget(tip(Button.builder(Component.empty(), b -> {
            layer().replace = !layer().replace;
            refreshAll();
        }).bounds(modeX, top, this.left + pw - modeX, 20).build(), "tip.mode"));

        this.statusY = top + 24;
        this.headY = this.statusY + 12;
        this.dataTop = this.headY + HEAD_H;
        this.rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, (this.height - this.dataTop - BELOW_GRID - 6) / ROW_H));
        clampScroll();
        layoutColumns(pw - BAR_W - 2);

        int listBottom = this.dataTop + this.rows * ROW_H;
        this.labelY1 = listBottom + 4;
        int y1 = this.labelY1 + 10;
        this.labelY2 = y1 + 24;
        int y2 = this.labelY2 + 10;
        this.candTop = y2 + 24;
        int y4 = this.candTop + CAND_LINES * CAND_H + 4;

        // Form row 1: name | gun | fire mode | ammo | weight.
        int nameW = Math.max(70, pw / 5);
        int fireW = 62;
        int ammoW = 40;
        int wtW = 34;
        int gunW = pw - nameW - fireW - ammoW - wtW - 4 * 4;
        this.formX[0] = this.left;
        this.formX[1] = this.formX[0] + nameW + 4;
        this.formX[2] = this.formX[1] + gunW + 4;
        this.formX[3] = this.formX[2] + fireW + 4;
        this.formX[4] = this.formX[3] + ammoW + 4;

        this.nameBox = box(this.formX[0], y1, nameW, 48, "tip.name");
        this.nameBox.setResponder(s -> edit(r -> r.name = s.trim()));
        this.gunBox = new PoolVehicleIdEditBox(this.font, this.formX[1], y1, gunW, 20,
                Component.translatable("gui.tacz_sewv.loadout.lbl.gun"));
        this.gunBox.setHint(Component.translatable("gui.tacz_sewv.loadout.hint.search"));
        this.gunBox.setMaxLength(128);
        this.gunBox.setResponder(s -> {
            edit(r -> r.gunId = s.trim());
            if (!this.loading) this.activeBox = this.gunBox;
            refreshCandidates();
        });
        this.gunBox.setTabCompleter(() -> complete(this.gunBox));
        addRenderableWidget(tip(this.gunBox, "tip.gun"));
        this.fireBtn = addRenderableWidget(tip(Button.builder(Component.empty(), b -> cycleFire())
                .bounds(this.formX[2], y1, fireW, 20).build(), "tip.fire"));
        this.ammoBox = box(this.formX[3], y1, ammoW, 4, "tip.ammo");
        this.ammoBox.setResponder(s -> edit(r -> r.ammo = parse(s, r.ammo)));
        this.weightBox = box(this.formX[4], y1, this.left + pw - this.formX[4], 3, "tip.weight");
        this.weightBox.setResponder(s -> edit(r -> r.weight = parse(s, r.weight)));

        // Form row 2: slot picker | attachment / armor id.
        this.slotBtn = addRenderableWidget(tip(Button.builder(Component.empty(), b -> {
            this.slotIdx = (this.slotIdx + 1) % LoadoutRow.ID_KEYS.size();
            loadSelected();
        }).bounds(this.left, y2, 96, 20).build(), "tip.slot"));
        this.slotBox = new PoolVehicleIdEditBox(this.font, this.left + 100, y2, pw - 100, 20,
                Component.translatable("gui.tacz_sewv.loadout.lbl.slotid"));
        this.slotBox.setHint(Component.translatable("gui.tacz_sewv.loadout.hint.search"));
        this.slotBox.setMaxLength(128);
        this.slotBox.setResponder(s -> {
            edit(r -> {
                String id = s.trim();
                if (id.isEmpty()) r.ids.remove(slotKey());
                else r.ids.put(slotKey(), id);
            });
            if (!this.loading) this.activeBox = this.slotBox;
            refreshCandidates();
        });
        this.slotBox.setTabCompleter(() -> complete(this.slotBox));
        addRenderableWidget(tip(this.slotBox, "tip.slotid"));

        String[] keys = {"add", "held", "adopt", "hide", "remove", "reset", "save"};
        int bw = (pw - (keys.length - 1) * 4) / keys.length;
        Runnable[] actions = {this::addRow, this::addFromHeld, this::adopt, this::toggleHide, this::removeRow,
                this::resetFaction, this::save};
        for (int i = 0; i < keys.length; i++) {
            final Runnable run = actions[i];
            addRenderableWidget(tip(Button.builder(Component.translatable("gui.tacz_sewv.loadout." + keys[i]),
                    b -> run.run()).bounds(this.left + i * (bw + 4), y4, bw, 20).build(), "tip." + keys[i]));
        }
        refreshAll();
    }

    /** Fixed-width cells, a flexible Gun column and a Name column sized off what is left. */
    private void layoutColumns(int gridW) {
        int[] fixed = {12, 42, 0, 0, 30, 36, 24, 24, 32};
        int used = 0;
        for (int w : fixed) used += w;
        int flex = Math.max(60, gridW - used);
        fixed[2] = Math.max(44, Math.min(110, flex * 2 / 5));
        fixed[C_GUN] = Math.max(40, flex - fixed[2]);
        int x = 0;
        for (int i = 0; i < COLS.length; i++) {
            this.colW[i] = fixed[i];
            this.colX[i] = x;
            x += fixed[i];
        }
    }

    private int gridW() {
        return this.colX[COLS.length - 1] + this.colW[COLS.length - 1];
    }

    private <T extends AbstractWidget> T tip(T widget, String key) {
        widget.setTooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.loadout." + key)));
        return widget;
    }

    private EditBox box(int x, int y, int w, int maxLen, String tipKey) {
        EditBox b = new EditBox(this.font, x, y, w, 20, Component.empty());
        b.setMaxLength(maxLen);
        return addRenderableWidget(tip(b, tipKey));
    }

    // ---------------------------------------------------------------- editing

    private void edit(Consumer<LoadoutRow> change) {
        if (this.loading || !isOursSel()) return;
        LoadoutRow r = selectedRow();
        if (r != null) change.accept(r);
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<String> fireModes(LoadoutRow r) {
        List<String> modes = new ArrayList<>();
        WeaponCatalogSource.gunMeta(r.gunId).ifPresent(m -> m.fireModes().forEach(f -> modes.add(f.name())));
        if (modes.isEmpty()) {
            modes.add("AUTO");
            modes.add("SEMI");
        }
        if (!this.data.extended()) modes.remove("BURST");
        return modes;
    }

    private void cycleFire() {
        LoadoutRow r = selectedRow();
        if (r == null || !isOursSel()) return;
        List<String> modes = fireModes(r);
        int i = modes.indexOf(r.fireMode);
        r.fireMode = modes.get((i + 1) % modes.size());
        refreshLabels();
    }

    private void addRow() {
        String typed = this.gunBox.getValue().trim();
        String gun = null;
        if (!typed.isEmpty()) {
            List<String> hits = WeaponCatalog.match(typed, WeaponCatalog.guns(), 1);
            gun = hits.isEmpty() ? typed : hits.get(0);
        } else if (!WeaponCatalog.guns().isEmpty()) {
            gun = WeaponCatalog.guns().get(0);
        }
        if (gun == null) return;
        LoadoutRow r = new LoadoutRow();
        r.gunId = gun;
        r.name = uniqueName(gun.substring(gun.indexOf(':') + 1));
        WeaponCatalogSource.gunMeta(gun).ifPresent(m -> {
            r.ammo = Math.max(1, m.magazine());
            if (!m.fireModes().isEmpty()) r.fireMode = m.fireModes().get(0).name();
        });
        layer().managed = true;
        ours().add(r);
        select(inherited().size() + ours().size() - 1);
    }

    /** Copies the gun in the player's selected hotbar slot: gun, fire mode, loaded rounds and attachments. */
    private void addFromHeld() {
        var player = Minecraft.getInstance().player;
        var row = player == null ? java.util.Optional.<LoadoutRow>empty()
                : WeaponCatalogSource.fromStack(player.getMainHandItem());
        if (row.isEmpty()) {
            this.notice = Component.translatable("gui.tacz_sewv.loadout.notice.not_gun");
            this.noticeUntil = System.currentTimeMillis() + 3500;
            return;
        }
        LoadoutRow r = row.get();
        r.name = uniqueName(r.name);
        layer().managed = true;
        ours().add(r);
        select(inherited().size() + ours().size() - 1);
    }

    private void adopt() {
        if (!isInheritedSel()) return;
        LoadoutManager.Inherited in = inherited().get(this.selected);
        LoadoutRow copy = in.row().copy();
        copy.name = uniqueName(in.name());
        layer().managed = true;
        layer().hidden.add(LoadoutMerge.hideKey(in.fileId(), in.name()));
        ours().add(copy);
        select(inherited().size() + ours().size() - 1);
    }

    private void toggleHide() {
        if (!isInheritedSel()) return;
        LoadoutManager.Inherited in = inherited().get(this.selected);
        String key = LoadoutMerge.hideKey(in.fileId(), in.name());
        if (!layer().hidden.remove(key)) layer().hidden.add(key);
        layer().managed = true;
        refreshAll();
    }

    private void removeRow() {
        if (!isOursSel()) return;
        ours().remove(this.selected - inherited().size());
        this.selected = Math.min(this.selected, total() - 1);
        clampScroll();
        refreshAll();
    }

    private void resetFaction() {
        this.layers.put(this.faction, new LoadoutMerge.Faction());
        this.selected = -1;
        this.scroll = 0;
        refreshAll();
    }

    private void save() {
        NetworkHandler.CHANNEL.sendToServer(new PacketUpdateLoadouts(this.layers));
        onClose();
    }

    private String uniqueName(String wanted) {
        String name = wanted;
        for (int n = 2; hasName(name); n++) name = wanted + "_" + n;
        return name;
    }

    private boolean hasName(String name) {
        return ours().stream().anyMatch(r -> r.name.equals(name));
    }

    private void select(int index) {
        this.selected = index;
        if (index < this.scroll) this.scroll = index;
        else if (index >= this.scroll + this.rows) this.scroll = index - this.rows + 1;
        clampScroll();
        refreshAll();
    }

    // ---------------------------------------------------------------- refresh

    private void refreshAll() {
        refreshLabels();
        loadSelected();
    }

    private void refreshLabels() {
        LoadoutMerge.Faction f = layer();
        this.managedBtn.setMessage(Component.translatable(f.managed
                ? "gui.tacz_sewv.loadout.managed_on" : "gui.tacz_sewv.loadout.managed_off"));
        this.modeBtn.setMessage(Component.translatable(f.replace
                ? "gui.tacz_sewv.loadout.mode_replace" : "gui.tacz_sewv.loadout.mode_inherit"));
        LoadoutRow r = selectedRow();
        this.fireBtn.setMessage(Component.literal(r == null ? "-" : r.fireMode));
        Component slot = Component.translatable("gui.tacz_sewv.loadout.slotname." + slotKey());
        this.slotBtn.setMessage(slotUsable() ? slot : Component.empty().append(slot).append(" *"));
    }

    private void loadSelected() {
        this.activeBox = null;
        this.loading = true;
        LoadoutRow r = selectedRow();
        boolean editable = isOursSel();
        this.nameBox.setValue(r == null ? "" : r.name);
        this.gunBox.setValue(r == null ? "" : r.gunId);
        this.ammoBox.setValue(r == null ? "" : Integer.toString(r.ammo));
        this.weightBox.setValue(r == null ? "" : Integer.toString(r.weight));
        this.slotBox.setValue(r == null ? "" : r.ids.getOrDefault(slotKey(), ""));
        this.nameBox.setEditable(editable);
        this.gunBox.setEditable(editable);
        this.ammoBox.setEditable(editable);
        this.weightBox.setEditable(editable);
        this.slotBox.setEditable(editable && slotUsable());
        this.fireBtn.active = editable;
        this.loading = false;
        refreshLabels();
        refreshCandidates();
    }

    private void refreshCandidates() {
        EditBox active = this.activeBox;
        if (active == null || !editableBox(active)) {
            this.candidates = List.of();
            return;
        }
        if (active == this.gunBox) {
            this.candidates = WeaponCatalog.match(this.gunBox.getValue(), WeaponCatalog.guns(), CAND_LINES);
        } else if (active == this.slotBox) {
            LoadoutRow r = selectedRow();
            this.candidates = WeaponCatalog.match(this.slotBox.getValue(),
                    WeaponCatalog.allowedFor(r == null ? "" : r.gunId, slotKey()), CAND_LINES);
        } else {
            this.candidates = List.of();
        }
    }

    /** Mirrors what {@link #loadSelected} passes to {@code setEditable} (EditBox has no getter). */
    private boolean editableBox(EditBox b) {
        return b == this.gunBox ? isOursSel() : b == this.slotBox && isOursSel() && slotUsable();
    }

    private boolean complete(EditBox target) {
        if (this.candidates.isEmpty() || target != this.activeBox) return false;
        String id = this.candidates.get(0);
        target.setValue(id);
        target.setCursorPosition(id.length());
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        EditBox focused = this.gunBox.isFocused() ? this.gunBox : this.slotBox.isFocused() ? this.slotBox : null;
        // Losing focus keeps the list: a click on a candidate takes focus away before it lands.
        if (focused != null && focused != this.activeBox) {
            this.activeBox = focused;
            refreshCandidates();
        }
    }

    // ---------------------------------------------------------------- sheet model

    private static String sourceTag(String packId) {
        String p = packId == null ? "" : packId.toLowerCase(Locale.ROOT);
        if (p.contains("berezka")) return "CFG";
        if (p.endsWith("simpleenemymod")) return "SEM";
        return p.isEmpty() ? "?" : "PACK";
    }

    private static int tagColor(String tag) {
        return switch (tag) {
            case "SEM" -> 0xFF8FB3D9;
            case "CFG" -> 0xFFE0A050;
            case "PACK" -> 0xFFB58AE0;
            case "NATIVE" -> 0xFF7FD67F;
            default -> 0xFF909090;
        };
    }

    /** Spawn weight of every list entry (0 = cannot spawn), inherited first then ours. */
    private double[] weights() {
        LoadoutMerge.Faction f = layer();
        boolean liveOurs = f.managed && ours().stream().anyMatch(r -> r.weight > 0);
        double[] w = new double[total()];
        for (int i = 0; i < inherited().size(); i++) {
            LoadoutManager.Inherited in = inherited().get(i);
            boolean gone = f.managed && (f.hidden.contains(LoadoutMerge.hideKey(in.fileId(), in.name()))
                    || (f.replace && liveOurs));
            w[i] = gone ? 0 : this.data.extended() ? Math.max(1, in.row().weight) : 1;
        }
        for (int i = 0; i < ours().size(); i++) {
            w[inherited().size() + i] = f.managed ? Math.max(0, ours().get(i).weight) : 0;
        }
        return w;
    }

    private Cell cell(int idx, double[] w, double sum) {
        boolean inh = idx < inherited().size();
        LoadoutRow r;
        String tag;
        String file = "";
        if (inh) {
            LoadoutManager.Inherited in = inherited().get(idx);
            r = in.row();
            tag = sourceTag(in.packId());
            file = in.fileId();
        } else {
            r = ours().get(idx - inherited().size());
            tag = "NATIVE";
        }
        boolean unknownGun = !this.knownGuns.isEmpty() && !this.knownGuns.contains(r.gunId);
        int state = unknownGun ? BAD : w[idx] > 0 ? LIVE : OFF;
        String pct = sum > 0 && w[idx] > 0 ? String.format(Locale.ROOT, "%.0f%%", 100 * w[idx] / sum) : "-";
        return new Cell(inh, tag, tagColor(tag), file, r, state, pct);
    }

    private String gunLabel(String id) {
        return this.labels.computeIfAbsent(id, k -> {
            String l = WeaponCatalog.label(k);
            return l.isEmpty() ? k : l;
        });
    }

    private String cellText(String col, Cell c) {
        return switch (col) {
            case "src" -> Component.translatable("gui.tacz_sewv.loadout.srcshort." + c.tag()).getString();
            case "name" -> c.row().name;
            case "gun" -> gunLabel(c.row().gunId);
            case "ammo" -> Integer.toString(c.row().ammo);
            case "mode" -> c.row().fireMode;
            case "att" -> Integer.toString(c.row().ids.size());
            case "wt" -> Integer.toString(c.row().weight);
            case "pct" -> c.pct();
            default -> "";
        };
    }

    // ---------------------------------------------------------------- input

    private int rowAt(double mx, double my) {
        if (mx < this.left || mx >= this.left + gridW() || my < this.dataTop || my >= this.dataTop + this.rows * ROW_H) {
            return -1;
        }
        int row = (int) ((my - this.dataTop) / ROW_H) + this.scroll;
        return row >= 0 && row < total() ? row : -1;
    }

    private boolean overBar(double mx, double my) {
        int bx = this.left + this.panelW - BAR_W;
        return mx >= bx && mx < bx + BAR_W && my >= this.dataTop && my < this.dataTop + this.rows * ROW_H;
    }

    private void dragBar(double my) {
        int max = maxScroll();
        if (max == 0) return;
        double frac = (my - this.dataTop) / (double) (this.rows * ROW_H);
        this.scroll = (int) Math.round(Math.max(0, Math.min(1, frac)) * max);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overBar(mouseX, mouseY)) {
            this.draggingBar = true;
            dragBar(mouseY);
            return true;
        }
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            select(row);
            return true;
        }
        if (this.activeBox != null && mouseX >= this.left && mouseX < this.left + this.panelW
                && mouseY >= this.candTop && mouseY < this.candTop + CAND_LINES * CAND_H) {
            int i = (int) ((mouseY - this.candTop) / CAND_H);
            if (i >= 0 && i < this.candidates.size()) {
                EditBox target = this.activeBox;
                target.setValue(this.candidates.get(i));
                target.setCursorPosition(target.getValue().length());
                setFocused(target);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (this.draggingBar) {
            dragBar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.scroll -= (int) Math.signum(delta);
        clampScroll();
        return true;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        boolean noticeOn = this.notice != null && System.currentTimeMillis() < this.noticeUntil;
        g.drawCenteredString(this.font, noticeOn ? this.notice : this.title, this.width / 2, 8,
                noticeOn ? 0xFFFF6666 : 0xFFFFFF);

        renderStatusLine(g);
        double[] w = weights();
        double sum = 0;
        for (double d : w) sum += d;
        renderGrid(g, mouseX, mouseY, w, sum);
        renderFormLabels(g);
        renderCandidates(g);
        renderHoverTip(g, mouseX, mouseY, w, sum);
    }

    private void renderStatusLine(GuiGraphics g) {
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.loadout.caps",
                yesNo(this.data.extended()), yesNo(this.data.configMod())), this.left, this.statusY, 0xFFA0A0A0, false);
        if (!this.data.hookActive()) {
            Component warn = Component.translatable("gui.tacz_sewv.loadout.hook_inactive");
            g.drawString(this.font, warn, this.left + this.panelW - this.font.width(warn), this.statusY, 0xFFFF5555, false);
        }
    }

    private void renderGrid(GuiGraphics g, int mx, int my, double[] w, double sum) {
        int gw = gridW();
        int x0 = this.left;
        int bottom = this.dataTop + this.rows * ROW_H;
        g.fill(x0 - 1, this.headY - 1, x0 + gw + 1, bottom + 1, 0xAA000000);
        g.fill(x0, this.headY, x0 + gw, this.headY + HEAD_H, 0xFF2A2A2A);

        for (int i = 0; i < COLS.length; i++) {
            String key = COLS[i];
            if (key.equals("st")) continue;
            Component head = Component.translatable("gui.tacz_sewv.loadout.col." + key);
            int tx = NUMERIC.contains(key) ? x0 + this.colX[i] + this.colW[i] - 2 - this.font.width(head)
                    : x0 + this.colX[i] + 3;
            g.drawString(this.font, head, tx, this.headY + 2, 0xFFE8E8E8, false);
        }

        int hover = rowAt(mx, my);
        for (int i = 0; i < this.rows; i++) {
            int idx = i + this.scroll;
            int y = this.dataTop + i * ROW_H;
            if (idx >= total()) {
                if (i % 2 == 1) g.fill(x0, y, x0 + gw, y + ROW_H, 0x14FFFFFF);
                continue;
            }
            Cell c = cell(idx, w, sum);
            if (i % 2 == 1) g.fill(x0, y, x0 + gw, y + ROW_H, 0x14FFFFFF);
            if (idx == hover) g.fill(x0, y, x0 + gw, y + ROW_H, 0x22FFFFFF);
            if (idx == this.selected) g.fill(x0, y, x0 + gw, y + ROW_H, 0x66FFAA00);

            int stColor = c.state() == LIVE ? 0xFF55FF55 : c.state() == BAD ? 0xFFFF5555 : 0xFF777777;
            g.fill(x0 + 4, y + 3, x0 + 9, y + 8, stColor);

            int textColor = c.state() == BAD ? 0xFFFF8888 : c.state() == OFF ? 0xFF707070
                    : c.inherited() ? 0xFFB8B8B8 : 0xFFFFFFFF;
            for (int k = 0; k < COLS.length; k++) {
                String key = COLS[k];
                if (key.equals("st")) continue;
                String text = this.font.plainSubstrByWidth(cellText(key, c), this.colW[k] - 5);
                int color = key.equals("src") ? c.tagColor() : textColor;
                int tx = NUMERIC.contains(key) ? x0 + this.colX[k] + this.colW[k] - 2 - this.font.width(text)
                        : x0 + this.colX[k] + 3;
                g.drawString(this.font, text, tx, y + 2, color, false);
            }
        }

        // Grid lines: verticals between columns, a heavier rule under the header, and the outline.
        for (int i = 1; i < COLS.length; i++) {
            g.fill(x0 + this.colX[i], this.headY, x0 + this.colX[i] + 1, bottom, 0x33FFFFFF);
        }
        g.fill(x0, this.dataTop - 1, x0 + gw, this.dataTop, 0x99FFFFFF);
        g.fill(x0, bottom, x0 + gw, bottom + 1, 0x55FFFFFF);
        renderScrollbar(g);
    }

    private void renderScrollbar(GuiGraphics g) {
        int bx = this.left + this.panelW - BAR_W;
        int trackH = this.rows * ROW_H;
        g.fill(bx, this.dataTop, bx + BAR_W, this.dataTop + trackH, 0x66000000);
        int max = maxScroll();
        if (max == 0) return;
        int thumbH = Math.max(8, trackH * this.rows / total());
        int thumbY = this.dataTop + (trackH - thumbH) * this.scroll / max;
        g.fill(bx, thumbY, bx + BAR_W, thumbY + thumbH, this.draggingBar ? 0xFFDDDDDD : 0xFF999999);
    }

    private void renderFormLabels(GuiGraphics g) {
        String[] keys = {"name", "gun", "fire", "ammo", "weight"};
        for (int i = 0; i < keys.length; i++) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.loadout.lbl." + keys[i]),
                    this.formX[i], this.labelY1, 0xFF909090, false);
        }
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.loadout.lbl.slot"),
                this.left, this.labelY2, 0xFF909090, false);
        g.drawString(this.font, Component.translatable("gui.tacz_sewv.loadout.lbl.slotid"),
                this.left + 100, this.labelY2, 0xFF909090, false);
        if (!this.candidates.isEmpty()) {
            Component hint = Component.translatable("gui.tacz_sewv.loadout.lbl.complete");
            g.drawString(this.font, hint, this.left + this.panelW - this.font.width(hint), this.labelY2, 0xFF909090, false);
        }
        if (isOursSel() && !slotUsable()) {
            Component need = Component.translatable("gui.tacz_sewv.loadout.slot_needs_ext");
            g.drawString(this.font, need, this.left + 100 + this.font.width(
                    Component.translatable("gui.tacz_sewv.loadout.lbl.slotid")) + 8, this.labelY2, 0xFFFFAA55, false);
        }
    }

    private void renderCandidates(GuiGraphics g) {
        for (int i = 0; i < this.candidates.size(); i++) {
            String id = this.candidates.get(i);
            String label = WeaponCatalog.label(id);
            g.drawString(this.font, this.font.plainSubstrByWidth(label.isEmpty() ? id : id + "  -  " + label,
                            this.panelW), this.left + 2, this.candTop + i * CAND_H, i == 0 ? 0xFFFFFF55 : 0xFFB0B0B0, false);
        }
        if (this.activeBox != null && this.candidates.isEmpty() && editableBox(this.activeBox)
                && !this.activeBox.getValue().isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.loadout.no_match"),
                    this.left + 2, this.candTop, 0xFFFF6666, false);
        }
    }

    // ---------------------------------------------------------------- tooltips

    private void renderHoverTip(GuiGraphics g, int mx, int my, double[] w, double sum) {
        List<Component> tip = null;
        if (my >= this.statusY && my < this.statusY + 10 && mx >= this.left && mx < this.left + this.panelW) {
            tip = List.of(Component.translatable(this.data.hookActive()
                    ? "gui.tacz_sewv.loadout.tip.caps" : "gui.tacz_sewv.loadout.tip.hook"));
        } else if (my >= this.headY && my < this.headY + HEAD_H && mx >= this.left && mx < this.left + gridW()) {
            for (int i = 0; i < COLS.length; i++) {
                if (mx >= this.left + this.colX[i] && mx < this.left + this.colX[i] + this.colW[i]) {
                    tip = List.of(Component.translatable("gui.tacz_sewv.loadout.tip.col." + COLS[i]));
                    break;
                }
            }
        } else {
            int row = rowAt(mx, my);
            if (row >= 0) tip = rowTip(cell(row, w, sum));
        }
        if (tip != null) drawTip(g, tip, mx, my);
    }

    private List<Component> rowTip(Cell c) {
        LoadoutRow r = c.row();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(gunLabel(r.gunId)).withStyle(net.minecraft.ChatFormatting.YELLOW));
        if (!gunLabel(r.gunId).equals(r.gunId)) {
            out.add(Component.literal(r.gunId).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        Component src = Component.translatable("gui.tacz_sewv.loadout.src." + c.tag());
        out.add(c.inherited()
                ? Component.translatable("gui.tacz_sewv.loadout.tip.row.src", src, c.file())
                : Component.translatable("gui.tacz_sewv.loadout.tip.row.ours"));
        if (r.ids.isEmpty()) {
            out.add(Component.translatable("gui.tacz_sewv.loadout.tip.row.att_none"));
        } else {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (var e : r.ids.entrySet()) {
                if (shown++ == 5) {
                    sb.append(", ...");
                    break;
                }
                String id = e.getValue();
                sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey().replace("_id", "")).append(' ')
                        .append(id.substring(id.indexOf(':') + 1));
            }
            out.add(Component.translatable("gui.tacz_sewv.loadout.tip.row.att", sb.toString()));
        }
        out.add(Component.translatable(switch (c.state()) {
            case LIVE -> "gui.tacz_sewv.loadout.tip.row.live";
            case BAD -> "gui.tacz_sewv.loadout.tip.row.bad";
            default -> "gui.tacz_sewv.loadout.tip.row.off";
        }, c.pct()));
        return out;
    }

    /** Wrapped, line-capped tooltip: a long id or attachment list ends in "..." instead of overflowing. */
    private void drawTip(GuiGraphics g, List<Component> lines, int mx, int my) {
        List<FormattedCharSequence> out = new ArrayList<>();
        boolean overflow = false;
        for (Component c : lines) {
            for (FormattedCharSequence s : this.font.split(c, TIP_W)) {
                if (out.size() >= TIP_MAX_LINES) {
                    overflow = true;
                    break;
                }
                out.add(s);
            }
        }
        if (overflow) out.set(out.size() - 1, FormattedCharSequence.forward("...", Style.EMPTY));
        g.renderTooltip(this.font, out, mx, my);
    }

    private static Component yesNo(boolean b) {
        return Component.translatable(b ? "gui.tacz_sewv.loadout.yes" : "gui.tacz_sewv.loadout.no");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
