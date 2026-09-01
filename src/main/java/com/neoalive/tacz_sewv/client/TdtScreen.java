package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.crew.NamePools;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketExitPlatoon;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketReachGuard;
import com.neoalive.tacz_sewv.network.PacketSetNameCategory;
import com.neoalive.tacz_sewv.network.PacketToggleAutoOrders;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/**
 * Tactical Data Terminal: left-docked C2 overlay (no world dim) with a category-filtered
 * order grid and unit-selection ribbon. Cruise and Sweep stay on the world map only.
 */
public class TdtScreen extends Screen {

    private static final EnumMap<VehicleMarker.Kind, ResourceLocation> KIND_TEX = new EnumMap<>(VehicleMarker.Kind.class);

    static {
        for (VehicleMarker.Kind kind : VehicleMarker.Kind.values()) {
            KIND_TEX.put(kind, new ResourceLocation(TaczSewv.MODID,
                    "textures/map/xaeros_icon_" + kind.textureName() + ".png"));
        }
    }

    // Docked left ~40% of screen; clamped so a 2-col grid stays readable. Widened slightly (was
    // 0.38f/400) to fit the Identity category's "Full Names" cycle row (category name + arrows).
    private static final float PANEL_WIDTH_FRAC = 0.40f;
    private static final int PANEL_W_MIN = 260;
    private static final int PANEL_W_MAX = 440;
    private static final int PAD = 10;
    private static final int TAB_H = 18;
    private static final int TAB_GAP = 10;
    private static final int CELL_H = 22;
    private static final int CELL_GAP = 4;
    private static final int GRID_COLS = 2;
    private static final int STRIPE_W = 2;
    private static final int STEP_BTN = 18;
    private static final int SCROLLBAR_W = 5;
    private static final int RIBBON_H = 54;
    private static final int ICON_SIZE = 24;
    private static final int FLOAT_ICON = 22;
    /** All/None select buttons, top ribbon row. */
    private static final int BTN_W = 40;
    private static final int BTN_H = 14;
    /** Where the icon row starts, clear of the stacked All/None/Live-Sel button column. */
    private static final int RIBBON_ICONS_X = BTN_W * 2 + 2 + 6;

    private static final int RADIUS_STEP = 16;
    private static final int DEFAULT_RADIUS = 256;
    private static final int RADIUS_FLOOR = RADIUS_STEP;
    private static final int ALT_STEP = 5;

    // C2 palette — single accent; fills stay neutral.
    private static final int COL_BASE = 0xFF12161C;
    private static final int COL_SURFACE = 0xFF1B222B;
    private static final int COL_HOVER = 0xFF232D38;
    private static final int COL_BORDER = 0xFF2E3946;
    private static final int COL_TEXT = 0xFFE8ECF0;
    private static final int COL_MUTED = 0xFF8B98A5;
    private static final int COL_ACCENT = 0xFF4FD1C5;
    private static final int COL_STRIPE = 0x404FD1C5; // accent @ ~25%

    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 8;
    private static final int GROUP_STRIPE_W = 3;

    // Muted category block stripes (~30% alpha) — All-view grouping only.
    private static final int STRIPE_ORDERS = 0x4D7C8CD9;
    private static final int STRIPE_CREW = 0x4D8FAA6B;
    private static final int STRIPE_AREA = 0x4DC9A15A;
    private static final int STRIPE_AIR = 0x4DB57ED1;
    private static final int STRIPE_FORM = 0x4DD98F6B;
    private static final int STRIPE_PLATOON = 0x4DD9C96B;
    private static final int STRIPE_IDENTITY = 0x4D6BD9A1;

    enum Category {
        ALL, ORDERS, CREW, AREA, AIR, FORM, PLATOON, IDENTITY
    }

    private enum StepperKind { PATROL, SEARCH, ALTITUDE, LINE }

    private enum CycleKind { NAME_CATEGORY }

    @Nullable
    private final Entity boardTarget;
    @Nullable
    private final BlockPos landPad;
    private final int formationAxis;

    private static int patrolRadius = DEFAULT_RADIUS;
    private static int searchRadius = DEFAULT_RADIUS;
    private static int heliAltitude = IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
    private static int lineRowSize = PacketVehicleFormation.DEFAULT_ROW_SIZE;
    private static String selectedNameCategory = NamePools.RANDOM;

    public static int patrolRadius() {
        return patrolRadius;
    }

    public static int searchRadius() {
        return searchRadius;
    }

    public static int heliAltitude() {
        return heliAltitude;
    }

    private Category category = Category.ALL;
    private int scroll;
    private boolean draggingScroll;

    private int panelW;
    private int panelLeft;
    private int panelTop;
    private int panelBottom;
    private int listTop;
    private int listBottom;
    private int listHeight;
    private int contentHeight;
    private int contentInnerW;
    private int ribbonTop;

    private final List<OrderEntry> catalog = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();

    @Nullable
    private VehicleMarker.Kind floatKind;
    /** Set instead of {@code floatKind} when the expanded flyout is a platoon group, not a Kind bucket. */
    @Nullable
    private Integer floatPlatoonColor;
    private float floatAnim;
    private int floatX;
    private int floatY;
    private final EnumMap<VehicleMarker.Kind, Float> ribbonAlpha = new EnumMap<>(VehicleMarker.Kind.class);
    private final Map<Integer, Float> platoonRibbonAlpha = new HashMap<>();
    @Nullable
    private String pendingTip;

    private record OrderEntry(Category cat, String labelKey, @Nullable String tipKey,
                              boolean closes, Runnable action, @Nullable StepperKind stepper,
                              @Nullable CycleKind cycle) {}

    /** Content-space rect for one order (grid cell or full-width stepper row). */
    private record Cell(OrderEntry entry, int x, int y, int w, int h) {}

    /** Painted category block in All view (header + continuous left stripe). */
    private record Section(Category cat, int y0, int y1) {}

    private TdtScreen(@Nullable Entity boardTarget, @Nullable BlockPos landPad, int formationAxis) {
        super(Component.translatable("gui.tacz_sewv.tdt.title"));
        this.boardTarget = boardTarget;
        this.landPad = landPad;
        this.formationAxis = formationAxis;
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Entity target = mc.hitResult instanceof EntityHitResult ehr
                && (ehr.getEntity() instanceof MortarEntity || ehr.getEntity() instanceof VehicleEntity)
                ? ehr.getEntity() : null;

        HitResult block = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
        BlockPos pad = block instanceof BlockHitResult bhr && block.getType() == HitResult.Type.BLOCK
                ? bhr.getBlockPos() : null;

        int axis = IFormationMember.axisOf(Direction.fromYRot(mc.player.getYRot()));
        mc.setScreen(new TdtScreen(target, pad, axis));
    }

    @Override
    protected void init() {
        TdtSelection.scan();
        buildCatalog();

        this.panelW = Mth.clamp((int) (this.width * PANEL_WIDTH_FRAC), PANEL_W_MIN, PANEL_W_MAX);
        this.panelLeft = 0;
        this.panelTop = 0;
        this.panelBottom = this.height;
        this.ribbonTop = this.panelBottom - RIBBON_H;
        this.listTop = PAD + 14 + TAB_H + 10; // title + tabs
        this.listBottom = this.ribbonTop - 6;
        this.listHeight = Math.max(40, this.listBottom - this.listTop);
        this.contentInnerW = this.panelW - PAD * 2 - SCROLLBAR_W - 4;

        rebuildCells();
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
        clearFloat();
    }

    private void buildCatalog() {
        this.catalog.clear();
        add(Category.ORDERS, "gui.tacz_sewv.tdt.hold", null, true, () -> issueSemOrder(OrderType.HOLD_POSITION));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.follow", null, true, () -> issueSemOrder(OrderType.FOLLOW_COMMANDER));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.move_to", "gui.tacz_sewv.tdt.move_to.tip", false, this::armMoveTo);
        add(Category.ORDERS, "gui.tacz_sewv.tdt.attack", "gui.tacz_sewv.tdt.attack.tip", false, this::armAttack);
        add(Category.ORDERS, "gui.tacz_sewv.tdt.cease_fire", null, true, () -> issueSemOrder(OrderType.CEASE_FIRE));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.free_fire", null, true, () -> issueSemOrder(OrderType.FREE_FIRE));

        add(Category.CREW, "gui.tacz_sewv.tdt.board", "gui.tacz_sewv.tdt.board.tip", true,
                () -> BoardKeybind.orderBoard(liveBoardTarget(), false));
        add(Category.CREW, "gui.tacz_sewv.tdt.board_passenger", "gui.tacz_sewv.tdt.board_passenger.tip", true,
                () -> BoardKeybind.orderBoard(liveBoardTarget(), true));
        add(Category.CREW, "gui.tacz_sewv.tdt.dismount", null, true, BoardKeybind::orderDismount);
        add(Category.CREW, "gui.tacz_sewv.tdt.escort", "gui.tacz_sewv.tdt.escort.tip", true, ClientEvents::armEscort);
        add(Category.CREW, "gui.tacz_sewv.tdt.tow", "gui.tacz_sewv.tdt.tow.tip", true, ClientEvents::armTowRecovery);
        add(Category.CREW, "gui.tacz_sewv.tdt.set_guard", "gui.tacz_sewv.tdt.set_guard.tip", true, ClientEvents::armGuardPosition);
        add(Category.CREW, "gui.tacz_sewv.tdt.reach_guard", "gui.tacz_sewv.tdt.reach_guard.tip", true, this::orderReachGuard);
        add(Category.CREW, "gui.tacz_sewv.tdt.capture_medic", "gui.tacz_sewv.tdt.capture_medic.tip", true,
                BoardKeybind::orderCaptureMedic);

        add(Category.AREA, "gui.tacz_sewv.tdt.patrol", "gui.tacz_sewv.tdt.patrol.tip", false,
                () -> orderAreaTask(patrolRadius, IVehiclePatrol.MODE_PATROL), StepperKind.PATROL);
        add(Category.AREA, "gui.tacz_sewv.tdt.search", "gui.tacz_sewv.tdt.search.tip", false,
                () -> orderAreaTask(searchRadius, IVehiclePatrol.MODE_SEARCH), StepperKind.SEARCH);
        add(Category.AREA, "gui.tacz_sewv.tdt.entrench", "gui.tacz_sewv.tdt.entrench.tip", true,
                ClientEvents::armEntrench);
        add(Category.AREA, "gui.tacz_sewv.tdt.dismiss", "gui.tacz_sewv.tdt.dismiss.tip", true,
                () -> BoardKeybind.orderAreaTask(0, PacketPatrolVehicle.MODE_DISMISS));

        add(Category.AIR, "gui.tacz_sewv.tdt.takeoff", "gui.tacz_sewv.tdt.altitude.tip", true,
                () -> HelicopterKeybind.orderTakeoff(heliAltitude), StepperKind.ALTITUDE);
        add(Category.AIR, "gui.tacz_sewv.tdt.land", "gui.tacz_sewv.tdt.land.tip", true,
                () -> HelicopterKeybind.orderLand(liveLandPad()));
        add(Category.AIR, "gui.tacz_sewv.tdt.emergency_land", "gui.tacz_sewv.tdt.emergency_land.tip",
                true, HelicopterKeybind::orderEmergencyLand);
        add(Category.AIR, "gui.tacz_sewv.tdt.rappel", "gui.tacz_sewv.tdt.rappel.tip", true, HelicopterKeybind::orderRappel);

        add(Category.FORM, "gui.tacz_sewv.tdt.sem_wedge", null, true, () -> issueSemOrder(OrderType.FORM_WEDGE));
        add(Category.FORM, "gui.tacz_sewv.tdt.sem_column", null, true, () -> issueSemOrder(OrderType.FORM_COLUMN));
        add(Category.FORM, "gui.tacz_sewv.tdt.wedge", null, true,
                () -> BoardKeybind.orderFormation(FormationShape.WEDGE, this.formationAxis, lineRowSize));
        add(Category.FORM, "gui.tacz_sewv.tdt.column", null, true,
                () -> BoardKeybind.orderFormation(FormationShape.COLUMN, this.formationAxis, lineRowSize));
        add(Category.FORM, "gui.tacz_sewv.tdt.line", "gui.tacz_sewv.tdt.line.tip", true,
                () -> BoardKeybind.orderFormation(FormationShape.LINE, this.formationAxis, lineRowSize), StepperKind.LINE);
        add(Category.FORM, "gui.tacz_sewv.tdt.echelon_left", null, true,
                () -> BoardKeybind.orderFormation(FormationShape.ECHELON_LEFT, this.formationAxis, lineRowSize));
        add(Category.FORM, "gui.tacz_sewv.tdt.echelon_right", null, true,
                () -> BoardKeybind.orderFormation(FormationShape.ECHELON_RIGHT, this.formationAxis, lineRowSize));

        add(Category.PLATOON, "gui.tacz_sewv.tdt.join_platoon", "gui.tacz_sewv.tdt.join_platoon.tip",
                true, ClientEvents::armJoinPlatoon);
        add(Category.PLATOON, "gui.tacz_sewv.tdt.exit_platoon", "gui.tacz_sewv.tdt.exit_platoon.tip",
                true, this::orderExitPlatoon);
        add(Category.PLATOON, "gui.tacz_sewv.tdt.toggle_auto_orders", "gui.tacz_sewv.tdt.toggle_auto_orders.tip",
                true, this::orderToggleAutoOrders);

        add(Category.IDENTITY, "gui.tacz_sewv.tdt.full_names", "gui.tacz_sewv.tdt.full_names.tip",
                false, this::applyNameCategory, CycleKind.NAME_CATEGORY);
    }

    /** Cycle values for {@link CycleKind#NAME_CATEGORY}: every configured category, plus RANDOM. */
    private static List<String> nameCategoryOptions() {
        List<String> options = new ArrayList<>(NamePools.active().categoryKeys());
        options.add(NamePools.RANDOM);
        return options;
    }

    private void applyNameCategory() {
        NetworkHandler.CHANNEL.sendToServer(new PacketSetNameCategory(selectedNameCategory));
    }

    private static void stepNameCategory(int dir) {
        List<String> options = nameCategoryOptions();
        int idx = options.indexOf(selectedNameCategory);
        if (idx < 0) idx = options.indexOf(NamePools.RANDOM);
        idx = Math.floorMod(idx + dir, options.size());
        selectedNameCategory = options.get(idx);
    }

    private void add(Category cat, String label, @Nullable String tip, boolean closes, Runnable action) {
        add(cat, label, tip, closes, action, (StepperKind) null);
    }

    private void add(Category cat, String label, @Nullable String tip, boolean closes,
                     Runnable action, @Nullable StepperKind stepper) {
        this.catalog.add(new OrderEntry(cat, label, tip, closes, action, stepper, null));
    }

    private void add(Category cat, String label, @Nullable String tip, boolean closes,
                     Runnable action, CycleKind cycle) {
        this.catalog.add(new OrderEntry(cat, label, tip, closes, action, null, cycle));
    }

    private void rebuildCells() {
        this.cells.clear();
        this.sections.clear();
        boolean allView = this.category == Category.ALL;
        int y = 0;
        int inset = allView ? GROUP_STRIPE_W + 2 : 0;
        int innerW = this.contentInnerW - inset;
        int cellW = (innerW - CELL_GAP * (GRID_COLS - 1)) / GRID_COLS;

        Category[] order = allView
                ? new Category[]{Category.ORDERS, Category.CREW, Category.AREA, Category.AIR, Category.FORM,
                    Category.PLATOON, Category.IDENTITY}
                : new Category[]{this.category};

        for (Category cat : order) {
            if (cat == Category.ALL) continue;
            List<OrderEntry> group = new ArrayList<>();
            for (OrderEntry e : this.catalog) {
                if (e.cat == cat) group.add(e);
            }
            if (group.isEmpty()) continue;

            int sectionTop = y;
            if (allView) {
                y += HEADER_H;
            }

            int col = 0;
            for (OrderEntry e : group) {
                if (e.stepper != null || e.cycle != null) {
                    if (col != 0) {
                        y += CELL_H + CELL_GAP;
                        col = 0;
                    }
                    this.cells.add(new Cell(e, inset, y, innerW, CELL_H));
                    y += CELL_H + CELL_GAP;
                    continue;
                }
                int x = inset + col * (cellW + CELL_GAP);
                this.cells.add(new Cell(e, x, y, cellW, CELL_H));
                col++;
                if (col >= GRID_COLS) {
                    col = 0;
                    y += CELL_H + CELL_GAP;
                }
            }
            if (col != 0) {
                y += CELL_H + CELL_GAP;
            }
            // Drop trailing cell gap inside the section.
            int sectionBottom = Math.max(sectionTop + (allView ? HEADER_H : 0), y - CELL_GAP);
            if (allView) {
                this.sections.add(new Section(cat, sectionTop, sectionBottom));
                y = sectionBottom + SECTION_GAP;
            } else {
                y = sectionBottom;
            }
        }
        this.contentHeight = Math.max(y - (allView ? SECTION_GAP : 0), 0);
    }

    private static int stripeFor(Category cat) {
        return switch (cat) {
            case ORDERS -> STRIPE_ORDERS;
            case CREW -> STRIPE_CREW;
            case AREA -> STRIPE_AREA;
            case AIR -> STRIPE_AIR;
            case FORM -> STRIPE_FORM;
            case PLATOON -> STRIPE_PLATOON;
            case IDENTITY -> STRIPE_IDENTITY;
            default -> COL_STRIPE;
        };
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - this.listHeight);
    }

    // --- SEM / area helpers -------------------------------------------------

    private void issueSemOrder(OrderType order) {
        if (!ensureUnitsSelected()) return;
        List<Integer> ids = new ArrayList<>(TdtSelection.resolve(TdtSelection.SCAN_RADIUS));
        ids.sort((a, b) -> Integer.compare(b, a));
        for (int i = 0; i < ids.size(); i++) {
            ModNetworking.sendToServer(new PacketIssueOrder(ids.get(i), order, Vec3.ZERO, i, -1));
        }
        // No ack here on purpose. SEM validates ownership server-side and silently drops what it
        // rejects, so a count printed at send time was a guess that contradicted every real refusal
        // arriving after it. MixinPacketIssueOrder now answers with the count that actually took.
    }

    private void armMoveTo() {
        if (!ensureUnitsSelected()) return;
        TdtSelection.writeSnapshot();
        CommanderOverlayRenderer.isSelectingPosition = true;
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.select_position").withStyle(ChatFormatting.GREEN), true);
        }
        onClose();
    }

    private void armAttack() {
        if (!ensureUnitsSelected()) return;
        TdtSelection.writeSnapshot();
        CommanderOverlayRenderer.isSelectingTarget = true;
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.select_target").withStyle(ChatFormatting.GREEN), true);
        }
        onClose();
    }

    private boolean ensureUnitsSelected() {
        if (!TdtSelection.resolve(TdtSelection.SCAN_RADIUS).isEmpty()) return true;
        if (this.minecraft != null && this.minecraft.player != null) {
            BoardKeybind.hint(this.minecraft.player, "message.tacz_sewv.tdt.need_selection");
        }
        return false;
    }

    @Nullable
    private Entity liveBoardTarget() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.hitResult == null) return this.boardTarget;
        if (mc.hitResult instanceof EntityHitResult ehr
                && (ehr.getEntity() instanceof MortarEntity || ehr.getEntity() instanceof VehicleEntity)) {
            return ehr.getEntity();
        }
        return this.boardTarget;
    }

    @Nullable
    private BlockPos liveLandPad() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) return this.landPad;
        HitResult block = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
        if (block instanceof BlockHitResult bhr && block.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return this.landPad;
    }

    private void orderReachGuard() {
        BoardKeybind.withOwnedUnits(pmc -> true, "message.tacz_sewv.guard.reach.none",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketReachGuard(unitIds)));
    }

    private void orderExitPlatoon() {
        BoardKeybind.withOwnedUnits(pmc -> true, "message.tacz_sewv.platoon.exit.none",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketExitPlatoon(unitIds)));
    }

    private void orderToggleAutoOrders() {
        BoardKeybind.withOwnedUnits(pmc -> pmc instanceof PmcCommanderEntity, "message.tacz_sewv.platoon.auto_orders.none",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketToggleAutoOrders(unitIds)));
    }

    private void orderAreaTask(int radius, int mode) {
        if (radius < PacketPatrolVehicle.MIN_RADIUS) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.patrol.min_radius", PacketPatrolVehicle.MIN_RADIUS)
                                .withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        BoardKeybind.orderAreaTask(radius, mode);
        onClose();
    }

    // --- Input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        boolean floatOpen = this.floatKind != null || this.floatPlatoonColor != null;
        if (floatOpen && clickFloat(mouseX, mouseY)) {
            clickSound();
            return true;
        }
        if (floatOpen) {
            clearFloat();
        }

        // Clicks outside the docked panel are swallowed (world stays non-interactive).
        if (mouseX > this.panelLeft + this.panelW) {
            return true;
        }

        if (clickTabs(mouseX, mouseY)) {
            clickSound();
            return true;
        }
        if (clickScrollbar(mouseX, mouseY)) return true;
        if (clickList(mouseX, mouseY)) {
            clickSound();
            return true;
        }
        if (clickRibbon(mouseX, mouseY)) {
            clickSound();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScroll && maxScroll() > 0) {
            this.scroll = Mth.clamp((int) ((mouseY - this.listTop) / (double) this.listHeight * maxScroll()),
                    0, maxScroll());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX <= this.panelLeft + this.panelW
                && mouseY >= this.listTop && mouseY <= this.listBottom) {
            this.scroll = Mth.clamp(this.scroll - (int) (delta * CELL_H), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean clickTabs(double mx, double my) {
        int y = PAD + 14;
        if (my < y || my > y + TAB_H) return false;
        int x = this.panelLeft + PAD;
        boolean useShort = tabsOverflow();
        for (Category c : Category.values()) {
            String label = tabLabel(c, useShort);
            int tw = this.font.width(label);
            if (mx >= x && mx < x + tw && my >= y && my < y + TAB_H) {
                this.category = c;
                this.scroll = 0;
                rebuildCells();
                return true;
            }
            x += tw + TAB_GAP;
        }
        return false;
    }

    private static String tabLabel(Category c, boolean useShort) {
        return I18n.get("gui.tacz_sewv.tdt.cat." + c.name().toLowerCase() + (useShort ? ".short" : ""));
    }

    /** The tab row has no wrapping — once the category count outgrows the panel at full labels,
     * fall back to the (already-shipped, previously unused) ".short" abbreviations instead of
     * letting text spill past the panel edge into the world view. */
    private boolean tabsOverflow() {
        int total = 0;
        for (Category c : Category.values()) {
            total += this.font.width(tabLabel(c, false)) + TAB_GAP;
        }
        return total > this.panelW - PAD * 2;
    }

    private boolean clickScrollbar(double mx, double my) {
        if (maxScroll() <= 0) return false;
        int sx = this.panelLeft + this.panelW - PAD - SCROLLBAR_W;
        if (mx >= sx && mx < sx + SCROLLBAR_W && my >= this.listTop && my <= this.listBottom) {
            this.draggingScroll = true;
            this.scroll = Mth.clamp((int) ((my - this.listTop) / (double) this.listHeight * maxScroll()),
                    0, maxScroll());
            return true;
        }
        return false;
    }

    private boolean clickList(double mx, double my) {
        if (my < this.listTop || my > this.listBottom) return false;
        int originX = this.panelLeft + PAD;
        int contentX = (int) mx - originX;
        int contentY = (int) my - this.listTop + this.scroll;

        for (Cell cell : this.cells) {
            if (contentX < cell.x() || contentX >= cell.x() + cell.w()
                    || contentY < cell.y() || contentY >= cell.y() + cell.h()) {
                continue;
            }

            if (cell.entry().stepper != null) {
                int relX = contentX - cell.x();
                StepperSpec spec = stepperSpec(cell.entry().stepper);
                int plusLeft = cell.w() - STEP_BTN;
                int minusLeft = stepperMinusLeft(cell);
                if (relX >= plusLeft) {
                    spec.set.accept(Math.min(spec.max, spec.get.getAsInt() + spec.step));
                    return true;
                }
                if (relX >= minusLeft && relX < minusLeft + STEP_BTN) {
                    spec.set.accept(Math.max(spec.min, spec.get.getAsInt() - spec.step));
                    return true;
                }
                // Label / left side fires the order
                cell.entry().action.run();
                if (cell.entry().closes) onClose();
                return true;
            }

            if (cell.entry().cycle != null) {
                int relX = contentX - cell.x();
                int nextLeft = cell.w() - STEP_BTN;
                int prevLeft = nextLeft - 4 - this.font.width(selectedNameCategory) - 4 - STEP_BTN;
                // Every zone applies immediately — this sets a sticky preference, not a fire-once
                // order like the other stepper rows, so there is no separate "confirm" step to miss.
                if (relX >= nextLeft) {
                    stepNameCategory(1);
                    cell.entry().action.run();
                    return true;
                }
                if (relX >= prevLeft && relX < prevLeft + STEP_BTN) {
                    stepNameCategory(-1);
                    cell.entry().action.run();
                    return true;
                }
                // Label / left side re-sends the current selection (harmless if unchanged)
                cell.entry().action.run();
                if (cell.entry().closes) onClose();
                return true;
            }

            if (cell.entry().labelKey.equals("gui.tacz_sewv.tdt.reach_guard") && !reachGuardActive()) {
                return true;
            }
            cell.entry().action.run();
            if (cell.entry().closes) onClose();
            return true;
        }
        return false;
    }

    private int stepperMinusLeft(Cell cell) {
        StepperSpec spec = stepperSpec(cell.entry().stepper);
        String suffix = cell.entry().stepper == StepperKind.LINE
                ? I18n.get("gui.tacz_sewv.tdt.unit.per_row")
                : I18n.get("gui.tacz_sewv.tdt.unit.blocks");
        String value = spec.get.getAsInt() + suffix;
        int plusLeft = cell.w() - STEP_BTN;
        int valueW = this.font.width(value);
        return plusLeft - 4 - valueW - 4 - STEP_BTN;
    }

    private boolean clickRibbon(double mx, double my) {
        if (my < this.ribbonTop || my > this.ribbonTop + RIBBON_H) return false;
        if (mx > this.panelLeft + this.panelW) return false;

        int allX = this.panelLeft + PAD;
        int allY = this.ribbonTop + 4;
        if (mx >= allX && mx < allX + BTN_W && my >= allY && my < allY + BTN_H) {
            TdtSelection.toggleSelectAll();
            return true;
        }

        int noneX = allX + BTN_W + 2;
        if (mx >= noneX && mx < noneX + BTN_W && my >= allY && my < allY + BTN_H) {
            TdtSelection.deselectAll();
            return true;
        }

        int liveY = allY + BTN_H + 2;
        int liveW = 48;
        if (mx >= allX && mx < allX + liveW && my >= liveY && my < liveY + BTN_H) {
            ClientEvents.armLiveSelection();
            onClose();
            return true;
        }

        int x = allX + RIBBON_ICONS_X;
        int y = this.ribbonTop + 6;

        Map<Integer, List<TdtSelection.Entry>> byPlatoon = TdtSelection.byPlatoon();
        for (Map.Entry<Integer, List<TdtSelection.Entry>> e : byPlatoon.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            if (mx >= x && mx < x + ICON_SIZE && my >= y && my < y + ICON_SIZE) {
                if (TdtSelection.distinctCount(units) == 1) {
                    toggleGroup(units);
                } else {
                    this.floatKind = null;
                    this.floatPlatoonColor = e.getKey();
                    this.floatX = x;
                    this.floatY = this.ribbonTop - 8;
                    this.floatAnim = 0.0F;
                }
                return true;
            }
            x += ICON_SIZE + 6;
            if (x + ICON_SIZE > this.panelLeft + this.panelW - PAD) return false;
        }

        Map<VehicleMarker.Kind, List<TdtSelection.Entry>> byKind = TdtSelection.byKind();
        for (Map.Entry<VehicleMarker.Kind, List<TdtSelection.Entry>> e : byKind.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            if (mx >= x && mx < x + ICON_SIZE && my >= y && my < y + ICON_SIZE) {
                if (TdtSelection.distinctCount(units) == 1) {
                    toggleGroup(units);
                } else {
                    this.floatPlatoonColor = null;
                    this.floatKind = e.getKey();
                    this.floatX = x;
                    this.floatY = this.ribbonTop - 8;
                    this.floatAnim = 0.0F;
                }
                return true;
            }
            x += ICON_SIZE + 6;
            if (x + ICON_SIZE > this.panelLeft + this.panelW - PAD) break;
        }
        return false;
    }

    private boolean clickFloat(double mx, double my) {
        if (this.floatKind == null && this.floatPlatoonColor == null) return false;
        List<TdtSelection.Entry> units = currentFloatUnits();
        if (units.isEmpty()) {
            clearFloat();
            return false;
        }

        int cols = Math.min(units.size(), 5);
        int unitRows = (units.size() + cols - 1) / cols;
        int pad = 6;
        int fw = Math.max(cols * (FLOAT_ICON + 4) + pad * 2, 90);
        int fh = (1 + unitRows) * (FLOAT_ICON + 4) + pad * 2;
        int fx = Mth.clamp(this.floatX - fw / 2 + ICON_SIZE / 2, 4, this.width - fw - 4);
        int fy = Mth.clamp(this.floatY - fh, 4, this.height - fh - 4);

        if (mx < fx || mx > fx + fw || my < fy || my > fy + fh) {
            return false;
        }

        if (my >= fy + pad && my < fy + pad + 14 && mx >= fx + pad && mx < fx + fw - pad) {
            for (TdtSelection.Entry unit : units) {
                TdtSelection.select(unit.id());
            }
            clearFloat();
            return true;
        }

        int ix = fx + pad;
        int iy = fy + pad + 16;
        for (TdtSelection.Entry unit : units) {
            if (mx >= ix && mx < ix + FLOAT_ICON && my >= iy && my < iy + FLOAT_ICON) {
                TdtSelection.toggle(unit.id());
                return true;
            }
            ix += FLOAT_ICON + 4;
            if (ix + FLOAT_ICON > fx + fw - pad) {
                ix = fx + pad;
                iy += FLOAT_ICON + 4;
            }
        }
        return true;
    }

    private void clearFloat() {
        this.floatKind = null;
        this.floatPlatoonColor = null;
        this.floatAnim = 0.0F;
    }

    /** Whichever bucket is currently expanded — a platoon group, or a plain Kind bucket. */
    private List<TdtSelection.Entry> currentFloatUnits() {
        if (this.floatPlatoonColor != null) {
            return TdtSelection.byPlatoon().getOrDefault(this.floatPlatoonColor, List.of());
        }
        if (this.floatKind != null) {
            return TdtSelection.byKind().getOrDefault(this.floatKind, List.of());
        }
        return List.of();
    }

    /** Select all if none of the group is selected yet, otherwise deselect all of it. */
    private static void toggleGroup(List<TdtSelection.Entry> entries) {
        boolean anySelected = false;
        for (TdtSelection.Entry e : entries) {
            if (TdtSelection.isSelected(e.id())) {
                anySelected = true;
                break;
            }
        }
        for (TdtSelection.Entry e : entries) {
            if (anySelected) {
                TdtSelection.deselect(e.id());
            } else {
                TdtSelection.select(e.id());
            }
        }
    }

    private static boolean unitsAnySelected(List<TdtSelection.Entry> units) {
        for (TdtSelection.Entry u : units) {
            if (TdtSelection.isSelected(u.id())) return true;
        }
        return false;
    }

    private static boolean unitsAnyCommander(List<TdtSelection.Entry> units) {
        for (TdtSelection.Entry u : units) {
            if (u.isCommander()) return true;
        }
        return false;
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.INTERACT_BEEP.get(), 1.0F));
    }

    private boolean reachGuardActive() {
        boolean knownOwn = false;
        boolean anyGuard = false;
        for (VehicleMarker m : MapMarkers.markers()) {
            if (m.allegiance() != VehicleMarker.Allegiance.OWN) continue;
            knownOwn = true;
            if (m.hasGuard()) {
                anyGuard = true;
                break;
            }
        }
        return !knownOwn || anyGuard;
    }

    // --- Render -------------------------------------------------------------

    /** No world dim — the battlefield stays visible beside the docked panel. */
    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.floatAnim = Math.min(1.0F, this.floatAnim + partialTick * 0.25F);
        this.pendingTip = null;

        // Panel chrome
        g.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelW, this.panelBottom, COL_BASE);
        g.fill(this.panelLeft + this.panelW - 1, this.panelTop, this.panelLeft + this.panelW, this.panelBottom, COL_BORDER);

        g.drawString(this.font, this.title, this.panelLeft + PAD, PAD, COL_TEXT, false);
        if (!net.minecraftforge.fml.ModList.get().isLoaded("xaeroworldmap")) {
            g.drawString(this.font, Component.translatable("gui.tacz_sewv.tdt.xaero_hint"),
                    this.panelLeft + PAD + this.font.width(this.title) + 8, PAD, COL_MUTED, false);
        }
        renderTabs(g);
        renderList(g, mouseX, mouseY);
        renderRibbon(g);
        if (this.floatKind != null || this.floatPlatoonColor != null) {
            renderFloat(g, mouseX, mouseY);
        }

        if (this.pendingTip != null) {
            g.renderTooltip(this.font, Component.translatable(this.pendingTip), mouseX, mouseY);
        }
    }

    private void renderTabs(GuiGraphics g) {
        int y = PAD + 14;
        int x = this.panelLeft + PAD;
        boolean useShort = tabsOverflow();
        for (Category c : Category.values()) {
            String label = tabLabel(c, useShort);
            int tw = this.font.width(label);
            boolean active = c == this.category;
            int color = active ? COL_ACCENT : COL_MUTED;
            g.drawString(this.font, label, x, y + 4, color, false);
            if (active) {
                g.fill(x, y + TAB_H - 2, x + tw, y + TAB_H, COL_ACCENT);
            }
            x += tw + TAB_GAP;
        }
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int originX = this.panelLeft + PAD;
        g.enableScissor(originX, this.listTop, this.panelLeft + this.panelW - PAD, this.listBottom);

        String unitBlocks = I18n.get("gui.tacz_sewv.tdt.unit.blocks");
        String unitPerRow = I18n.get("gui.tacz_sewv.tdt.unit.per_row");
        boolean allView = this.category == Category.ALL;
        int labelInset = allView ? 6 : STRIPE_W + 4;

        for (Section section : this.sections) {
            int sy0 = this.listTop + section.y0() - this.scroll;
            int sy1 = this.listTop + section.y1() - this.scroll;
            if (sy1 < this.listTop || sy0 > this.listBottom) continue;
            g.fill(originX, sy0, originX + GROUP_STRIPE_W, sy1, stripeFor(section.cat()));
            // Hairline under the section
            g.fill(originX + GROUP_STRIPE_W, sy1 - 1, originX + this.contentInnerW, sy1, COL_BORDER);
            String header = I18n.get("gui.tacz_sewv.tdt.section." + section.cat().name().toLowerCase());
            g.drawString(this.font, header, originX + GROUP_STRIPE_W + 4, sy0 + 2, COL_MUTED, false);
        }

        for (Cell cell : this.cells) {
            int sx = originX + cell.x();
            int sy = this.listTop + cell.y() - this.scroll;
            if (sy + cell.h() < this.listTop || sy > this.listBottom) continue;

            boolean inactive = cell.entry().labelKey.equals("gui.tacz_sewv.tdt.reach_guard") && !reachGuardActive();
            boolean hover = mouseX >= sx && mouseX < sx + cell.w()
                    && mouseY >= sy && mouseY < sy + cell.h()
                    && mouseY >= this.listTop && mouseY <= this.listBottom;

            int fill = hover && !inactive ? COL_HOVER : COL_SURFACE;
            g.fill(sx, sy, sx + cell.w(), sy + cell.h(), fill);
            if (!allView) {
                g.fill(sx, sy, sx + STRIPE_W, sy + cell.h(), COL_STRIPE);
            }
            // Hairline bottom
            g.fill(sx, sy + cell.h() - 1, sx + cell.w(), sy + cell.h(), COL_BORDER);

            int textColor = inactive ? COL_MUTED : COL_TEXT;
            Component label = Component.translatable(cell.entry().labelKey);
            int textX = sx + labelInset;

            if (cell.entry().stepper != null) {
                StepperSpec spec = stepperSpec(cell.entry().stepper);
                String suffix = cell.entry().stepper == StepperKind.LINE ? unitPerRow : unitBlocks;
                String value = spec.get.getAsInt() + suffix;
                int plusLeft = sx + cell.w() - STEP_BTN;
                int valueW = this.font.width(value);
                int minusLeft = plusLeft - 4 - valueW - 4 - STEP_BTN;

                g.drawString(this.font, label, textX, sy + (CELL_H - 8) / 2, textColor, false);

                drawStepBtn(g, minusLeft, sy + 2, "-", hover);
                int valueColor = spec.get.getAsInt() < spec.redBelow ? 0xFFE07070 : COL_MUTED;
                g.drawCenteredString(this.font, value, minusLeft + STEP_BTN + 4 + valueW / 2,
                        sy + (CELL_H - 8) / 2, valueColor);
                drawStepBtn(g, plusLeft, sy + 2, "+", hover);
            } else if (cell.entry().cycle != null) {
                String value = selectedNameCategory;
                int nextLeft = sx + cell.w() - STEP_BTN;
                int valueW = this.font.width(value);
                int prevLeft = nextLeft - 4 - valueW - 4 - STEP_BTN;

                g.drawString(this.font, label, textX, sy + (CELL_H - 8) / 2, textColor, false);

                drawStepBtn(g, prevLeft, sy + 2, "<", hover);
                g.drawCenteredString(this.font, value, prevLeft + STEP_BTN + 4 + valueW / 2,
                        sy + (CELL_H - 8) / 2, COL_MUTED);
                drawStepBtn(g, nextLeft, sy + 2, ">", hover);
            } else {
                g.drawString(this.font, label, textX, sy + (CELL_H - 8) / 2, textColor, false);
            }

            if (hover && cell.entry().tipKey != null) {
                this.pendingTip = cell.entry().tipKey;
            }
        }
        g.disableScissor();

        if (maxScroll() > 0) {
            int sx = this.panelLeft + this.panelW - PAD - SCROLLBAR_W;
            g.fill(sx, this.listTop, sx + SCROLLBAR_W, this.listBottom, 0x66000000);
            int thumbH = Math.max(16, (int) ((float) this.listHeight / this.contentHeight * this.listHeight));
            int thumbY = this.listTop + (int) ((float) this.scroll / maxScroll() * (this.listHeight - thumbH));
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, COL_ACCENT);
        }
    }

    private void drawStepBtn(GuiGraphics g, int x, int y, String glyph, boolean parentHover) {
        g.fill(x, y, x + STEP_BTN, y + CELL_H - 4, parentHover ? COL_HOVER : COL_SURFACE);
        g.fill(x, y, x + STEP_BTN, y + 1, COL_BORDER);
        g.fill(x, y + CELL_H - 5, x + STEP_BTN, y + CELL_H - 4, COL_BORDER);
        g.drawCenteredString(this.font, glyph, x + STEP_BTN / 2, y + (CELL_H - 4 - 8) / 2, COL_ACCENT);
    }

    private void renderRibbon(GuiGraphics g) {
        g.fill(this.panelLeft, this.ribbonTop, this.panelLeft + this.panelW, this.panelBottom, COL_BASE);
        g.fill(this.panelLeft + PAD, this.ribbonTop, this.panelLeft + this.panelW - PAD, this.ribbonTop + 1, COL_BORDER);

        boolean all = TdtSelection.allSelected();
        String allLabel = I18n.get(all ? "gui.tacz_sewv.tdt.deselect_all" : "gui.tacz_sewv.tdt.select_all");
        int allX = this.panelLeft + PAD;
        int allY = this.ribbonTop + 4;
        g.fill(allX, allY, allX + BTN_W, allY + BTN_H, COL_SURFACE);
        if (all) {
            g.fill(allX, allY + BTN_H - 1, allX + BTN_W, allY + BTN_H, COL_ACCENT);
        }
        g.drawCenteredString(this.font, allLabel, allX + BTN_W / 2, allY + 3, COL_TEXT);

        int noneX = allX + BTN_W + 2;
        g.fill(noneX, allY, noneX + BTN_W, allY + BTN_H, COL_SURFACE);
        g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.tdt.select_none"), noneX + BTN_W / 2, allY + 3, COL_TEXT);

        int liveY = allY + BTN_H + 2;
        int liveW = 48;
        g.fill(allX, liveY, allX + liveW, liveY + BTN_H, COL_SURFACE);
        g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.tdt.live_sel"),
                allX + liveW / 2, liveY + 3, COL_TEXT);

        int x = allX + RIBBON_ICONS_X;
        int y = this.ribbonTop + 6;

        // Platoons get their own expandable entry, separate from the plain Kind buckets below —
        // one icon per platoon (armor/infantry generic, per representativeKind), vehicles counted
        // once each regardless of crew size (TdtSelection.distinctCount).
        Map<Integer, List<TdtSelection.Entry>> byPlatoon = TdtSelection.byPlatoon();
        for (Map.Entry<Integer, List<TdtSelection.Entry>> e : byPlatoon.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            int color = e.getKey();
            boolean anySelected = unitsAnySelected(units);
            float target = anySelected ? 1.0f : 0.5f;
            float cur = this.platoonRibbonAlpha.getOrDefault(color, 0.0f);
            cur = cur + (target - cur) * 0.2f;
            this.platoonRibbonAlpha.put(color, cur);

            if (anySelected) {
                g.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1, COL_ACCENT);
            }
            g.fill(x - 2, y - 2, x + ICON_SIZE + 2, y + ICON_SIZE + 3, 0xFF000000 | color);
            ResourceLocation tex = KIND_TEX.get(TdtSelection.representativeKind(units));
            g.setColor(1.0f, 1.0f, 1.0f, cur);
            g.blit(tex, x, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            String badge = "x" + TdtSelection.distinctCount(units);
            g.drawString(this.font, badge, x + ICON_SIZE - this.font.width(badge), y + ICON_SIZE - 8, COL_TEXT, true);
            if (unitsAnyCommander(units)) {
                g.drawString(this.font, "★", x - 1, y - 1, starColor(color), true);
            }
            x += ICON_SIZE + 6;
            if (x + ICON_SIZE > this.panelLeft + this.panelW - PAD) break;
        }
        this.platoonRibbonAlpha.keySet().removeIf(k -> !byPlatoon.containsKey(k) || byPlatoon.get(k).isEmpty());

        Map<VehicleMarker.Kind, List<TdtSelection.Entry>> byKind = TdtSelection.byKind();
        for (Map.Entry<VehicleMarker.Kind, List<TdtSelection.Entry>> e : byKind.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            boolean anySelected = unitsAnySelected(units);
            float target = anySelected ? 1.0f : 0.5f;
            float cur = this.ribbonAlpha.getOrDefault(e.getKey(), 0.0f);
            cur = cur + (target - cur) * 0.2f;
            this.ribbonAlpha.put(e.getKey(), cur);

            if (anySelected) {
                g.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1, COL_ACCENT);
            }
            ResourceLocation tex = KIND_TEX.get(e.getKey());
            g.setColor(1.0f, 1.0f, 1.0f, cur);
            g.blit(tex, x, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            // A vehicle's whole crew still counts as one — "x3" for a 3-man tank was misleading.
            String badge = "x" + TdtSelection.distinctCount(units);
            g.drawString(this.font, badge, x + ICON_SIZE - this.font.width(badge), y + ICON_SIZE - 8, COL_TEXT, true);
            if (unitsAnyCommander(units)) {
                g.drawString(this.font, "★", x - 1, y - 1, 0xFFFFD700, true);
            }
            x += ICON_SIZE + 6;
            if (x + ICON_SIZE > this.panelLeft + this.panelW - PAD) break;
        }
        this.ribbonAlpha.keySet().removeIf(k -> !byKind.containsKey(k) || byKind.get(k).isEmpty());

        String count = I18n.get("gui.tacz_sewv.tdt.selection_count",
                TdtSelection.selectedCount(), TdtSelection.scanned().size());
        g.drawCenteredString(this.font, count,
                this.panelLeft + this.panelW / 2, this.ribbonTop + RIBBON_H - 12, COL_MUTED);
    }

    private void renderFloat(GuiGraphics g, int mouseX, int mouseY) {
        List<TdtSelection.Entry> units = currentFloatUnits();
        if (units.isEmpty()) return;

        int cols = Math.min(units.size(), 5);
        int unitRows = (units.size() + cols - 1) / cols;
        int pad = 6;
        int fw = Math.max(cols * (FLOAT_ICON + 4) + pad * 2, 90);
        int fh = (1 + unitRows) * (FLOAT_ICON + 4) + pad * 2;
        int fx = Mth.clamp(this.floatX - fw / 2 + ICON_SIZE / 2, 4, this.width - fw - 4);
        int fyBase = Mth.clamp(this.floatY - fh, 4, this.height - fh - 4);
        int fy = fyBase + (int) ((1.0f - this.floatAnim) * 10);

        int a = (int) (this.floatAnim * 0xF0) << 24;
        g.fill(fx, fy, fx + fw, fy + fh, a | (COL_BASE & 0xFFFFFF));
        g.fill(fx, fy, fx + fw, fy + 1, COL_ACCENT);

        // Icons first, tracking whichever one the cursor is over — the header line above them
        // then shows that unit's name instead of the default "Select All" prompt.
        int ix = fx + pad;
        int iy = fy + pad + 16;
        String hoveredName = null;
        for (TdtSelection.Entry unit : units) {
            boolean sel = TdtSelection.isSelected(unit.id());
            boolean hover = mouseX >= ix && mouseX < ix + FLOAT_ICON && mouseY >= iy && mouseY < iy + FLOAT_ICON;
            if (hover && !unit.name().isEmpty()) hoveredName = unit.name();
            if (sel) {
                g.fill(ix - 1, iy - 1, ix + FLOAT_ICON + 1, iy + FLOAT_ICON + 1, COL_ACCENT);
            }
            g.setColor(1.0f, 1.0f, 1.0f, sel ? 1.0f : 0.45f);
            g.blit(KIND_TEX.get(unit.kind()), ix, iy, 0, 0, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            if (unit.platoonColorRgb() != 0) {
                g.fill(ix, iy + FLOAT_ICON + 1, ix + FLOAT_ICON, iy + FLOAT_ICON + 2,
                        0xFF000000 | unit.platoonColorRgb());
            }
            if (unit.isCommander()) {
                g.drawString(this.font, "★", ix - 1, iy - 1, starColor(unit.platoonColorRgb()), true);
            }
            ix += FLOAT_ICON + 4;
            if (ix + FLOAT_ICON > fx + fw - pad) {
                ix = fx + pad;
                iy += FLOAT_ICON + 4;
            }
        }

        String header = hoveredName != null ? hoveredName : I18n.get("gui.tacz_sewv.tdt.float_select_all");
        int headerColor = hoveredName != null ? COL_TEXT : COL_ACCENT;
        int headerW = this.font.width(header);
        // A full name can run wider than the flyout at small unit counts - clip rather than
        // overflow the box the way the unpatched tab bar did.
        if (headerW > fw - pad * 2) {
            header = this.font.plainSubstrByWidth(header, fw - pad * 2 - this.font.width("…")) + "…";
        }
        g.drawCenteredString(this.font, header, fx + fw / 2, fy + pad + 2, headerColor);
    }

    /** The commander star tints with its platoon's colour when it has one; gold otherwise. */
    private static int starColor(int platoonColorRgb) {
        return platoonColorRgb != 0 ? (0xFF000000 | platoonColorRgb) : 0xFFFFD700;
    }

    private record StepperSpec(IntSupplier get, IntConsumer set, int min, int max, int step, int redBelow) {}

    private StepperSpec stepperSpec(StepperKind kind) {
        return switch (kind) {
            case PATROL -> new StepperSpec(() -> patrolRadius, v -> patrolRadius = v,
                    RADIUS_FLOOR, PacketPatrolVehicle.MAX_RADIUS, RADIUS_STEP, PacketPatrolVehicle.MIN_RADIUS);
            case SEARCH -> new StepperSpec(() -> searchRadius, v -> searchRadius = v,
                    RADIUS_FLOOR, PacketPatrolVehicle.MAX_RADIUS, RADIUS_STEP, PacketPatrolVehicle.MIN_RADIUS);
            case ALTITUDE -> new StepperSpec(() -> heliAltitude, v -> heliAltitude = v,
                    PacketHelicopterCommand.MIN_ALTITUDE, PacketHelicopterCommand.MAX_ALTITUDE, ALT_STEP, 0);
            case LINE -> new StepperSpec(() -> lineRowSize, v -> lineRowSize = v,
                    PacketVehicleFormation.MIN_ROW_SIZE, PacketVehicleFormation.MAX_ROW_SIZE, 1, 0);
        };
    }

    @Override
    public void removed() {
        TdtSelection.clearGlow();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
