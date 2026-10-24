package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;

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

import com.neoalive.tacz_sewv.client.gui.GuiFit;
import com.neoalive.tacz_sewv.client.skin.LogoPoolRegistry;
import com.neoalive.tacz_sewv.crew.NamePools;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketApplyPmcIdentity;
import com.neoalive.tacz_sewv.network.PacketRequestPmcIdentity;
import com.neoalive.tacz_sewv.network.PacketSetNameCategory;

/**
 * Tactical Data Terminal: the player's PMC identity — unit name pool, company name and helmet logo.
 * Orders moved to the Quick Wheel (held key); carrying the terminal is still what enables it.
 * A centred modal with five fixed rows; nothing scrolls.
 */
public class TdtScreen extends Screen {

    private static final float PANEL_WIDTH_FRAC = 0.45f;
    private static final int PANEL_W_MIN = 280;
    private static final int PANEL_W_MAX = 380;
    private static final int PAD = 10;
    private static final int HEADER_H = 24;
    private static final int CELL_H = 22;
    private static final int CELL_GAP = 4;
    private static final int ROWS = 5;
    private static final int STEP_BTN = 18;
    private static final int FLOAT_ICON = 22;
    private static final int FOOTER_BTN_W = 52;

    private static final int ROW_NAMES = 0;
    private static final int ROW_COMPANY = 1;
    private static final int ROW_POOL = 2;
    private static final int ROW_ICON = 3;
    private static final int ROW_FOOTER = 4;

    // C2 palette — single accent; fills stay neutral.
    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;

    private static String selectedNameCategory = NamePools.RANDOM;

    private static String draftCompanyName = "";
    private static String draftLogoPool = PmcIdentityPreference.DEFAULT_POOL;
    private static String draftLogoId = PmcIdentityPreference.DEFAULT_LOGO;
    private static String savedCompanyName = "";
    private static String savedLogoPool = PmcIdentityPreference.DEFAULT_POOL;
    private static String savedLogoId = PmcIdentityPreference.DEFAULT_LOGO;

    /** Last server-synced logo pool — used by helmet DogTag compositing. */
    public static String savedLogoPool() {
        return savedLogoPool;
    }

    /** Last server-synced logo id — used by helmet DogTag compositing. */
    public static String savedLogoId() {
        return savedLogoId;
    }

    private int panelW;
    private int panelLeft;
    private int panelTop;
    private int rowsTop;
    @Nullable
    private EditBox companyField;
    /** Logo picker flyout open, anchored at the click. */
    private boolean logoFloat;
    private float floatAnim;
    private int floatX;
    private int floatY;
    @Nullable
    private String pendingTip;

    /** Server snapshot applied — refreshes draft fields and the company EditBox. */
    public static void receiveIdentitySync(String company, String pool, String logo, String nameCategory) {
        savedCompanyName = company;
        savedLogoPool = pool;
        savedLogoId = logo;
        draftCompanyName = company;
        draftLogoPool = pool;
        draftLogoId = logo;
        selectedNameCategory = nameCategory;
    }

    public void onIdentitySynced() {
        if (this.companyField != null) {
            this.companyField.setValue(draftCompanyName);
        }
    }

    private TdtScreen() {
        super(Component.translatable("gui.tacz_sewv.tdt.title"));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        mc.setScreen(new TdtScreen());
    }

    @Override
    protected void init() {
        this.panelW = GuiFit.panelW(this.width, PANEL_WIDTH_FRAC, PANEL_W_MIN, PANEL_W_MAX);
        int panelH = HEADER_H + ROWS * (CELL_H + CELL_GAP) - CELL_GAP + PAD;
        this.panelLeft = (this.width - this.panelW) / 2;
        this.panelTop = (this.height - panelH) / 2;
        this.rowsTop = this.panelTop + HEADER_H;
        this.logoFloat = false;

        int labelW = this.font.width(I18n.get("gui.tacz_sewv.tdt.pmc_company"));
        int fieldX = rowLeft() + PAD + labelW + 6;
        this.companyField = new EditBox(this.font, fieldX, rowY(ROW_COMPANY) + (CELL_H - 16) / 2,
                Math.max(40, rowRight() - PAD - fieldX), 16,
                Component.translatable("gui.tacz_sewv.tdt.pmc_company"));
        this.companyField.setMaxLength(30);
        this.companyField.setValue(draftCompanyName);
        this.companyField.setResponder(val -> draftCompanyName = val);
        addRenderableWidget(this.companyField);

        NetworkHandler.CHANNEL.sendToServer(new PacketRequestPmcIdentity());
    }

    private int rowLeft() {
        return this.panelLeft + PAD;
    }

    private int rowRight() {
        return this.panelLeft + this.panelW - PAD;
    }

    private int rowY(int row) {
        return this.rowsTop + row * (CELL_H + CELL_GAP);
    }

    // --- Identity state ------------------------------------------------------

    /** Cycle values for the name pool: every configured category, plus RANDOM. */
    private static List<String> nameCategoryOptions() {
        List<String> options = new ArrayList<>(NamePools.active().categoryKeys());
        options.add(NamePools.RANDOM);
        return options;
    }

    /** A sticky preference, not a fire-once order: every step applies at once. */
    private static void stepNameCategory(int dir) {
        List<String> options = nameCategoryOptions();
        int idx = options.indexOf(selectedNameCategory);
        if (idx < 0) idx = options.indexOf(NamePools.RANDOM);
        selectedNameCategory = options.get(Math.floorMod(idx + dir, options.size()));
        NetworkHandler.CHANNEL.sendToServer(new PacketSetNameCategory(selectedNameCategory));
    }

    private static List<String> logoPoolOptions() {
        List<String> pools = new ArrayList<>();
        for (String id : LogoPoolRegistry.poolIds()) {
            if (id.startsWith("pmc_")) {
                pools.add(id);
            }
        }
        return pools.isEmpty() ? List.of(PmcIdentityPreference.DEFAULT_POOL) : pools;
    }

    private static void stepLogoPool(int dir) {
        List<String> options = logoPoolOptions();
        int idx = Math.max(0, options.indexOf(draftLogoPool));
        draftLogoPool = options.get(Math.floorMod(idx + dir, options.size()));
        List<String> icons = LogoPoolRegistry.iconsIn(draftLogoPool);
        if (!icons.contains(draftLogoId) && !icons.isEmpty()) {
            draftLogoId = icons.get(0);
        }
    }

    private static boolean identityDirty() {
        return !draftCompanyName.equals(savedCompanyName)
                || !draftLogoPool.equals(savedLogoPool)
                || !draftLogoId.equals(savedLogoId);
    }

    private void applyIdentityDraft() {
        if (this.companyField != null) {
            draftCompanyName = this.companyField.getValue().strip();
        }
        NetworkHandler.CHANNEL.sendToServer(
                new PacketApplyPmcIdentity(draftCompanyName, draftLogoPool, draftLogoId));
    }

    private void resetIdentityDraft() {
        draftCompanyName = "";
        draftLogoPool = PmcIdentityPreference.DEFAULT_POOL;
        draftLogoId = PmcIdentityPreference.DEFAULT_LOGO;
        if (this.companyField != null) {
            this.companyField.setValue(draftCompanyName);
        }
    }

    // --- Input ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // The flyout sits above the company EditBox — handle it first or the field steals clicks
        // through the popup.
        if (this.logoFloat) {
            if (clickLogoFloat(mouseX, mouseY)) clickSound();
            this.logoFloat = false;
            return true;
        }
        if (this.companyField != null && this.companyField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(this.companyField);
            return true;
        }
        if (this.companyField != null) {
            this.companyField.setFocused(false);
        }
        if (clickRows(mouseX, mouseY)) {
            clickSound();
        }
        return true;
    }

    private boolean clickRows(double mx, double my) {
        if (mx < rowLeft() || mx >= rowRight()) return false;
        for (int row = 0; row < ROWS; row++) {
            int y = rowY(row);
            if (my < y || my >= y + CELL_H) continue;
            int relX = (int) mx - rowLeft();
            int w = rowRight() - rowLeft();
            return switch (row) {
                case ROW_NAMES -> clickCycle(relX, w, selectedNameCategory, TdtScreen::stepNameCategory);
                case ROW_POOL -> clickCycle(relX, w, draftLogoPool, TdtScreen::stepLogoPool);
                case ROW_ICON -> {
                    this.logoFloat = true;
                    this.floatX = (int) mx;
                    this.floatY = (int) my;
                    this.floatAnim = 0.0F;
                    yield true;
                }
                case ROW_FOOTER -> clickFooter(relX, w);
                default -> false;
            };
        }
        return false;
    }

    private boolean clickCycle(int relX, int w, String value, java.util.function.IntConsumer step) {
        int nextLeft = w - STEP_BTN;
        int prevLeft = nextLeft - 4 - this.font.width(value) - 4 - STEP_BTN;
        if (relX >= nextLeft) {
            step.accept(1);
            return true;
        }
        if (relX >= prevLeft && relX < prevLeft + STEP_BTN) {
            step.accept(-1);
            return true;
        }
        return false;
    }

    private boolean clickFooter(int relX, int w) {
        int resetLeft = w - FOOTER_BTN_W;
        int applyLeft = resetLeft - 6 - FOOTER_BTN_W;
        if (relX >= applyLeft && relX < applyLeft + FOOTER_BTN_W && identityDirty()) {
            applyIdentityDraft();
            return true;
        }
        if (relX >= resetLeft) {
            resetIdentityDraft();
            return true;
        }
        return false;
    }

    private boolean clickLogoFloat(double mx, double my) {
        List<String> icons = LogoPoolRegistry.iconsIn(draftLogoPool);
        int[] box = logoFloatBox(icons.size());
        if (box == null || mx < box[0] || mx > box[0] + box[2] || my < box[1] || my > box[1] + box[3]) {
            return false;
        }
        int pad = 6;
        int ix = box[0] + pad;
        int iy = box[1] + pad + 16;
        for (String icon : icons) {
            if (mx >= ix && mx < ix + FLOAT_ICON && my >= iy && my < iy + FLOAT_ICON) {
                draftLogoId = icon;
                return true;
            }
            ix += FLOAT_ICON + 4;
            if (ix + FLOAT_ICON > box[0] + box[2] - pad) {
                ix = box[0] + pad;
                iy += FLOAT_ICON + 4;
            }
        }
        return false;
    }

    /** {x, y, w, h} of the logo picker for {@code count} icons, or null when there are none. */
    @Nullable
    private int[] logoFloatBox(int count) {
        if (count <= 0) return null;
        int cols = Math.min(count, 5);
        int rows = (count + cols - 1) / cols;
        int pad = 6;
        int fw = Math.max(cols * (FLOAT_ICON + 4) + pad * 2, 90);
        int fh = (1 + rows) * (FLOAT_ICON + 4) + pad * 2;
        int fx = Mth.clamp(this.floatX - fw / 2, 4, this.width - fw - 4);
        int fy = Mth.clamp(this.floatY - fh, 4, this.height - fh - 4);
        return new int[]{fx, fy, fw, fh};
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.logoFloat && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.logoFloat = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Render --------------------------------------------------------------

    /**
     * NeoForge port: 1.21 {@code Screen.render} draws the background itself, after this screen's
     * panel has been drawn; {@link #render} draws it first instead, exactly once (a second blur throws).
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        this.floatAnim = Math.min(1.0F, this.floatAnim + partialTick * 0.25F);
        this.pendingTip = null;

        int panelBottom = rowY(ROWS - 1) + CELL_H + PAD;
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelW, panelBottom, COL_BASE);
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelTop + 1, COL_ACCENT);
        g.drawString(this.font, this.title, this.panelLeft + PAD, this.panelTop + 8, COL_TEXT, false);

        for (int row = 0; row < ROWS; row++) {
            renderRow(g, row, mouseX, mouseY);
        }
        if (this.companyField != null) {
            this.companyField.setVisible(!this.logoFloat);
        }
        super.render(g, mouseX, mouseY, partialTick);

        // Last, so neither the EditBox nor the steppers can paint over it.
        if (this.logoFloat) {
            renderLogoFloat(g, mouseX, mouseY);
        } else if (this.pendingTip != null) {
            g.renderTooltip(this.font, Component.translatable(this.pendingTip), mouseX, mouseY);
        }
    }

    private void renderRow(GuiGraphics g, int row, int mouseX, int mouseY) {
        int x = rowLeft();
        int right = rowRight();
        int y = rowY(row);
        boolean hover = !this.logoFloat && mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + CELL_H;
        int textY = y + (CELL_H - 8) / 2;

        if (row == ROW_FOOTER) {
            int resetLeft = right - FOOTER_BTN_W;
            int applyLeft = resetLeft - 6 - FOOTER_BTN_W;
            g.fill(applyLeft, y + 2, applyLeft + FOOTER_BTN_W, y + CELL_H - 2,
                    identityDirty() ? COL_ACCENT : COL_MUTED);
            g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.tdt.identity_apply"),
                    applyLeft + FOOTER_BTN_W / 2, textY, COL_BASE);
            g.fill(resetLeft, y + 2, right, y + CELL_H - 2, COL_SURFACE);
            g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.tdt.identity_reset"),
                    resetLeft + FOOTER_BTN_W / 2, textY, COL_TEXT);
            return;
        }

        g.fill(x, y, right, y + CELL_H, hover ? COL_HOVER : COL_SURFACE);
        g.fill(x, y + CELL_H - 1, right, y + CELL_H, COL_BORDER);
        int textX = x + PAD;
        switch (row) {
            case ROW_NAMES -> {
                g.drawString(this.font, Component.translatable("gui.tacz_sewv.tdt.full_names"), textX, textY,
                        COL_TEXT, false);
                drawCycle(g, right, y, selectedNameCategory, hover);
                if (hover) this.pendingTip = "gui.tacz_sewv.tdt.full_names.tip";
            }
            case ROW_COMPANY -> {
                g.drawString(this.font, Component.translatable("gui.tacz_sewv.tdt.pmc_company"), textX, textY,
                        COL_TEXT, false);
                if (hover) this.pendingTip = "gui.tacz_sewv.tdt.pmc_company.tip";
            }
            case ROW_POOL -> {
                g.drawString(this.font, Component.translatable("gui.tacz_sewv.tdt.logo_pool"), textX, textY,
                        COL_TEXT, false);
                drawCycle(g, right, y, draftLogoPool, hover);
            }
            case ROW_ICON -> {
                g.drawString(this.font, Component.translatable("gui.tacz_sewv.tdt.logo_icon"), textX, textY,
                        COL_TEXT, false);
                int iconX = right - PAD - FLOAT_ICON;
                drawLogoIcon(g, draftLogoPool, draftLogoId, iconX, y + (CELL_H - FLOAT_ICON) / 2, true);
                g.drawString(this.font, draftLogoId, iconX - 4 - this.font.width(draftLogoId), textY,
                        COL_MUTED, false);
                if (hover) this.pendingTip = "gui.tacz_sewv.tdt.logo_icon.tip";
            }
            default -> {
            }
        }
    }

    /** "< value >" stepper flush right in a row. */
    private void drawCycle(GuiGraphics g, int right, int y, String value, boolean hover) {
        int nextLeft = right - STEP_BTN;
        int valueW = this.font.width(value);
        int prevLeft = nextLeft - 4 - valueW - 4 - STEP_BTN;
        drawStepBtn(g, prevLeft, y + 2, "<", hover);
        g.drawCenteredString(this.font, value, prevLeft + STEP_BTN + 4 + valueW / 2, y + (CELL_H - 8) / 2,
                COL_MUTED);
        drawStepBtn(g, nextLeft, y + 2, ">", hover);
    }

    private void drawStepBtn(GuiGraphics g, int x, int y, String glyph, boolean parentHover) {
        g.fill(x, y, x + STEP_BTN, y + CELL_H - 4, parentHover ? COL_HOVER : COL_SURFACE);
        g.fill(x, y, x + STEP_BTN, y + 1, COL_BORDER);
        g.fill(x, y + CELL_H - 5, x + STEP_BTN, y + CELL_H - 4, COL_BORDER);
        g.drawCenteredString(this.font, glyph, x + STEP_BTN / 2, y + (CELL_H - 4 - 8) / 2, COL_ACCENT);
    }

    private void drawLogoIcon(GuiGraphics g, String pool, String icon, int x, int y, boolean selected) {
        ResourceLocation tex = LogoPoolRegistry.texture(pool, icon);
        if (tex == null) {
            g.fill(x, y, x + FLOAT_ICON, y + FLOAT_ICON, COL_MUTED);
            return;
        }
        if (selected) {
            g.fill(x - 1, y - 1, x + FLOAT_ICON + 1, y + FLOAT_ICON + 1, COL_ACCENT);
        }
        // Light plate so black-on-transparent logos stay readable on the dark panel.
        g.fill(x, y, x + FLOAT_ICON, y + FLOAT_ICON, COL_TEXT);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(tex, x, y, 0, 0, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON);
    }

    private void renderLogoFloat(GuiGraphics g, int mouseX, int mouseY) {
        List<String> icons = LogoPoolRegistry.iconsIn(draftLogoPool);
        int[] box = logoFloatBox(icons.size());
        if (box == null) return;
        int pad = 6;
        int fx = box[0];
        int fw = box[2];
        int fh = box[3];
        int fy = box[1] - (int) ((1.0F - this.floatAnim) * 12.0F);
        // Opaque — a translucent plate let the rows read through the picker and look like clipping.
        g.fill(fx, fy, fx + fw, fy + fh, COL_BASE);
        g.fill(fx, fy, fx + fw, fy + 1, COL_ACCENT);
        g.fill(fx, fy, fx + 1, fy + fh, COL_BORDER);
        g.fill(fx + fw - 1, fy, fx + fw, fy + fh, COL_BORDER);
        g.fill(fx, fy + fh - 1, fx + fw, fy + fh, COL_BORDER);
        g.drawString(this.font, I18n.get("gui.tacz_sewv.tdt.logo_pick"), fx + pad, fy + pad, COL_ACCENT, false);

        int ix = fx + pad;
        int iy = fy + pad + 16;
        for (String icon : icons) {
            boolean hover = mouseX >= ix && mouseX < ix + FLOAT_ICON && mouseY >= iy && mouseY < iy + FLOAT_ICON;
            drawLogoIcon(g, draftLogoPool, icon, ix, iy, icon.equals(draftLogoId) || hover);
            if (hover) {
                g.drawString(this.font, icon, fx + pad, fy + fh - 12, COL_MUTED, false);
            }
            ix += FLOAT_ICON + 4;
            if (ix + FLOAT_ICON > fx + fw - pad) {
                ix = fx + pad;
                iy += FLOAT_ICON + 4;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
