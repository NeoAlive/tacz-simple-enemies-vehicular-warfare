package com.neoalive.tacz_sewv.client.gui.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;
import com.neoalive.tacz_sewv.config.ConfigScope;
import com.neoalive.tacz_sewv.config.ConfigValueType;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketConfigShortcut;

/**
 * Entry screen: search, the Essentials page, then every category under Server / Client headers,
 * then the pool-editor tools. All vanilla widgets; edits live in the shared {@link ConfigSession}
 * until Save.
 */
public final class ConfigHomeScreen extends Screen {

    private static final String SHORTCUTS = "shortcuts";
    private static final int WIDE = 310;

    private final ConfigSession session;
    private ConfigEntryList list;
    private Button saveButton;

    public ConfigHomeScreen(ConfigSession session) {
        super(Component.translatable("gui.tacz_sewv.config.title"));
        this.session = session;
    }

    @Override
    protected void init() {
        ConfigLayout.checkKeys();
        int cx = this.width / 2;
        int w = Math.min(WIDE, this.width - 20);

        EditBox search = new EditBox(this.font, cx - w / 2, 28, w, 20,
                Component.translatable("gui.tacz_sewv.config.search.hint"));
        search.setHint(Component.translatable("gui.tacz_sewv.config.search.hint").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(s -> {
            if (!s.isBlank()) open(ConfigListScreen.search(this, this.session, s));
        });
        addRenderableWidget(search);

        addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.config.essentials"),
                        b -> open(ConfigListScreen.essentials(this, this.session)))
                .bounds(cx - w / 2, 52, w, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.tacz_sewv.config.essentials.tooltip")))
                .build());

        this.list = new ConfigEntryList(this.minecraft, this.width, this.height, 80, this.height - 32, w);
        this.list.setRows(rows());
        addWidget(this.list);

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(cx - 154, this.height - 27, 150, 20).build());
        this.saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.save"), b -> {
                            this.session.save(this.minecraft);
                            this.minecraft.setScreen(null);
                        })
                .bounds(cx + 4, this.height - 27, 150, 20).build());
        tick();
    }

    private List<ConfigEntryList.Row> rows() {
        List<ConfigEntryList.Row> rows = new ArrayList<>();
        rows.add(new ConfigEntryList.SectionRow(this.font, heading("gui.tacz_sewv.config.home.server")));
        if (!this.session.canEditServer) {
            rows.add(new ConfigEntryList.SectionRow(this.font, Component.translatable(
                    "gui.tacz_sewv.config.home.no_permission").withStyle(ChatFormatting.GRAY)));
        }
        addCategoryButtons(rows, ConfigScope.SERVER);
        rows.add(new ConfigEntryList.SectionRow(this.font, heading("gui.tacz_sewv.config.home.client")));
        addCategoryButtons(rows, ConfigScope.CLIENT);

        if (this.session.canEditServer) {
            List<Button> tools = new ArrayList<>();
            for (ConfigEntry e : ConfigRegistry.forCategory(ConfigScope.SERVER, SHORTCUTS)) {
                if (e.type != ConfigValueType.SHORTCUT || e.shortcutAction == null) continue;
                tools.add(Button.builder(Component.translatable(e.labelKey()), b -> NetworkHandler.CHANNEL
                                .sendToServer(new PacketConfigShortcut(e.shortcutAction)))
                        .tooltip(Tooltip.create(Component.translatable(e.tooltipKey())))
                        .size(100, 20).build());
            }
            if (!tools.isEmpty()) {
                rows.add(new ConfigEntryList.SectionRow(this.font, heading("gui.tacz_sewv.config.home.tools")));
                addPairs(rows, tools);
            }
        }
        return rows;
    }

    private static Component heading(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.BOLD);
    }

    private void addCategoryButtons(List<ConfigEntryList.Row> rows, ConfigScope scope) {
        List<Button> buttons = new ArrayList<>();
        for (String cat : ConfigRegistry.categoriesForScope(scope)) {
            if (SHORTCUTS.equals(cat)) continue;
            List<ConfigEntry> entries = ConfigRegistry.forCategory(scope, cat);
            MutableComponent label = Component.translatable(ConfigEntry.categoryLabelKey(cat));
            if (entries.stream().anyMatch(this.session::unsaved)) {
                label.append(Component.literal(" *").withStyle(ChatFormatting.YELLOW));
            }
            Button b = Button.builder(label, btn -> open(ConfigListScreen.category(this, this.session, scope, cat)))
                    .size(100, 20).build();
            boolean editable = scope == ConfigScope.CLIENT || this.session.canEditServer;
            b.active = editable;
            b.setTooltip(Tooltip.create(editable
                    ? Component.translatable("gui.tacz_sewv.config.category.count", entries.size())
                    : Component.translatable("gui.tacz_sewv.config.tip.locked")));
            buttons.add(b);
        }
        addPairs(rows, buttons);
    }

    private static void addPairs(List<ConfigEntryList.Row> rows, List<Button> buttons) {
        for (int i = 0; i < buttons.size(); i += 2) {
            rows.add(new ConfigEntryList.ButtonsRow(buttons.subList(i, Math.min(i + 2, buttons.size()))));
        }
    }

    private void open(Screen next) {
        this.minecraft.setScreen(next);
    }

    @Override
    public void tick() {
        if (this.saveButton != null) this.saveButton.active = this.session.dirty() && this.session.valid();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        this.list.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partial);
    }

    /** Leaving with unsaved edits asks first — the same as a vanilla discard prompt. */
    @Override
    public void onClose() {
        if (!this.session.dirty()) {
            this.minecraft.setScreen(null);
            return;
        }
        this.minecraft.setScreen(new ConfirmScreen(discard -> this.minecraft.setScreen(discard ? null : this),
                Component.translatable("gui.tacz_sewv.config.discard.title"),
                Component.translatable("gui.tacz_sewv.config.discard.message")));
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
