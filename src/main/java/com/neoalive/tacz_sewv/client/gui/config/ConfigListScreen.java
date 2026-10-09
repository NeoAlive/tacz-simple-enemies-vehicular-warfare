package com.neoalive.tacz_sewv.client.gui.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;
import com.neoalive.tacz_sewv.config.ConfigScope;
import com.neoalive.tacz_sewv.config.ConfigValueType;

/**
 * One page of settings: a category, the Essentials page, or search across everything. Rows are
 * grouped under section headings ({@link ConfigLayout}); advanced settings stay hidden until
 * "Show advanced", except in search, which always finds everything.
 */
public final class ConfigListScreen extends Screen {

    private enum Mode { CATEGORY, ESSENTIALS, SEARCH }

    /** Remembered for the rest of the game session, across pages. */
    private static boolean showAdvanced;

    private final Screen parent;
    private final ConfigSession session;
    private final Mode mode;
    @Nullable private final ConfigScope scope;
    @Nullable private final String category;
    private String query;
    private final Map<ConfigEntry, String> haystack = new HashMap<>();

    private ConfigEntryList list;
    private Button saveButton;
    private boolean empty;
    /** Restored when a sub-screen (id list editor) returns here and init() rebuilds the list. */
    private double savedScroll;

    private ConfigListScreen(Screen parent, ConfigSession session, Mode mode, Component title,
                             @Nullable ConfigScope scope, @Nullable String category, String query) {
        super(title);
        this.parent = parent;
        this.session = session;
        this.mode = mode;
        this.scope = scope;
        this.category = category;
        this.query = query;
    }

    static ConfigListScreen category(Screen parent, ConfigSession session, ConfigScope scope, String category) {
        return new ConfigListScreen(parent, session, Mode.CATEGORY,
                Component.translatable(ConfigEntry.categoryLabelKey(category)), scope, category, "");
    }

    static ConfigListScreen essentials(Screen parent, ConfigSession session) {
        return new ConfigListScreen(parent, session, Mode.ESSENTIALS,
                Component.translatable("gui.tacz_sewv.config.essentials"), null, null, "");
    }

    static ConfigListScreen search(Screen parent, ConfigSession session, String query) {
        return new ConfigListScreen(parent, session, Mode.SEARCH,
                Component.translatable("gui.tacz_sewv.config.search.title"), null, null, query);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 20);
        boolean toggle = this.mode != Mode.SEARCH;
        int searchW = toggle ? w - 104 : w;

        EditBox search = new EditBox(this.font, cx - w / 2, 26, searchW, 20,
                Component.translatable("gui.tacz_sewv.config.search.page_hint"));
        search.setHint(Component.translatable(this.mode == Mode.SEARCH
                ? "gui.tacz_sewv.config.search.hint" : "gui.tacz_sewv.config.search.page_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(this.query);
        search.setResponder(s -> {
            if (s.equals(this.query)) return;
            this.query = s;
            refresh();
            this.list.setScrollAmount(0);
        });
        addRenderableWidget(search);
        if (this.mode == Mode.SEARCH) setInitialFocus(search);

        if (toggle) {
            addRenderableWidget(CycleButton.onOffBuilder(showAdvanced)
                    .withTooltip(v -> Tooltip.create(Component.translatable("gui.tacz_sewv.config.advanced.tooltip")))
                    .create(cx + w / 2 - 100, 26, 100, 20, Component.translatable("gui.tacz_sewv.config.advanced"),
                            (b, v) -> {
                                showAdvanced = v;
                                refresh();
                            }));
        }

        this.list = new ConfigEntryList(this.minecraft, this.width, this.height, 52, this.height - 32,
                Math.min(360, this.width - 30));
        addWidget(this.list);
        refresh();
        this.list.setScrollAmount(this.savedScroll);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(cx - 154, this.height - 27, 100, 20).build());
        Button resetPage = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.reset_page"), b -> resetPage())
                .bounds(cx - 50, this.height - 27, 100, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.config.reset_page.tooltip")))
                .build());
        resetPage.active = this.mode != Mode.SEARCH || !this.query.isBlank();
        this.saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.save_close"), b -> {
                            this.session.save(this.minecraft);
                            this.minecraft.setScreen(null);
                        })
                .bounds(cx + 54, this.height - 27, 100, 20).build());
        tick();
    }

    /** Rebuilds the rows from the session (after a reset, a master toggle, or a filter change). */
    private void refresh() {
        List<ConfigEntryList.Row> rows = new ArrayList<>();
        String lastHeading = null;
        for (Item item : items()) {
            if (item.heading != null && !item.heading.equals(lastHeading)) {
                rows.add(new ConfigEntryList.SectionRow(this.font, Component.translatable(item.heading)
                        .withStyle(ChatFormatting.BOLD)));
                lastHeading = item.heading;
            }
            ConfigEntry e = item.entry;
            rows.add(ConfigEntryList.valueRow(this, this.font, this.session, e, this::refresh,
                    () -> this.minecraft.setScreen(new IdListScreen(this, this.session, e)),
                    this.mode == Mode.SEARCH && ConfigLayout.isAdvanced(e)));
        }
        this.empty = rows.isEmpty();
        this.list.setRows(rows);
    }

    private record Item(@Nullable String heading, ConfigEntry entry) {}

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        switch (this.mode) {
            case CATEGORY -> {
                List<ConfigEntry> all = ConfigLayout.sorted(
                        ConfigRegistry.forCategory(Objects.requireNonNull(this.scope), this.category));
                // A page made only of advanced settings (Debug) would otherwise open empty.
                boolean advanced = showAdvanced || all.stream().allMatch(ConfigLayout::isAdvanced);
                for (ConfigEntry e : all) {
                    if (!shown(e) || (!advanced && ConfigLayout.isAdvanced(e))) continue;
                    String s = ConfigLayout.sectionOf(e);
                    out.add(new Item(s == null ? null : ConfigEntry.sectionLabelKey(s), e));
                }
            }
            case ESSENTIALS -> {
                for (Map.Entry<String, List<String>> section : ConfigLayout.ESSENTIALS.entrySet()) {
                    for (String key : section.getValue()) {
                        ConfigEntry e = ConfigRegistry.byKey(key);
                        if (e == null || !shown(e)) continue;
                        if (!showAdvanced && ConfigLayout.isAdvanced(e)) continue;
                        out.add(new Item(ConfigEntry.sectionLabelKey(section.getKey()), e));
                    }
                }
            }
            case SEARCH -> {
                if (this.query.isBlank()) return out;
                for (ConfigEntry e : ConfigRegistry.entries()) {
                    if (shown(e)) out.add(new Item(ConfigEntry.categoryLabelKey(e.category), e));
                }
            }
        }
        return out;
    }

    private boolean shown(ConfigEntry e) {
        if (e.type == ConfigValueType.SHORTCUT || e.type == ConfigValueType.SECTION_HEADER) return false;
        if (e.requiresKey != null && !this.session.bool(e.requiresKey)) return false;
        if (this.mode == Mode.SEARCH && e.scope == ConfigScope.SERVER && !this.session.canEditServer) return false;
        return matches(e);
    }

    /** Every whitespace token must appear in the key, label, tooltip, category or section name. */
    private boolean matches(ConfigEntry e) {
        if (this.query.isBlank()) return true;
        String hay = this.haystack.computeIfAbsent(e, x -> {
            String section = ConfigLayout.sectionOf(x);
            return (x.key + ' ' + I18n.get(x.labelKey()) + ' ' + I18n.get(x.tooltipKey()) + ' '
                    + I18n.get(ConfigEntry.categoryLabelKey(x.category)) + ' '
                    + (section == null ? "" : I18n.get(ConfigEntry.sectionLabelKey(section))))
                    .toLowerCase(Locale.ROOT);
        });
        for (String token : this.query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    private void resetPage() {
        for (Item item : items()) {
            if (this.session.editable(item.entry)) this.session.reset(item.entry);
        }
        refresh();
    }

    @Override
    public void tick() {
        if (this.saveButton != null) this.saveButton.active = this.session.dirty() && this.session.valid();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        this.list.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        if (this.empty) {
            Component msg = Component.translatable(this.mode == Mode.SEARCH && this.query.isBlank()
                    ? "gui.tacz_sewv.config.search.prompt" : "gui.tacz_sewv.config.search.no_results");
            g.drawCenteredString(this.font, msg, this.width / 2, 70, 0xA0A0A0);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void removed() {
        if (this.list != null) this.savedScroll = this.list.getScrollAmount();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
