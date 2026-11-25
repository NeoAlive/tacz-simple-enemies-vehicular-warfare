package com.neoalive.tacz_sewv.client.voice;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import com.neoalive.tacz_sewv.compat.vosk.VoskCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.ConfigPersistence;
import com.neoalive.tacz_sewv.platform.SewvScreen;

/**
 * First run setup for voice orders: consent, model download, microphone check, push to talk key. VoskLib's
 * own model screen asks a player to pick one of dozens of models without saying that only the small ones
 * work here, and picks no microphone at all; this asks only what a player can answer.
 *
 * <p>The model follows the game language, and from then on the phrase table follows the model.
 */
public final class VoiceSetupScreen extends SewvScreen {

    private enum Page { CONSENT, INSTALL, MIC, KEY }

    private static final int WIDTH = 300;
    private static final int BAR_H = 8;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_DIM = 0xFF9AA3AD;
    private static final int COL_BAR_BG = 0xFF2A2F36;
    private static final int COL_BAR = 0xFF5CCB6A;
    private static final int COL_FAIL = 0xFFE85A4A;

    @Nullable
    private final Screen parent;
    private final String model;
    private Page page = Page.CONSENT;
    private volatile boolean installDone;
    private volatile boolean installFailed;
    private final MicLevelTest levelTest = new MicLevelTest();
    private final List<String> devices = new ArrayList<>();
    private int device;
    private boolean capturingKey;

    public VoiceSetupScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.tacz_sewv.voice_setup.title"));
        this.parent = parent;
        this.model = VoiceModels.forGameLanguage(net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected());
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = this.width / 2;
        int y = this.height / 2 + 40;
        switch (this.page) {
            case CONSENT -> {
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.start"), b -> startInstall())
                        .bounds(cx - 154, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.later"), b -> onClose())
                        .bounds(cx - 50, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.never"), b -> {
                    stopPrompting();
                    onClose();
                }).bounds(cx + 54, y, 100, 20).build());
            }
            case INSTALL -> {
                if (this.installFailed) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.retry"), b -> startInstall())
                            .bounds(cx - 102, y, 100, 20).build());
                    addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.later"), b -> onClose())
                            .bounds(cx + 2, y, 100, 20).build());
                }
            }
            case MIC -> {
                addRenderableWidget(Button.builder(deviceLabel(), b -> {
                    this.device = (this.device + 1) % this.devices.size();
                    this.levelTest.start(this.devices.get(this.device));
                    b.setMessage(deviceLabel());
                }).bounds(cx - 150, y - 30, 300, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.next"), b -> {
                    saveDevice();
                    this.levelTest.stop();
                    show(Page.KEY);
                }).bounds(cx - 50, y, 100, 20).build());
            }
            case KEY -> {
                addRenderableWidget(Button.builder(keyLabel(), b -> {
                    this.capturingKey = true;
                    b.setMessage(Component.translatable("gui.tacz_sewv.voice_setup.press_key").withStyle(ChatFormatting.YELLOW));
                }).bounds(cx - 100, y - 30, 200, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.tacz_sewv.voice_setup.finish"), b -> {
                    stopPrompting();
                    onClose();
                }).bounds(cx - 50, y, 100, 20).build());
            }
        }
    }

    private void show(Page next) {
        this.page = next;
        if (next == Page.MIC) {
            this.devices.clear();
            this.devices.add("");
            this.devices.addAll(JavaSoundMic.deviceNames());
            String saved = savedDevice();
            this.device = Math.max(0, this.devices.indexOf(saved));
            this.levelTest.start(this.devices.get(this.device));
        }
        init();
    }

    private void startInstall() {
        this.installDone = false;
        this.installFailed = false;
        this.page = Page.INSTALL;
        init();
        VoskCompat.install(this.model, VoiceModels.url(this.model),
                () -> this.minecraft.execute(() -> {
                    this.installDone = true;
                    if (this.page == Page.INSTALL) show(Page.MIC);
                }),
                () -> this.minecraft.execute(() -> {
                    this.installFailed = true;
                    if (this.page == Page.INSTALL) init();
                }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.capturingKey) {
            KeyMapping mapping = VoiceOrders.Keys.PUSH_TO_TALK;
            mapping.setKey(keyCode == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN
                    : InputConstants.getKey(keyCode, scanCode));
            KeyMapping.resetMapping();
            this.minecraft.options.save();
            this.capturingKey = false;
            init();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = this.width / 2;
        int top = this.height / 2 - 70;
        g.drawCenteredString(this.font, this.title, cx, top, COL_TEXT);
        int y = top + 20;
        switch (this.page) {
            case CONSENT -> {
                y = para(g, Component.translatable("gui.tacz_sewv.voice_setup.consent",
                        this.model, VoiceModels.sizeMib(this.model)), cx, y, COL_TEXT);
                para(g, Component.translatable("gui.tacz_sewv.voice_setup.consent_note"), cx, y + 6, COL_DIM);
            }
            case INSTALL -> {
                if (this.installFailed) {
                    para(g, Component.translatable("gui.tacz_sewv.voice_setup.failed"), cx, y, COL_FAIL);
                } else {
                    long[] p = VoskCompat.progress();
                    boolean loading = p[1] > 0 && p[0] >= p[1];
                    String line = p[1] <= 0 || loading
                            ? Component.translatable(loading ? "gui.tacz_sewv.voice_setup.loading"
                                    : "gui.tacz_sewv.voice_setup.connecting").getString()
                            : Component.translatable("gui.tacz_sewv.voice_setup.downloading",
                                    p[0] >> 20, p[1] >> 20).getString();
                    g.drawCenteredString(this.font, line, cx, y, COL_TEXT);
                    bar(g, cx, y + 16, p[1] > 0 ? (float) p[0] / p[1] : 0);
                }
            }
            case MIC -> {
                y = para(g, Component.translatable("gui.tacz_sewv.voice_setup.mic"), cx, y, COL_TEXT);
                if (!this.levelTest.opened()) {
                    para(g, Component.translatable("gui.tacz_sewv.voice_setup.mic_failed"), cx, y + 4, COL_FAIL);
                } else {
                    bar(g, cx, y + 6, this.levelTest.level());
                }
            }
            case KEY -> para(g, Component.translatable("gui.tacz_sewv.voice_setup.key", samplePhrase()), cx, y, COL_TEXT);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int para(GuiGraphics g, Component text, int cx, int y, int colour) {
        for (FormattedCharSequence line : this.font.split(text, WIDTH)) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, y, colour, false);
            y += 10;
        }
        return y;
    }

    private void bar(GuiGraphics g, int cx, int y, float fill) {
        int left = cx - WIDTH / 2;
        g.fill(left, y, left + WIDTH, y + BAR_H, COL_BAR_BG);
        g.fill(left, y, left + Math.round(WIDTH * Math.max(0, Math.min(1, fill))), y + BAR_H, COL_BAR);
    }

    private Component deviceLabel() {
        String name = this.devices.isEmpty() ? "" : this.devices.get(this.device);
        return Component.translatable("gui.tacz_sewv.voice_setup.device",
                name.isEmpty() ? Component.translatable("gui.tacz_sewv.voice_setup.default_device") : Component.literal(name));
    }

    private Component keyLabel() {
        return Component.translatable("gui.tacz_sewv.voice_setup.key_button",
                VoiceOrders.Keys.PUSH_TO_TALK.getTranslatedKeyMessage());
    }

    /** An order to try, in the language the model hears. */
    private String samplePhrase() {
        String table = VoicePhrases.langOfModel(VoskCompat.modelName());
        VoicePhrases phrases = VoicePhrases.load(table != null ? table : VoicePhrases.DEFAULT_LANG);
        if (phrases == null) return "";
        List<List<String>> hold = phrases.orders().getOrDefault("quick_hold", List.of());
        return hold.isEmpty() ? "" : String.join(" ", hold.get(hold.size() > 1 ? 1 : 0));
    }

    private static String savedDevice() {
        try {
            return ClientConfig.VOICE_ORDERS_MIC_DEVICE.get();
        } catch (Throwable t) {
            return "";
        }
    }

    private void saveDevice() {
        ClientConfig.VOICE_ORDERS_MIC_DEVICE.set(this.devices.get(this.device));
        ConfigPersistence.saveClient();
    }

    private static void stopPrompting() {
        ClientConfig.VOICE_SETUP_PROMPT.set(false);
        ConfigPersistence.saveClient();
    }

    @Override
    public void removed() {
        this.levelTest.stop();
        super.removed();
    }

    @Override
    public void onClose() {
        this.levelTest.stop();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !this.capturingKey;
    }
}
