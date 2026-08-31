package com.neoalive.tacz_sewv.client.gui.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.config.ConfigApplier;
import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;
import com.neoalive.tacz_sewv.config.ConfigScope;
import com.neoalive.tacz_sewv.config.ConfigValidator;
import com.neoalive.tacz_sewv.config.ConfigValueType;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketConfigShortcut;
import com.neoalive.tacz_sewv.network.PacketSaveConfigUI;

public class ConfigUIScreen extends Screen {

    private static final int PANEL_MAX_W = 520;
    private static final float PANEL_WIDTH_FRAC = 0.70f;
    private static final int PAD = 14;
    private static final int TITLE_H = 16;
    private static final int RIBBON_H = 22;
    private static final int FOOTER_H = 40;
    private static final int ROW_H = 26;
    private static final int ROW_H_MULTI = 58;
    private static final int ROW_GAP = 4;
    private static final int LABEL_W = 148;
    private static final int ROW_INSET = 8;
    private static final int SCROLLBAR_W = 5;
    private static final int SCOPE_TAB_H = 20;
    private static final int SCOPE_TAB_W = 72;
    private static final int SHORTCUT_BTN_W = 200;
    private static final int SHORTCUT_BTN_H = 22;
    private static final int SHORTCUT_GAP = 8;

    private static final int COL_BASE = 0xCC12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_RIBBON = 0xFF2A3340;
    private static final int COL_RIBBON_SEL = 0xFF4FD1C5;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;

    private final boolean canEditServer;
    private final Map<Integer, String> clientBaseline;
    private final Map<Integer, String> serverBaseline;
    private final Map<Integer, String> clientDraft;
    private final Map<Integer, String> serverDraft;

    private ConfigScope scope = ConfigScope.CLIENT;
    private int categoryIndex;
    private int categoryScroll;
    private int contentScroll;
    private boolean draggingScrollbar;

    private Button confirmButton;
    private Button resetButton;
    private Button scopeClientTab;
    private Button scopeServerTab;
    private Button ribbonLeft;
    private Button ribbonRight;

    private final List<RowWidgets> rows = new ArrayList<>();

    private record RowWidgets(ConfigEntry entry, ConfigWidgets.OnOffSwitch toggle,
                              ConfigWidgets.ValidatedEditBox field, Button enumBtn,
                              Button shortcutBtn, Button minusBtn, Button plusBtn) {}

    public ConfigUIScreen(boolean canEditServer,
                          Map<Integer, String> clientDraft,
                          Map<Integer, String> serverDraft) {
        super(Component.translatable("gui.tacz_sewv.config.title"));
        this.canEditServer = canEditServer;
        this.clientDraft = new HashMap<>(clientDraft);
        this.serverDraft = new HashMap<>(serverDraft);
        this.clientBaseline = Map.copyOf(clientDraft);
        this.serverBaseline = Map.copyOf(serverDraft);
        if (canEditServer) {
            this.scope = ConfigScope.SERVER;
        }
    }

    private int panelW() {
        return Mth.clamp((int) (this.width * PANEL_WIDTH_FRAC), 340, PANEL_MAX_W);
    }

    private int panelLeft() {
        return (this.width - panelW()) / 2;
    }

    private int panelTop() {
        return PAD + 10;
    }

    private int panelBottom() {
        return this.height - PAD;
    }

    private int scopeTabY() {
        return panelTop() + TITLE_H + 4;
    }

    private int ribbonY() {
        return scopeTabY() + SCOPE_TAB_H + 6;
    }

    private int contentTop() {
        return ribbonY() + RIBBON_H + 10;
    }

    private int contentBottom() {
        return panelBottom() - FOOTER_H;
    }

    private int contentLeft() {
        return panelLeft() + PAD + ROW_INSET;
    }

    private int contentRight() {
        return panelLeft() + panelW() - PAD - SCROLLBAR_W - 4 - ROW_INSET;
    }

    private int labelX() {
        return contentLeft();
    }

    private int valueX() {
        return contentLeft() + LABEL_W + 8;
    }

    private int valueW() {
        return contentRight() - valueX();
    }

    private Map<Integer, String> activeDraft() {
        return this.scope == ConfigScope.CLIENT ? this.clientDraft : this.serverDraft;
    }

    private Map<Integer, String> activeBaseline() {
        return this.scope == ConfigScope.CLIENT ? this.clientBaseline : this.serverBaseline;
    }

    private List<String> categories() {
        return ConfigRegistry.categoriesForScope(this.scope);
    }

    private String currentCategory() {
        List<String> cats = categories();
        if (cats.isEmpty()) return "";
        return cats.get(Mth.clamp(this.categoryIndex, 0, cats.size() - 1));
    }

    private boolean isShortcutsCategory() {
        return "shortcuts".equals(currentCategory());
    }

    @Override
    protected void init() {
        clearWidgets();
        this.rows.clear();

        int left = panelLeft();
        int pw = panelW();
        int tabY = scopeTabY();

        this.scopeClientTab = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.scope.client"),
                        b -> switchScope(ConfigScope.CLIENT))
                .bounds(left + PAD, tabY, SCOPE_TAB_W, SCOPE_TAB_H).build());
        this.scopeServerTab = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.scope.server"),
                        b -> switchScope(ConfigScope.SERVER))
                .bounds(left + PAD + SCOPE_TAB_W + 6, tabY, SCOPE_TAB_W, SCOPE_TAB_H).build());
        this.scopeServerTab.active = this.canEditServer;
        this.scopeServerTab.visible = this.canEditServer;

        int ribbonY = ribbonY();
        this.ribbonLeft = addRenderableWidget(Button.builder(Component.literal("<"), b -> scrollCategories(-1))
                .bounds(left + PAD, ribbonY, 16, RIBBON_H).build());
        this.ribbonRight = addRenderableWidget(Button.builder(Component.literal(">"), b -> scrollCategories(1))
                .bounds(left + pw - PAD - 16, ribbonY, 16, RIBBON_H).build());

        rebuildEntryWidgets();

        int btnY = panelBottom() - 30;
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx - 158, btnY, 96, 20).build());
        this.resetButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.reset"), b -> resetCategory())
                .bounds(cx - 48, btnY, 96, 20).build());
        this.resetButton.setTooltip(Tooltip.create(
                Component.translatable("gui.tacz_sewv.config.reset.tooltip")));
        this.confirmButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.confirm"), b -> confirm())
                .bounds(cx + 62, btnY, 96, 20).build());

        updateConfirmButton();
    }

    private void switchScope(ConfigScope next) {
        if (next == ConfigScope.SERVER && !this.canEditServer) return;
        this.scope = next;
        this.categoryIndex = 0;
        this.categoryScroll = 0;
        this.contentScroll = 0;
        init();
    }

    private void scrollCategories(int dir) {
        this.categoryScroll = Mth.clamp(this.categoryScroll + dir, 0, Math.max(0, categories().size() - 1));
    }

    private void resetCategory() {
        Map<Integer, String> draft = activeDraft();
        for (ConfigEntry entry : ConfigRegistry.forCategory(this.scope, currentCategory())) {
            if (entry.type == ConfigValueType.SHORTCUT) continue;
            draft.put(entry.index, entry.defaultDraftString());
        }
        this.contentScroll = 0;
        init();
    }

    private void rebuildEntryWidgets() {
        this.rows.clear();
        List<ConfigEntry> entries = ConfigRegistry.forCategory(this.scope, currentCategory());
        Map<Integer, String> draft = activeDraft();

        for (ConfigEntry entry : entries) {
            String value = draft.computeIfAbsent(entry.index, k -> entry.draftString());
            Runnable onChange = this::updateConfirmButton;

            if (entry.type == ConfigValueType.SHORTCUT) {
                Button btn = addRenderableWidget(Button.builder(
                                Component.translatable(entry.labelKey()),
                                b -> openShortcut(entry))
                        .bounds(0, 0, SHORTCUT_BTN_W, SHORTCUT_BTN_H).build());
                btn.setTooltip(Tooltip.create(Component.translatable(entry.tooltipKey())));
                this.rows.add(new RowWidgets(entry, null, null, null, btn, null, null));
                continue;
            }

            if (entry.type == ConfigValueType.BOOLEAN || entry.type == ConfigValueType.GAMERULE_BOOL) {
                boolean on = Boolean.parseBoolean(value);
                ConfigWidgets.OnOffSwitch sw = addRenderableWidget(
                        new ConfigWidgets.OnOffSwitch(0, 0, on, v -> {
                            draft.put(entry.index, v ? "true" : "false");
                            onChange.run();
                        }));
                this.rows.add(new RowWidgets(entry, sw, null, null, null, null, null));
                continue;
            }

            if (entry.type == ConfigValueType.ENUM) {
                Button cycle = addRenderableWidget(Button.builder(enumOptionLabel(entry, value), b -> {
                    cycleEnum(entry);
                    onChange.run();
                }).bounds(0, 0, Math.min(140, valueW()), 20).build());
                applyEnumTooltip(cycle, entry, value);
                this.rows.add(new RowWidgets(entry, null, null, cycle, null, null, null));
                continue;
            }

            int fieldH = entry.type == ConfigValueType.MULTILINE_IDS ? ROW_H_MULTI - 6 : 18;
            boolean multiline = entry.type == ConfigValueType.MULTILINE_IDS;
            ConfigWidgets.ValidatedEditBox box = addRenderableWidget(
                    new ConfigWidgets.ValidatedEditBox(this.font, 0, 0, valueW(), fieldH,
                            Component.translatable(entry.labelKey()), multiline, onChange));
            box.setDraftValue(value);
            if (entry.type == ConfigValueType.INT) {
                box.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
            } else if (entry.type == ConfigValueType.DOUBLE) {
                box.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
            } else if (entry.type == ConfigValueType.HEX_COLOR) {
                box.setFilter(s -> s.isEmpty() || s.matches("[#0-9A-Fa-f]*"));
            }
            this.rows.add(new RowWidgets(entry, null, box, null, null, null, null));
        }
        layoutRows();
    }

    private static Component enumOptionLabel(ConfigEntry entry, String option) {
        return Component.translatable(ConfigEntry.enumOptionLabelKey(entry.key, option));
    }

    private static void applyEnumTooltip(Button button, ConfigEntry entry, String option) {
        Component field = Component.translatable(entry.tooltipKey());
        Component optionTip = Component.translatable(ConfigEntry.enumOptionTooltipKey(entry.key, option));
        button.setTooltip(Tooltip.create(Component.empty().append(field).append("\n").append(optionTip)));
    }

    private void cycleEnum(ConfigEntry entry) {
        if (entry.enumOptions == null || entry.enumOptions.isEmpty()) return;
        Map<Integer, String> draft = activeDraft();
        String cur = draft.getOrDefault(entry.index, entry.draftString());
        int idx = entry.enumOptions.indexOf(cur);
        int next = (idx + 1) % entry.enumOptions.size();
        draft.put(entry.index, entry.enumOptions.get(next));
        init();
    }

    private void openShortcut(ConfigEntry entry) {
        if (entry.shortcutAction == null) return;
        if ("doctrine_info".equals(entry.shortcutAction)) {
            if (this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.translatable("config.tacz_sewv.player_doctrine_info.tooltip"), false);
            }
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketConfigShortcut(entry.shortcutAction));
    }

    private void layoutRows() {
        int clipTop = contentTop();
        int clipBottom = contentBottom();
        int y = clipTop - this.contentScroll;

        if (isShortcutsCategory()) {
            layoutShortcutRows(y, clipTop, clipBottom);
            return;
        }

        for (RowWidgets row : this.rows) {
            ConfigEntry entry = row.entry();
            int rowH = rowHeight(entry);
            boolean visible = y + rowH >= clipTop && y <= clipBottom;
            int valueY = y + (rowH - 20) / 2;

            if (row.toggle() != null) {
                row.toggle().setTooltip(Tooltip.create(Component.translatable(entry.tooltipKey())));
                row.toggle().setPosition(valueX() + valueW() - ConfigWidgets.switchWidth(), valueY + 1);
                row.toggle().visible = visible;
                row.toggle().active = visible;
            }
            if (row.field() != null) {
                row.field().setTooltip(Tooltip.create(Component.translatable(entry.tooltipKey())));
                row.field().setWidth(valueW());
                row.field().setPosition(valueX(), y + 3);
                row.field().visible = visible;
                row.field().setEditable(visible);
            }
            if (row.enumBtn() != null) {
                String cur = activeDraft().getOrDefault(entry.index, entry.draftString());
                row.enumBtn().setMessage(enumOptionLabel(entry, cur));
                applyEnumTooltip(row.enumBtn(), entry, cur);
                row.enumBtn().setWidth(Math.min(140, valueW()));
                row.enumBtn().setPosition(valueX(), valueY);
                row.enumBtn().visible = visible;
                row.enumBtn().active = visible;
            }
            y += rowH + ROW_GAP;
        }
    }

    private void layoutShortcutRows(int startY, int clipTop, int clipBottom) {
        int totalH = this.rows.size() * (SHORTCUT_BTN_H + SHORTCUT_GAP) - SHORTCUT_GAP;
        int areaH = clipBottom - clipTop;
        int baseY = clipTop + Math.max(0, (areaH - totalH) / 2) - this.contentScroll;
        int cx = panelLeft() + panelW() / 2 - SHORTCUT_BTN_W / 2;

        for (int i = 0; i < this.rows.size(); i++) {
            RowWidgets row = this.rows.get(i);
            int y = baseY + i * (SHORTCUT_BTN_H + SHORTCUT_GAP);
            boolean visible = y + SHORTCUT_BTN_H >= clipTop && y <= clipBottom;
            if (row.shortcutBtn() != null) {
                row.shortcutBtn().setPosition(cx, y);
                row.shortcutBtn().visible = visible;
                row.shortcutBtn().active = visible;
            }
        }
    }

    private static int rowHeight(ConfigEntry entry) {
        return entry.type == ConfigValueType.MULTILINE_IDS ? ROW_H_MULTI : ROW_H;
    }

    private int contentHeight() {
        if (isShortcutsCategory()) {
            int totalH = this.rows.size() * (SHORTCUT_BTN_H + SHORTCUT_GAP) - SHORTCUT_GAP;
            return Math.max(totalH, contentBottom() - contentTop());
        }
        int h = 0;
        for (RowWidgets row : this.rows) {
            h += rowHeight(row.entry()) + ROW_GAP;
        }
        return h;
    }

    private int maxContentScroll() {
        return Math.max(0, contentHeight() - (contentBottom() - contentTop()));
    }

    private void syncDraftFromWidgets() {
        Map<Integer, String> draft = activeDraft();
        for (RowWidgets row : this.rows) {
            ConfigEntry entry = row.entry();
            if (row.toggle() != null) {
                draft.put(entry.index, row.toggle().value() ? "true" : "false");
            } else if (row.field() != null) {
                draft.put(entry.index, row.field().getValue());
            }
        }
    }

    private boolean isDirty() {
        Map<Integer, String> draft = activeDraft();
        Map<Integer, String> baseline = activeBaseline();
        for (ConfigEntry e : ConfigRegistry.forScope(this.scope)) {
            if (e.type == ConfigValueType.SHORTCUT) continue;
            String d = draft.get(e.index);
            String b = baseline.get(e.index);
            if (d == null ? b != null : !d.equals(b)) return true;
        }
        return false;
    }

    private boolean isValid() {
        syncDraftFromWidgets();
        Map<Integer, String> draft = activeDraft();
        boolean ok = true;
        for (RowWidgets row : this.rows) {
            ConfigEntry entry = row.entry();
            if (entry.type == ConfigValueType.SHORTCUT) continue;
            String text = draft.getOrDefault(entry.index, "");
            boolean valid = ConfigValidator.isValid(entry, text);
            if (row.field() != null) row.field().setValid(valid);
            if (!valid) ok = false;
        }
        return ok;
    }

    private void updateConfirmButton() {
        if (this.confirmButton == null) return;
        syncDraftFromWidgets();
        this.confirmButton.active = isDirty() && isValid();
    }

    private Map<Integer, String> collectChanges(ConfigScope targetScope) {
        Map<Integer, String> draft = targetScope == ConfigScope.CLIENT ? this.clientDraft : this.serverDraft;
        Map<Integer, String> baseline = targetScope == ConfigScope.CLIENT ? this.clientBaseline : this.serverBaseline;
        Map<Integer, String> changes = new HashMap<>();
        for (ConfigEntry e : ConfigRegistry.forScope(targetScope)) {
            if (e.type == ConfigValueType.SHORTCUT) continue;
            String d = draft.get(e.index);
            String b = baseline.get(e.index);
            if (d == null ? b != null : !d.equals(b)) {
                changes.put(e.index, d);
            }
        }
        return changes;
    }

    private void confirm() {
        syncDraftFromWidgets();
        if (!isValid()) return;

        Map<Integer, String> clientChanges = collectChanges(ConfigScope.CLIENT);
        if (!clientChanges.isEmpty()) {
            ConfigApplier.applyClient(clientChanges);
        }

        Map<Integer, String> serverChanges = collectChanges(ConfigScope.SERVER);
        if (!serverChanges.isEmpty()) {
            if (this.minecraft.getSingleplayerServer() != null) {
                var server = this.minecraft.getSingleplayerServer();
                var sp = server.getPlayerList().getPlayer(this.minecraft.player.getUUID());
                if (sp != null) {
                    ConfigApplier.applyServer(sp, serverChanges);
                }
            } else {
                NetworkHandler.CHANNEL.sendToServer(new PacketSaveConfigUI(serverChanges));
            }
        }

        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int left = panelLeft();
        int top = panelTop();
        int pw = panelW();
        int ph = panelBottom() - top;
        g.fill(left, top, left + pw, top + ph, COL_BASE);
        g.fill(left, top, left + pw, top + 1, COL_BORDER);
        g.fill(left, top + ph - 1, left + pw, top + ph, COL_BORDER);
        g.fill(left, top, left + 1, top + ph, COL_BORDER);
        g.fill(left + pw - 1, top, left + pw, top + ph, COL_BORDER);

        g.drawCenteredString(this.font, this.title, this.width / 2, top + 4, COL_TEXT);

        int clipTop = contentTop();
        int clipBottom = contentBottom();
        g.fill(left + PAD, clipTop - 4, left + pw - PAD, clipTop, COL_BORDER);
        g.fill(left + PAD, clipTop, left + pw - PAD, clipBottom, COL_SURFACE);
        g.fill(left + PAD, clipBottom, left + pw - PAD, clipBottom + 1, COL_BORDER);

        renderRibbon(g, left, ribbonY(), pw);

        if (!isShortcutsCategory()) {
            g.enableScissor(contentLeft(), clipTop, contentRight(), clipBottom);
            int y = clipTop - this.contentScroll;
            for (RowWidgets row : this.rows) {
                ConfigEntry entry = row.entry();
                int rowH = rowHeight(entry);
                if (y + rowH >= clipTop && y <= clipBottom) {
                    Component label = Component.translatable(entry.labelKey());
                    g.drawString(this.font, label, labelX(), y + 7, COL_TEXT, false);
                    if (entry.type == ConfigValueType.HEX_COLOR && row.field() != null) {
                        String hex = row.field().getValue().replace("#", "");
                        if (hex.matches("[0-9A-Fa-f]{6}")) {
                            int rgb = 0xFF000000 | Integer.parseInt(hex, 16);
                            g.fill(labelX() + LABEL_W - 14, y + 5, labelX() + LABEL_W - 2, y + 17, rgb);
                            g.fill(labelX() + LABEL_W - 14, y + 5, labelX() + LABEL_W - 2, y + 6, COL_BORDER);
                            g.fill(labelX() + LABEL_W - 14, y + 16, labelX() + LABEL_W - 2, y + 17, COL_BORDER);
                        }
                    }
                }
                y += rowH + ROW_GAP;
            }
            g.disableScissor();
        }

        super.render(g, mouseX, mouseY, partial);

        if (contentHeight() > clipBottom - clipTop) {
            int trackH = clipBottom - clipTop;
            int thumbH = Math.max(12, trackH * trackH / contentHeight());
            int maxScroll = maxContentScroll();
            int thumbY = clipTop + (maxScroll == 0 ? 0
                    : (int) ((long) this.contentScroll * (trackH - thumbH) / maxScroll));
            int sbX = left + pw - SCROLLBAR_W - PAD;
            g.fill(sbX, clipTop, sbX + SCROLLBAR_W, clipBottom, 0xFF0E1218);
            g.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, COL_RIBBON_SEL);
        }
    }

    private void renderRibbon(GuiGraphics g, int left, int ribbonY, int pw) {
        int ribbonLeft = left + PAD + 18;
        int ribbonRight = left + pw - PAD - 18;
        g.fill(ribbonLeft, ribbonY, ribbonRight, ribbonY + RIBBON_H, COL_RIBBON);
        List<String> cats = categories();
        int x = ribbonLeft + 4;
        int maxX = ribbonRight - 4;
        for (int i = this.categoryScroll; i < cats.size(); i++) {
            String cat = cats.get(i);
            Component label = ConfigCategoryStyle.ribbonLabel(cat);
            int w = this.font.width(label) + 14;
            if (x + w > maxX) break;
            boolean sel = i == this.categoryIndex;
            if (sel) {
                g.fill(x, ribbonY + 2, x + w, ribbonY + RIBBON_H - 2, ConfigCategoryStyle.selectedBackground(cat));
                g.fill(x, ribbonY + RIBBON_H - 3, x + w, ribbonY + RIBBON_H - 2, ConfigCategoryStyle.accentColor(cat));
            }
            int color = sel ? 0xFFF5F7FA : ConfigCategoryStyle.accentColor(cat);
            g.drawString(this.font, label, x + 7, ribbonY + 7, color, false);
            x += w + 4;
        }
        this.ribbonLeft.setPosition(left + PAD, ribbonY);
        this.ribbonRight.setPosition(left + pw - PAD - 16, ribbonY);
        this.ribbonLeft.visible = this.categoryScroll > 0;
        this.ribbonRight.visible = x >= maxX && this.categoryScroll + 1 < cats.size();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int ribbonLeft = panelLeft() + PAD + 22;
            int ribbonY = ribbonY();
            int maxX = panelLeft() + panelW() - PAD - 22;
            List<String> cats = categories();
            int x = ribbonLeft;
            for (int i = this.categoryScroll; i < cats.size(); i++) {
                Component label = ConfigCategoryStyle.ribbonLabel(cats.get(i));
                int w = this.font.width(label) + 14;
                if (x + w > maxX) break;
                if (mouseX >= x && mouseX < x + w && mouseY >= ribbonY && mouseY < ribbonY + RIBBON_H) {
                    this.categoryIndex = i;
                    this.contentScroll = 0;
                    init();
                    return true;
                }
                x += w + 4;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= panelLeft() && mouseX <= panelLeft() + panelW()
                && mouseY >= contentTop() && mouseY <= contentBottom()) {
            this.contentScroll = Mth.clamp(this.contentScroll - (int) (delta * 16), 0, maxContentScroll());
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
