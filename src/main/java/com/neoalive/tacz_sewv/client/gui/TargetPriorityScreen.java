package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketUpdateTargetPriority;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Op-only three-column editor: per-faction MobCategory exclude toggles
 * ({@code /sewv targetPriority}).
 */
public class TargetPriorityScreen extends Screen {

    private static final int LABEL_W = 170;
    private static final int COL_W = 90;
    private static final int ROW_H = 22;
    private static final int VISIBLE = 10;
    private static final TankFaction[] COLS = {TankFaction.RU, TankFaction.US, TankFaction.PMC};

    private final Map<TankFaction, Set<String>> excluded;
    private final Map<TankFaction, Set<String>> defaults;
    private final List<String> catalog;

    private int scroll;
    private final List<Button> toggles = new ArrayList<>();

    public TargetPriorityScreen(Map<TankFaction, Set<String>> excluded,
                                Map<TankFaction, Set<String>> defaults,
                                List<String> catalog) {
        super(Component.translatable("gui.tacz_sewv.target_priority.title"));
        this.excluded = deepCopy(excluded);
        this.defaults = deepCopy(defaults);
        this.catalog = List.copyOf(catalog);
    }

    private static Map<TankFaction, Set<String>> deepCopy(Map<TankFaction, Set<String>> src) {
        Map<TankFaction, Set<String>> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            out.put(f, new LinkedHashSet<>(src.getOrDefault(f, Set.of())));
        }
        return out;
    }

    private int panelW() {
        return LABEL_W + COLS.length * COL_W;
    }

    @Override
    protected void init() {
        this.toggles.clear();
        int left = (this.width - panelW()) / 2;
        int top = 44;
        int rows = Math.min(VISIBLE, Math.max(this.catalog.size(), 1));
        for (int i = 0; i < rows; i++) {
            final int row = i;
            for (int c = 0; c < COLS.length; c++) {
                final int col = c;
                Button b = addRenderableWidget(Button.builder(Component.empty(), btn -> toggle(row, col))
                        .bounds(left + LABEL_W + col * COL_W, top + i * ROW_H, COL_W - 4, 20)
                        .build());
                this.toggles.add(b);
            }
        }
        refreshToggles();

        int bottom = top + rows * ROW_H + 10;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.target_priority.reset"),
                b -> resetDefaults())
                .bounds(left, bottom, 120, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.pool.save"),
                b -> {
                    NetworkHandler.CHANNEL.sendToServer(new PacketUpdateTargetPriority(this.excluded));
                    onClose();
                })
                .bounds(left + panelW() - 100, bottom, 100, 20).build());

        if (this.catalog.size() > VISIBLE) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                if (this.scroll > 0) {
                    this.scroll--;
                    refreshToggles();
                }
            }).bounds(left + panelW() + 4, top, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                if (this.scroll + VISIBLE < this.catalog.size()) {
                    this.scroll++;
                    refreshToggles();
                }
            }).bounds(left + panelW() + 4, top + (VISIBLE - 1) * ROW_H, 20, 20).build());
        }
    }

    private void toggle(int visibleRow, int col) {
        String cat = categoryAt(visibleRow);
        if (cat == null) return;
        Set<String> set = this.excluded.get(COLS[col]);
        if (!set.remove(cat)) set.add(cat);
        refreshToggles();
    }

    private void resetDefaults() {
        for (TankFaction f : TankFaction.values()) {
            this.excluded.put(f, new LinkedHashSet<>(this.defaults.get(f)));
        }
        refreshToggles();
    }

    private String categoryAt(int visibleRow) {
        int idx = this.scroll + visibleRow;
        if (idx < 0 || idx >= this.catalog.size()) return null;
        return this.catalog.get(idx);
    }

    private void refreshToggles() {
        int rows = Math.min(VISIBLE, Math.max(this.catalog.size(), 1));
        for (int i = 0; i < rows; i++) {
            String cat = categoryAt(i);
            for (int c = 0; c < COLS.length; c++) {
                Button b = this.toggles.get(i * COLS.length + c);
                if (cat == null) {
                    b.active = false;
                    b.setMessage(Component.empty());
                    continue;
                }
                b.active = true;
                boolean off = this.excluded.get(COLS[c]).contains(cat);
                b.setMessage(Component.translatable(off
                        ? "gui.tacz_sewv.target_priority.excluded"
                        : "gui.tacz_sewv.target_priority.allowed"));
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int left = (this.width - panelW()) / 2;
        g.drawString(this.font, this.title, left, 12, 0xFFFFFF, false);
        int headerY = 30;
        for (int c = 0; c < COLS.length; c++) {
            String label = COLS[c].name();
            int x = left + LABEL_W + c * COL_W + 8;
            g.drawString(this.font, label, x, headerY, 0xFFFFA0, false);
        }
        int top = 44;
        int rows = Math.min(VISIBLE, Math.max(this.catalog.size(), 1));
        for (int i = 0; i < rows; i++) {
            String cat = categoryAt(i);
            if (cat == null) continue;
            String shown = cat.toUpperCase(Locale.ROOT);
            if (this.font.width(shown) > LABEL_W - 6) {
                shown = this.font.plainSubstrByWidth(shown, LABEL_W - 12) + "…";
            }
            g.drawString(this.font, shown, left, top + i * ROW_H + 6, 0xE0E0E0, false);
        }
        g.drawString(this.font,
                Component.translatable("gui.tacz_sewv.target_priority.hint").getString(),
                left, this.height - 24, 0xA0A0A0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
