package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.client.gui.GuiFit;
import com.neoalive.tacz_sewv.client.skin.LogoPoolRegistry;
import com.neoalive.tacz_sewv.crew.NamePools;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.crew.PmcVoices;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketApplyPmcIdentity;
import com.neoalive.tacz_sewv.network.PacketFetchWebLogo;
import com.neoalive.tacz_sewv.network.PacketRequestPmcIdentity;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/**
 * Tactical Data Terminal: the player's PMC identity (company, recruit names, insignia, crew radio
 * language). Every field is a draft until Apply, which sends one packet.
 */
public class TdtScreen extends Screen {

    private static final int PANEL_W_MIN = 300;
    private static final int PANEL_W_MAX = 360;
    private static final int PAD = 12;
    private static final int HEADER_H = 40;
    private static final int SECTION_H = 13;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 3;
    private static final int SECTION_GAP = 5;
    private static final int FOOTER_H = 28;
    private static final int PREVIEW = 28;
    private static final int LIST_ROW = 13;
    private static final int ICON = 24;
    private static final int ICON_GAP = 4;
    private static final int GRID_ROWS = 4;
    private static final long TIP_DELAY_MS = 450;
    private static final long DISCARD_WINDOW_MS = 3000;
    /** Pop-ups draw above every other layer; shadowed text sits slightly forward in depth. */
    private static final float POP_Z = 300.0F;

    private static final int COL_BG = 0xFF161815;
    private static final int COL_HEADER = 0xFF1C1F1B;
    private static final int COL_FIELD = 0xFF0E100E;
    private static final int COL_FIELD_HOVER = 0xFF1B1E1A;
    private static final int COL_LINE = 0xFF2D312B;
    private static final int COL_LINE_HI = 0xFF4C5247;
    private static final int COL_TEXT = 0xFFE6E2D3;
    private static final int COL_MUTED = 0xFF8E9184;
    private static final int COL_FAINT = 0xFF565A50;
    private static final int COL_ACCENT = 0xFFD9A441;
    private static final int COL_ACCENT_HI = 0xFFEBB955;
    private static final int COL_ON_ACCENT = 0xFF1B1406;
    private static final int COL_OK = 0xFF93C06E;
    private static final int COL_ERR = 0xFFE27A63;
    private static final int COL_PLATE = 0xFFDCD7C5;

    private enum Pop { NONE, NAMES, POOL, LOGO, VOICE }

    private record Rect(int x, int y, int w, int h) {
        int r() {
            return this.x + this.w;
        }

        int b() {
            return this.y + this.h;
        }

        boolean has(double mx, double my) {
            return mx >= this.x && mx < r() && my >= this.y && my < b();
        }
    }

    private record Opt(String key, String label) {
    }

    // Saved state, mirrored from the server.
    private static String savedCompany = "";
    private static String savedNames = NamePools.RANDOM;
    private static String savedVoice = PmcVoices.DEFAULT;
    private static String savedPool = PmcIdentityPreference.DEFAULT_POOL;
    private static String savedLogo = PmcIdentityPreference.DEFAULT_LOGO;
    private static String savedUrl = "";

    // Drafts, committed by Apply.
    private static String draftCompany = "";
    private static String draftNames = NamePools.RANDOM;
    private static String draftVoice = PmcVoices.DEFAULT;
    private static String draftPool = PmcIdentityPreference.DEFAULT_POOL;
    private static String draftLogo = PmcIdentityPreference.DEFAULT_LOGO;
    private static String draftUrl = "";

    private static boolean webView;
    private static String libPool = PmcIdentityPreference.DEFAULT_POOL;
    private static String libLogo = PmcIdentityPreference.DEFAULT_LOGO;
    private static String urlText = "";
    @Nullable
    private static String pendingUrl;
    private static String webError = "";
    /** Set by any edit; a server snapshot then updates the saved values only, never the drafts. */
    private static boolean edited;

    /** Last server-synced logo pool, used by helmet DogTag compositing. */
    public static String savedLogoPool() {
        return savedPool;
    }

    /** Last server-synced logo id, used by helmet DogTag compositing. */
    public static String savedLogoId() {
        return savedLogo;
    }

    public static void receiveIdentitySync(String company, String pool, String logo, String url, String names,
            String voice) {
        savedCompany = company;
        savedPool = pool;
        savedLogo = logo;
        savedUrl = url;
        savedNames = names;
        savedVoice = PmcVoices.sanitize(voice);
        if (!edited) revert();
    }

    /** Answer to a Load. Ignored unless it is for the link this terminal asked about last. */
    public static void receiveWebLogo(String url, String id, String error) {
        if (pendingUrl == null || !pendingUrl.equals(url)) return;
        pendingUrl = null;
        if (id.isEmpty()) {
            webError = error;
            return;
        }
        webError = "";
        draftPool = WebLogoCache.POOL;
        draftLogo = id;
        draftUrl = url;
        edited = true;
    }

    public void onIdentitySynced() {
        if (this.companyBox != null) this.companyBox.setValue(draftCompany);
        if (this.urlBox != null) this.urlBox.setValue(urlText);
    }

    private static void revert() {
        draftCompany = savedCompany;
        draftNames = savedNames;
        draftVoice = savedVoice;
        draftPool = savedPool;
        draftLogo = savedLogo;
        draftUrl = savedUrl;
        webView = WebLogoCache.POOL.equals(savedPool);
        if (!webView) {
            libPool = savedPool;
            libLogo = savedLogo;
        }
        urlText = savedUrl;
        pendingUrl = null;
        webError = "";
        edited = false;
    }

    private static boolean dirty() {
        return !draftCompany.equals(savedCompany) || !draftNames.equals(savedNames)
                || !draftVoice.equals(savedVoice) || !draftPool.equals(savedPool)
                || !draftLogo.equals(savedLogo) || !draftUrl.equals(savedUrl);
    }

    private int panelW;
    private int panelLeft;
    private int panelTop;
    private int panelBottom;
    private int labelX;
    private int ctrlX;
    private int ctrlR;
    private int secIdentity;
    private int secInsignia;
    private int secRadio;
    private Rect company;
    private Rect names;
    private Rect source;
    private Rect pick;
    private Rect load;
    private Rect detail;
    private Rect voice;
    private Rect btnApply;
    private Rect btnRevert;
    private Rect btnDefaults;
    @Nullable
    private EditBox companyBox;
    @Nullable
    private EditBox urlBox;

    private Pop pop = Pop.NONE;
    private int gridScroll;
    private long discardArmedAt = -1;
    @Nullable
    private String tipKey;
    private long tipSince;

    private TdtScreen() {
        super(Component.translatable("gui.tacz_sewv.tdt.title"));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        mc.setScreen(new TdtScreen());
    }

    // --- Layout --------------------------------------------------------------

    @Override
    protected void init() {
        this.panelW = GuiFit.panelW(this.width, 0.5f, PANEL_W_MIN, PANEL_W_MAX);
        int contentH = HEADER_H + SECTION_GAP
                + 3 * SECTION_H + 6 * (ROW_H + ROW_GAP) + 2 * SECTION_GAP + FOOTER_H;
        this.panelLeft = (this.width - this.panelW) / 2;
        this.panelTop = Math.max(4, (this.height - contentH) / 2);
        this.panelBottom = this.panelTop + contentH;

        int labelW = 0;
        for (String k : new String[]{"pmc_company", "full_names", "logo_source", "logo_pool", "logo_icon",
                "web_url", "web_status", "voice"}) {
            labelW = Math.max(labelW, this.font.width(I18n.get("gui.tacz_sewv.tdt." + k)));
        }
        this.labelX = this.panelLeft + PAD;
        this.ctrlX = this.labelX + labelW + 12;
        this.ctrlR = this.panelLeft + this.panelW - PAD;
        int cw = this.ctrlR - this.ctrlX;

        int y = this.panelTop + HEADER_H + SECTION_GAP;
        this.secIdentity = y;
        y += SECTION_H;
        this.company = new Rect(this.ctrlX, y, cw, ROW_H);
        y += ROW_H + ROW_GAP;
        this.names = new Rect(this.ctrlX, y, cw, ROW_H);
        y += ROW_H + ROW_GAP + SECTION_GAP;
        this.secInsignia = y;
        y += SECTION_H;
        this.source = new Rect(this.ctrlX, y, cw, ROW_H);
        y += ROW_H + ROW_GAP;
        int loadW = this.font.width(I18n.get("gui.tacz_sewv.tdt.web_load")) + 16;
        this.pick = new Rect(this.ctrlX, y, cw, ROW_H);
        this.load = new Rect(this.ctrlR - loadW, y, loadW, ROW_H);
        y += ROW_H + ROW_GAP;
        this.detail = new Rect(this.ctrlX, y, cw, ROW_H);
        y += ROW_H + ROW_GAP + SECTION_GAP;
        this.secRadio = y;
        y += SECTION_H;
        this.voice = new Rect(this.ctrlX, y, cw, ROW_H);

        int by = this.panelBottom - FOOTER_H + (FOOTER_H - ROW_H) / 2;
        this.btnApply = button(this.ctrlR, by, "identity_apply");
        this.btnRevert = button(this.btnApply.x() - 4, by, "identity_revert");
        this.btnDefaults = button(this.btnRevert.x() - 4, by, "identity_reset");

        this.companyBox = box(this.company, 30, "gui.tacz_sewv.tdt.pmc_company.hint");
        this.companyBox.setValue(draftCompany);
        this.companyBox.setResponder(v -> {
            if (v.strip().equals(draftCompany)) return;
            draftCompany = v.strip();
            edited = true;
        });
        this.urlBox = box(new Rect(this.pick.x(), this.pick.y(), this.pick.w() - this.load.w() - 4, ROW_H),
                WebLogoCache.MAX_URL, "gui.tacz_sewv.tdt.web_url.hint");
        this.urlBox.setValue(urlText);
        this.urlBox.setResponder(v -> {
            if (v.equals(urlText)) return;
            urlText = v;
            webError = "";
            edited = true;
        });

        this.pop = Pop.NONE;
        NetworkHandler.CHANNEL.sendToServer(new PacketRequestPmcIdentity());
    }

    /** Right-aligned footer button ending at {@code right}. */
    private Rect button(int right, int y, String key) {
        int w = Math.max(48, this.font.width(I18n.get("gui.tacz_sewv.tdt." + key)) + 16);
        return new Rect(right - w, y, w, ROW_H);
    }

    private EditBox box(Rect field, int maxLen, String hintKey) {
        EditBox b = new EditBox(this.font, field.x() + 6, field.y() + (ROW_H - 8) / 2, field.w() - 12, 10,
                Component.empty());
        b.setBordered(false);
        b.setMaxLength(maxLen);
        b.setTextColor(COL_TEXT);
        b.setHint(Component.translatable(hintKey).withStyle(s -> s.withColor(COL_FAINT & 0xFFFFFF)));
        addRenderableWidget(b);
        return b;
    }

    private Rect urlField() {
        return new Rect(this.pick.x(), this.pick.y(), this.pick.w() - this.load.w() - 4, ROW_H);
    }

    // --- Options -------------------------------------------------------------

    private static List<Opt> nameOptions() {
        List<Opt> out = new ArrayList<>();
        out.add(new Opt(NamePools.RANDOM, I18n.get("gui.tacz_sewv.tdt.names.random")));
        for (String k : NamePools.active().categoryKeys()) out.add(new Opt(k, pretty(k)));
        return out;
    }

    private static List<Opt> poolOptions() {
        List<Opt> out = new ArrayList<>();
        for (String id : LogoPoolRegistry.poolIds()) {
            if (id.startsWith("pmc_")) out.add(new Opt(id, pretty(id.substring(4))));
        }
        if (out.isEmpty()) out.add(new Opt(PmcIdentityPreference.DEFAULT_POOL, pretty("default")));
        return out;
    }

    private static List<Opt> voiceOptions() {
        List<Opt> out = new ArrayList<>();
        for (PmcVoices.Voice v : PmcVoices.ALL) out.add(new Opt(v.key(), voiceName(v.key())));
        return out;
    }

    private static String voiceName(String key) {
        PmcVoices.Voice v = PmcVoices.of(key);
        return v == null || v.name() == null ? I18n.get("gui.tacz_sewv.tdt.voice.default") : v.name();
    }

    private static String nameLabel(String key) {
        return NamePools.RANDOM.equalsIgnoreCase(key) ? I18n.get("gui.tacz_sewv.tdt.names.random") : pretty(key);
    }

    /** {@code SOUTH_AFRICAN} or {@code south_african} to {@code South african}. */
    private static String pretty(String key) {
        String s = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private List<Opt> popOptions() {
        return switch (this.pop) {
            case NAMES -> nameOptions();
            case POOL -> poolOptions();
            case VOICE -> voiceOptions();
            default -> List.of();
        };
    }

    @Nullable
    private Rect popAnchor() {
        return switch (this.pop) {
            case NAMES -> this.names;
            case POOL -> this.pick;
            case LOGO -> this.detail;
            case VOICE -> this.voice;
            default -> null;
        };
    }

    private String popSelected() {
        return switch (this.pop) {
            case NAMES -> draftNames;
            case POOL -> libPool;
            case VOICE -> draftVoice;
            default -> "";
        };
    }

    private void choose(String key) {
        edited = true;
        switch (this.pop) {
            case NAMES -> draftNames = key;
            case VOICE -> draftVoice = key;
            case POOL -> {
                libPool = key;
                List<String> icons = LogoPoolRegistry.iconsIn(key);
                if (!icons.contains(libLogo) && !icons.isEmpty()) libLogo = icons.get(0);
                draftPool = libPool;
                draftLogo = libLogo;
                draftUrl = "";
            }
            default -> {
            }
        }
    }

    // --- Pop-up geometry -----------------------------------------------------

    /** Places a pop-up of size w by h under its anchor, or above it when there is no room below. */
    private Rect placePop(Rect anchor, int w, int h) {
        int x = Mth.clamp(anchor.x(), 4, Math.max(4, this.width - w - 4));
        int y = anchor.b() + 2;
        if (y + h > this.height - 4) y = anchor.y() - h - 2;
        return new Rect(x, Math.max(4, y), w, h);
    }

    private int listCols(int n) {
        return n <= 10 ? 1 : n <= 24 ? 2 : 3;
    }

    private Rect listBox(List<Opt> opts, Rect anchor) {
        int cols = listCols(opts.size());
        int rows = (opts.size() + cols - 1) / cols;
        int colW = 0;
        for (Opt o : opts) colW = Math.max(colW, this.font.width(o.label()));
        colW = Math.max(colW + 18, anchor.w() / cols);
        return placePop(anchor, cols * colW + 4, rows * LIST_ROW + 4);
    }

    private int listAt(List<Opt> opts, Rect box, double mx, double my) {
        if (!box.has(mx, my)) return -1;
        int cols = listCols(opts.size());
        int rows = (opts.size() + cols - 1) / cols;
        int colW = (box.w() - 4) / cols;
        int col = (int) (mx - box.x() - 2) / colW;
        int row = (int) (my - box.y() - 2) / LIST_ROW;
        if (col < 0 || col >= cols || row < 0 || row >= rows) return -1;
        int idx = col * rows + row;
        return idx < opts.size() ? idx : -1;
    }

    private int gridCols(int n) {
        return Mth.clamp(n, 1, 8);
    }

    private Rect gridBox(int n, Rect anchor) {
        int cols = gridCols(n);
        int rows = Math.min(GRID_ROWS, (n + cols - 1) / cols);
        int w = Math.max(anchor.w(), cols * (ICON + ICON_GAP) + ICON_GAP);
        int h = 16 + rows * (ICON + ICON_GAP) + ICON_GAP + 12;
        return placePop(anchor, w, h);
    }

    private int gridAt(List<String> icons, Rect box, double mx, double my) {
        int cols = gridCols(icons.size());
        int x0 = box.x() + (box.w() - (cols * (ICON + ICON_GAP) - ICON_GAP)) / 2;
        int y0 = box.y() + 16;
        int visible = Math.min(GRID_ROWS, (icons.size() + cols - 1) / cols);
        for (int r = 0; r < visible; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (r + this.gridScroll) * cols + c;
                if (idx >= icons.size()) return -1;
                int ix = x0 + c * (ICON + ICON_GAP);
                int iy = y0 + r * (ICON + ICON_GAP);
                if (mx >= ix && mx < ix + ICON && my >= iy && my < iy + ICON) return idx;
            }
        }
        return -1;
    }

    // --- Actions -------------------------------------------------------------

    private void setSource(boolean web) {
        if (web == webView) return;
        webView = web;
        edited = true;
        if (!web && WebLogoCache.POOL.equals(draftPool)) {
            draftPool = libPool;
            draftLogo = libLogo;
            draftUrl = "";
        } else if (web) {
            String url = urlText.strip();
            String id = WebLogoCache.idFor(url);
            if (!url.isEmpty() && LogoPoolRegistry.texture(WebLogoCache.POOL, id) != null) {
                draftPool = WebLogoCache.POOL;
                draftLogo = id;
                draftUrl = url;
            }
        }
    }

    private void loadUrl() {
        String url = urlText.strip();
        if (url.isEmpty() || pendingUrl != null) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            webError = WebLogoCache.ERR_BAD_URL;
            return;
        }
        String id = WebLogoCache.idFor(url);
        webError = "";
        edited = true;
        if (LogoPoolRegistry.texture(WebLogoCache.POOL, id) != null) {
            draftPool = WebLogoCache.POOL;
            draftLogo = id;
            draftUrl = url;
            return;
        }
        pendingUrl = url;
        NetworkHandler.CHANNEL.sendToServer(new PacketFetchWebLogo(url));
    }

    private void apply() {
        if (!dirty()) return;
        edited = false;
        NetworkHandler.CHANNEL.sendToServer(new PacketApplyPmcIdentity(
                draftCompany, draftNames, draftVoice, draftPool, draftLogo, draftUrl));
    }

    private void defaults() {
        draftCompany = "";
        draftNames = NamePools.RANDOM;
        draftVoice = PmcVoices.DEFAULT;
        libPool = PmcIdentityPreference.DEFAULT_POOL;
        libLogo = PmcIdentityPreference.DEFAULT_LOGO;
        draftPool = libPool;
        draftLogo = libLogo;
        draftUrl = "";
        webView = false;
        webError = "";
        edited = true;
        onIdentitySynced();
    }

    // --- Input ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        this.discardArmedAt = -1;
        if (this.pop != Pop.NONE) {
            if (button == 0 && clickPop(mx, my)) beep();
            this.pop = Pop.NONE;
            return true;
        }
        if (button != 0) return super.mouseClicked(mx, my, button);

        for (EditBox b : new EditBox[]{this.companyBox, this.urlBox}) {
            if (b == null) continue;
            boolean hit = b.isVisible() && (b == this.urlBox ? urlField() : this.company).has(mx, my);
            b.setFocused(hit);
            if (hit) {
                setFocused(b);
                b.mouseClicked(Mth.clamp(mx, b.getX(), b.getX() + b.getWidth() - 1), b.getY() + 1, button);
                return true;
            }
        }
        setFocused(null);

        if (clickControls(mx, my)) beep();
        return true;
    }

    private boolean clickControls(double mx, double my) {
        if (this.names.has(mx, my)) return openPop(Pop.NAMES);
        if (this.voice.has(mx, my)) return openPop(Pop.VOICE);
        if (this.source.has(mx, my)) {
            setSource(mx >= this.source.x() + this.source.w() / 2.0);
            return true;
        }
        if (webView) {
            if (this.load.has(mx, my) && pendingUrl == null && !urlText.isBlank()) {
                loadUrl();
                return true;
            }
        } else {
            if (this.pick.has(mx, my)) return openPop(Pop.POOL);
            if (this.detail.has(mx, my) && !LogoPoolRegistry.iconsIn(libPool).isEmpty()) {
                this.gridScroll = scrollTo(LogoPoolRegistry.iconsIn(libPool), libLogo);
                return openPop(Pop.LOGO);
            }
        }
        if (this.btnApply.has(mx, my) && dirty()) {
            apply();
            return true;
        }
        if (this.btnRevert.has(mx, my) && dirty()) {
            revert();
            onIdentitySynced();
            return true;
        }
        if (this.btnDefaults.has(mx, my)) {
            defaults();
            return true;
        }
        return false;
    }

    private boolean openPop(Pop p) {
        this.pop = p;
        return true;
    }

    private int scrollTo(List<String> icons, String icon) {
        int cols = gridCols(icons.size());
        int rows = (icons.size() + cols - 1) / cols;
        int row = Math.max(0, icons.indexOf(icon)) / cols;
        return Mth.clamp(row - GRID_ROWS + 1, 0, Math.max(0, rows - GRID_ROWS));
    }

    private boolean clickPop(double mx, double my) {
        Rect anchor = popAnchor();
        if (anchor == null) return false;
        if (this.pop == Pop.LOGO) {
            List<String> icons = LogoPoolRegistry.iconsIn(libPool);
            int idx = gridAt(icons, gridBox(icons.size(), anchor), mx, my);
            if (idx < 0) return false;
            libLogo = icons.get(idx);
            edited = true;
            draftPool = libPool;
            draftLogo = libLogo;
            draftUrl = "";
            return true;
        }
        List<Opt> opts = popOptions();
        int idx = listAt(opts, listBox(opts, anchor), mx, my);
        if (idx < 0) return false;
        choose(opts.get(idx).key());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (this.pop == Pop.LOGO) {
            List<String> icons = LogoPoolRegistry.iconsIn(libPool);
            int cols = gridCols(icons.size());
            int rows = (icons.size() + cols - 1) / cols;
            this.gridScroll = Mth.clamp(this.gridScroll - (int) Math.signum(delta), 0,
                    Math.max(0, rows - GRID_ROWS));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.pop != Pop.NONE) {
                this.pop = Pop.NONE;
                return true;
            }
            long now = Util.getMillis();
            if (dirty() && (this.discardArmedAt < 0 || now - this.discardArmedAt > DISCARD_WINDOW_MS)) {
                this.discardArmedAt = now;
                return true;
            }
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.urlBox != null && this.urlBox.isFocused()) loadUrl();
            else apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        revert();
        super.onClose();
    }

    private static void beep() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    // --- Render --------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        boolean live = this.pop == Pop.NONE;
        int mx = live ? mouseX : -1;
        int my = live ? mouseY : -1;
        if (this.urlBox != null) this.urlBox.setVisible(webView);

        renderPanel(g, mx, my);
        super.render(g, mouseX, mouseY, partialTick);

        if (!live) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, POP_Z);
            if (this.pop == Pop.LOGO) renderGrid(g, mouseX, mouseY);
            else renderList(g, mouseX, mouseY);
            g.pose().popPose();
        } else {
            renderTip(g, mouseX, mouseY);
        }
    }

    private void renderPanel(GuiGraphics g, int mx, int my) {
        int l = this.panelLeft;
        int r = l + this.panelW;
        int t = this.panelTop;
        int b = this.panelBottom;
        g.fill(l, t, r, b, COL_BG);
        g.fill(l, t, r, t + HEADER_H, COL_HEADER);
        g.fill(l, t + HEADER_H, r, t + HEADER_H + 1, COL_LINE);
        g.fill(l, b - FOOTER_H, r, b - FOOTER_H + 1, COL_LINE);
        outline(g, new Rect(l, t, this.panelW, b - t), COL_LINE);

        renderHeader(g);

        section(g, this.secIdentity, "section.identity");
        label(g, this.company, "pmc_company");
        field(g, this.company, this.company.has(mx, my), this.companyBox != null && this.companyBox.isFocused());
        label(g, this.names, "full_names");
        dropdown(g, this.names, nameLabel(draftNames), mx, my, this.pop == Pop.NAMES);

        section(g, this.secInsignia, "section.insignia");
        label(g, this.source, "logo_source");
        segmented(g, this.source, mx, my);
        if (webView) {
            label(g, this.pick, "web_url");
            Rect uf = urlField();
            field(g, uf, uf.has(mx, my), this.urlBox != null && this.urlBox.isFocused());
            boolean canLoad = pendingUrl == null && !urlText.isBlank();
            drawButton(g, this.load, I18n.get("gui.tacz_sewv.tdt.web_load"), false, canLoad, this.load.has(mx, my));
            label(g, this.detail, "web_status");
            renderStatus(g, this.detail);
        } else {
            label(g, this.pick, "logo_pool");
            dropdown(g, this.pick, pretty(libPool.startsWith("pmc_") ? libPool.substring(4) : libPool), mx, my,
                    this.pop == Pop.POOL);
            label(g, this.detail, "logo_icon");
            boolean hover = this.detail.has(mx, my);
            field(g, this.detail, hover, this.pop == Pop.LOGO);
            logo(g, libPool, libLogo, this.detail.x() + 3, this.detail.y() + 2, ROW_H - 4);
            g.drawString(this.font, fit(libLogo, this.detail.w() - 40), this.detail.x() + ROW_H + 4,
                    this.detail.y() + (ROW_H - 8) / 2, COL_TEXT, false);
            caret(g, this.detail);
        }

        section(g, this.secRadio, "section.radio");
        label(g, this.voice, "voice");
        dropdown(g, this.voice, voiceName(draftVoice), mx, my, this.pop == Pop.VOICE);

        renderFooter(g, mx, my);
    }

    private void renderHeader(GuiGraphics g) {
        int x = this.panelLeft + PAD;
        int y = this.panelTop + (HEADER_H - PREVIEW) / 2 + 1;
        g.fill(x - 1, y - 1, x + PREVIEW + 1, y + PREVIEW + 1, COL_LINE_HI);
        logo(g, draftPool, draftLogo, x, y, PREVIEW);
        int tx = x + PREVIEW + 10;
        int tw = this.panelLeft + this.panelW - PAD - tx;
        g.drawString(this.font, this.title, tx, y + 3, COL_TEXT, false);
        String sub = draftCompany.isEmpty() ? I18n.get("gui.tacz_sewv.tdt.subtitle.none") : draftCompany;
        g.drawString(this.font, fit(sub, tw), tx, y + 16, draftCompany.isEmpty() ? COL_FAINT : COL_MUTED, false);
    }

    private void renderStatus(GuiGraphics g, Rect r) {
        String text;
        int color;
        boolean usingWeb = WebLogoCache.POOL.equals(draftPool);
        if (pendingUrl != null) {
            text = I18n.get("gui.tacz_sewv.tdt.web.loading");
            color = COL_ACCENT;
        } else if (!webError.isEmpty()) {
            text = I18n.get("gui.tacz_sewv.tdt.web.error." + webError);
            color = COL_ERR;
        } else if (usingWeb && urlText.strip().equals(draftUrl)) {
            boolean have = LogoPoolRegistry.texture(draftPool, draftLogo) != null;
            boolean applied = draftLogo.equals(savedLogo) && draftPool.equals(savedPool);
            text = I18n.get(!have ? "gui.tacz_sewv.tdt.web.missing"
                    : applied ? "gui.tacz_sewv.tdt.web.in_use" : "gui.tacz_sewv.tdt.web.ready");
            color = have ? COL_OK : COL_ERR;
        } else {
            text = I18n.get("gui.tacz_sewv.tdt.web.idle");
            color = COL_MUTED;
        }
        g.drawString(this.font, fit(text, r.w()), r.x(), r.y() + (ROW_H - 8) / 2, color, false);
    }

    private void renderFooter(GuiGraphics g, int mx, int my) {
        boolean dirty = dirty();
        int ty = this.panelBottom - FOOTER_H + (FOOTER_H - 8) / 2;
        boolean discard = this.discardArmedAt >= 0 && Util.getMillis() - this.discardArmedAt <= DISCARD_WINDOW_MS;
        if (dirty) {
            String state = I18n.get(discard ? "gui.tacz_sewv.tdt.state.discard" : "gui.tacz_sewv.tdt.state.dirty");
            int x = this.panelLeft + PAD;
            g.drawString(this.font, fit(state, this.btnDefaults.x() - x - 8), x, ty, discard ? COL_ERR : COL_MUTED,
                    false);
        }

        drawButton(g, this.btnDefaults, I18n.get("gui.tacz_sewv.tdt.identity_reset"), false, true,
                this.btnDefaults.has(mx, my));
        drawButton(g, this.btnRevert, I18n.get("gui.tacz_sewv.tdt.identity_revert"), false, dirty,
                this.btnRevert.has(mx, my));
        drawButton(g, this.btnApply, I18n.get("gui.tacz_sewv.tdt.identity_apply"), true, dirty,
                this.btnApply.has(mx, my));
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        Rect anchor = popAnchor();
        if (anchor == null) return;
        List<Opt> opts = popOptions();
        Rect box = listBox(opts, anchor);
        popFrame(g, box);
        int cols = listCols(opts.size());
        int rows = (opts.size() + cols - 1) / cols;
        int colW = (box.w() - 4) / cols;
        int hovered = listAt(opts, box, mx, my);
        String selected = popSelected();
        for (int i = 0; i < opts.size(); i++) {
            int ex = box.x() + 2 + (i / rows) * colW;
            int ey = box.y() + 2 + (i % rows) * LIST_ROW;
            boolean sel = opts.get(i).key().equalsIgnoreCase(selected);
            if (i == hovered) g.fill(ex, ey, ex + colW, ey + LIST_ROW, COL_FIELD_HOVER);
            if (sel) g.fill(ex, ey + 2, ex + 2, ey + LIST_ROW - 2, COL_ACCENT);
            g.drawString(this.font, fit(opts.get(i).label(), colW - 12), ex + 7, ey + 3,
                    sel ? COL_ACCENT : COL_TEXT, false);
        }
    }

    private void renderGrid(GuiGraphics g, int mx, int my) {
        List<String> icons = LogoPoolRegistry.iconsIn(libPool);
        Rect box = gridBox(icons.size(), this.detail);
        popFrame(g, box);
        g.drawString(this.font, fit(pretty(libPool.startsWith("pmc_") ? libPool.substring(4) : libPool),
                box.w() - 12), box.x() + 6, box.y() + 5, COL_ACCENT, false);
        int cols = gridCols(icons.size());
        int totalRows = (icons.size() + cols - 1) / cols;
        if (totalRows > GRID_ROWS) {
            String page = (this.gridScroll + 1) + "-" + Math.min(totalRows, this.gridScroll + GRID_ROWS) + "/" + totalRows;
            g.drawString(this.font, page, box.r() - 6 - this.font.width(page), box.y() + 5, COL_FAINT, false);
        }
        int x0 = box.x() + (box.w() - (cols * (ICON + ICON_GAP) - ICON_GAP)) / 2;
        int y0 = box.y() + 16;
        int hovered = gridAt(icons, box, mx, my);
        int visible = Math.min(GRID_ROWS, totalRows);
        for (int r = 0; r < visible; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (r + this.gridScroll) * cols + c;
                if (idx >= icons.size()) break;
                int ix = x0 + c * (ICON + ICON_GAP);
                int iy = y0 + r * (ICON + ICON_GAP);
                boolean sel = icons.get(idx).equals(libLogo);
                if (sel || idx == hovered) {
                    g.fill(ix - 2, iy - 2, ix + ICON + 2, iy + ICON + 2, sel ? COL_ACCENT : COL_LINE_HI);
                }
                logo(g, libPool, icons.get(idx), ix, iy, ICON);
            }
        }
        String name = hovered >= 0 ? icons.get(hovered) : libLogo;
        g.drawString(this.font, fit(name, box.w() - 12), box.x() + 6, box.b() - 11, COL_MUTED, false);
    }

    private void renderTip(GuiGraphics g, int mx, int my) {
        String key = null;
        if (rowHit(this.company, mx, my)) key = "pmc_company.tip";
        else if (rowHit(this.names, mx, my)) key = "full_names.tip";
        else if (rowHit(this.detail, mx, my) || rowHit(this.pick, mx, my) || rowHit(this.source, mx, my)) {
            key = webView ? "web_url.tip" : "logo_icon.tip";
        } else if (rowHit(this.voice, mx, my)) key = "voice.tip";
        if (key == null || !key.equals(this.tipKey)) {
            this.tipKey = key;
            this.tipSince = Util.getMillis();
            return;
        }
        if (Util.getMillis() - this.tipSince < TIP_DELAY_MS) return;
        g.renderTooltip(this.font, this.font.split(Component.translatable("gui.tacz_sewv.tdt." + key), 220),
                mx, my);
    }

    /** The label part of a row, so the tooltip never covers the control being used. */
    private boolean rowHit(Rect r, int mx, int my) {
        return mx >= this.labelX && mx < r.x() && my >= r.y() && my < r.b();
    }

    // --- Drawing helpers -----------------------------------------------------

    private void section(GuiGraphics g, int y, String key) {
        g.drawString(this.font, I18n.get("gui.tacz_sewv.tdt." + key), this.panelLeft + PAD, y + 3, COL_TEXT, false);
    }

    private void label(GuiGraphics g, Rect row, String key) {
        g.drawString(this.font, I18n.get("gui.tacz_sewv.tdt." + key), this.labelX, row.y() + (ROW_H - 8) / 2,
                COL_MUTED, false);
    }

    private void field(GuiGraphics g, Rect r, boolean hover, boolean active) {
        g.fill(r.x(), r.y(), r.r(), r.b(), hover && !active ? COL_FIELD_HOVER : COL_FIELD);
        outline(g, r, active ? COL_ACCENT : hover ? COL_LINE_HI : COL_LINE);
    }

    private void dropdown(GuiGraphics g, Rect r, String text, int mx, int my, boolean open) {
        field(g, r, r.has(mx, my), open);
        g.drawString(this.font, fit(text, r.w() - 22), r.x() + 6, r.y() + (ROW_H - 8) / 2, COL_TEXT, false);
        caret(g, r);
    }

    /** A small down-pointing triangle, drawn rather than relying on a font glyph. */
    private void caret(GuiGraphics g, Rect r) {
        int cx = r.r() - 10;
        int cy = r.y() + ROW_H / 2 - 1;
        for (int i = 0; i < 3; i++) g.fill(cx - 2 + i, cy + i, cx + 3 - i, cy + i + 1, COL_MUTED);
    }

    private void segmented(GuiGraphics g, Rect r, int mx, int my) {
        int half = r.w() / 2;
        Rect left = new Rect(r.x(), r.y(), half, r.h());
        Rect right = new Rect(r.x() + half, r.y(), r.w() - half, r.h());
        segment(g, left, I18n.get("gui.tacz_sewv.tdt.logo_source.library"), !webView, left.has(mx, my));
        segment(g, right, I18n.get("gui.tacz_sewv.tdt.logo_source.web"), webView, right.has(mx, my));
    }

    private void segment(GuiGraphics g, Rect r, String text, boolean on, boolean hover) {
        if (on) {
            g.fill(r.x(), r.y(), r.r(), r.b(), COL_ACCENT);
        } else {
            g.fill(r.x(), r.y(), r.r(), r.b(), hover ? COL_FIELD_HOVER : COL_FIELD);
            outline(g, r, hover ? COL_LINE_HI : COL_LINE);
        }
        String s = fit(text, r.w() - 8);
        g.drawString(this.font, s, r.x() + (r.w() - this.font.width(s)) / 2, r.y() + (ROW_H - 8) / 2,
                on ? COL_ON_ACCENT : COL_MUTED, false);
    }

    private void drawButton(GuiGraphics g, Rect r, String text, boolean primary, boolean enabled, boolean hover) {
        int fg;
        if (!enabled) {
            g.fill(r.x(), r.y(), r.r(), r.b(), COL_FIELD);
            outline(g, r, COL_LINE);
            fg = COL_FAINT;
        } else if (primary) {
            g.fill(r.x(), r.y(), r.r(), r.b(), hover ? COL_ACCENT_HI : COL_ACCENT);
            fg = COL_ON_ACCENT;
        } else {
            g.fill(r.x(), r.y(), r.r(), r.b(), hover ? COL_FIELD_HOVER : COL_FIELD);
            outline(g, r, hover ? COL_MUTED : COL_LINE_HI);
            fg = COL_TEXT;
        }
        String s = fit(text, r.w() - 6);
        g.drawString(this.font, s, r.x() + (r.w() - this.font.width(s)) / 2, r.y() + (ROW_H - 8) / 2, fg, false);
    }

    private void popFrame(GuiGraphics g, Rect r) {
        g.fill(r.x(), r.y(), r.r(), r.b(), COL_BG);
        outline(g, r, COL_LINE_HI);
    }

    private static void outline(GuiGraphics g, Rect r, int c) {
        g.fill(r.x(), r.y(), r.r(), r.y() + 1, c);
        g.fill(r.x(), r.b() - 1, r.r(), r.b(), c);
        g.fill(r.x(), r.y(), r.x() + 1, r.b(), c);
        g.fill(r.r() - 1, r.y(), r.r(), r.b(), c);
    }

    /** A logo on a light plate, so dark ink on transparency stays readable. */
    private void logo(GuiGraphics g, String pool, String icon, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, COL_PLATE);
        ResourceLocation tex = LogoPoolRegistry.texture(pool, icon);
        if (tex == null) tex = LogoPoolRegistry.defaultTexture();
        if (tex == null) return;
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(tex, x, y, 0, 0, size, size, size, size);
    }

    private String fit(String s, int w) {
        if (this.font.width(s) <= w) return s;
        return this.font.plainSubstrByWidth(s, Math.max(0, w - this.font.width("..."))) + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
