package com.neoalive.tacz_sewv.client.gui.config;

import java.util.Arrays;
import java.util.stream.Collectors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigValidator;

/** Edits a list-of-ids setting, one id per line. Done writes the draft; nothing is saved yet. */
final class IdListScreen extends Screen {

    private final Screen parent;
    private final ConfigSession session;
    private final ConfigEntry entry;
    private MultiLineEditBox box;
    private Button done;

    IdListScreen(Screen parent, ConfigSession session, ConfigEntry entry) {
        super(Component.translatable(entry.labelKey()));
        this.parent = parent;
        this.session = session;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 20);
        String current = this.box != null ? this.box.getValue() : this.session.get(this.entry);
        this.box = addRenderableWidget(new MultiLineEditBox(this.font, cx - w / 2, 36, w, this.height - 76,
                Component.translatable("gui.tacz_sewv.config.idlist.hint"), this.title));
        this.box.setCharacterLimit(16384);
        this.box.setValue(current);
        setInitialFocus(this.box);

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(cx - 154, this.height - 27, 150, 20).build());
        this.done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            this.session.set(this.entry, normalized());
            onClose();
        }).bounds(cx + 4, this.height - 27, 150, 20).build());
    }

    private String normalized() {
        return Arrays.stream(this.box.getValue().split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
    }

    @Override
    public void tick() {
        this.box.tick();
        this.done.active = ConfigValidator.isValid(this.entry, normalized());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        Component sub = this.done.active
                ? Component.translatable("gui.tacz_sewv.config.idlist.hint")
                : Component.translatable("gui.tacz_sewv.config.idlist.invalid");
        g.drawCenteredString(this.font, sub, this.width / 2, 24, this.done.active ? 0xA0A0A0 : 0xFF5555);
        super.render(g, mouseX, mouseY, partial);
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
