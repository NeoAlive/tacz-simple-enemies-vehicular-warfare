package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.EnumMap;
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
import net.minecraft.sounds.SoundEvents;
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
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketReachGuard;
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

    // Docked left ~38% of screen; clamped so a 2-col grid stays readable.
    private static final float PANEL_WIDTH_FRAC = 0.38f;
    private static final int PANEL_W_MIN = 260;
    private static final int PANEL_W_MAX = 400;
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

    enum Category {
        ALL, ORDERS, CREW, AREA, AIR, FORM
    }

    private enum StepperKind { PATROL, SEARCH, ALTITUDE, LINE }

    @Nullable
    private final Entity boardTarget;
    @Nullable
    private final BlockPos landPad;
    private final int formationAxis;

    private static int patrolRadius = DEFAULT_RADIUS;
    private static int searchRadius = DEFAULT_RADIUS;
    private static int heliAltitude = IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
    private static int lineRowSize = PacketVehicleFormation.DEFAULT_ROW_SIZE;

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

    @Nullable
    private VehicleMarker.Kind floatKind;
    private float floatAnim;
    private int floatX;
    private int floatY;
    private final EnumMap<VehicleMarker.Kind, Float> ribbonAlpha = new EnumMap<>(VehicleMarker.Kind.class);
    @Nullable
    private String pendingTip;

    private record OrderEntry(Category cat, String labelKey, @Nullable String tipKey,
                              boolean closes, Runnable action, @Nullable StepperKind stepper) {}

    /** Content-space rect for one order (grid cell or full-width stepper row). */
    private record Cell(OrderEntry entry, int x, int y, int w, int h) {}

    private TdtScreen(@Nullable Entity boardTarget, @Nullable BlockPos landPad, int formationAxis) {
        super(Component.translatable("gui.tacz_sewv.tdt.title"));
        this.boardTarget = boardTarget;
        this.landPad = landPad;
        this.formationAxis = formationAxis;
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (InvasionHudClient.isActive()) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.invasion.orders_locked"), true);
            return;
        }

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
        this.floatKind = null;
        this.floatAnim = 0.0F;
    }

    private void buildCatalog() {
        this.catalog.clear();
        add(Category.ORDERS, "gui.tacz_sewv.tdt.hold", null, true, () -> issueSemOrder(OrderType.HOLD_POSITION));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.follow", null, true, () -> issueSemOrder(OrderType.FOLLOW_COMMANDER));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.move_to", "gui.tacz_sewv.tdt.move_to.tip", false, this::armMoveTo);
        add(Category.ORDERS, "gui.tacz_sewv.tdt.attack", "gui.tacz_sewv.tdt.attack.tip", false, this::armAttack);
        add(Category.ORDERS, "gui.tacz_sewv.tdt.cease_fire", null, true, () -> issueSemOrder(OrderType.CEASE_FIRE));
        add(Category.ORDERS, "gui.tacz_sewv.tdt.free_fire", null, true, () -> issueSemOrder(OrderType.FREE_FIRE));

        add(Category.CREW, "gui.tacz_sewv.tdt.board", null, true, () -> BoardKeybind.orderBoard(this.boardTarget, false));
        add(Category.CREW, "gui.tacz_sewv.tdt.board_passenger", "gui.tacz_sewv.tdt.board_passenger.tip", true,
                () -> BoardKeybind.orderBoard(this.boardTarget, true));
        add(Category.CREW, "gui.tacz_sewv.tdt.dismount", null, true, BoardKeybind::orderDismount);
        add(Category.CREW, "gui.tacz_sewv.tdt.escort", "gui.tacz_sewv.tdt.escort.tip", true, ClientEvents::armEscort);
        add(Category.CREW, "gui.tacz_sewv.tdt.set_guard", "gui.tacz_sewv.tdt.set_guard.tip", true, ClientEvents::armGuardPosition);
        add(Category.CREW, "gui.tacz_sewv.tdt.reach_guard", "gui.tacz_sewv.tdt.reach_guard.tip", true, this::orderReachGuard);

        add(Category.AREA, "gui.tacz_sewv.tdt.patrol", "gui.tacz_sewv.tdt.patrol.tip", false,
                () -> orderAreaTask(patrolRadius, IVehiclePatrol.MODE_PATROL), StepperKind.PATROL);
        add(Category.AREA, "gui.tacz_sewv.tdt.search", "gui.tacz_sewv.tdt.search.tip", false,
                () -> orderAreaTask(searchRadius, IVehiclePatrol.MODE_SEARCH), StepperKind.SEARCH);
        add(Category.AREA, "gui.tacz_sewv.tdt.dismiss", "gui.tacz_sewv.tdt.dismiss.tip", true,
                () -> BoardKeybind.orderAreaTask(0, PacketPatrolVehicle.MODE_DISMISS));

        add(Category.AIR, "gui.tacz_sewv.tdt.takeoff", "gui.tacz_sewv.tdt.altitude.tip", true,
                () -> HelicopterKeybind.orderTakeoff(heliAltitude), StepperKind.ALTITUDE);
        add(Category.AIR, "gui.tacz_sewv.tdt.land", null, true, () -> HelicopterKeybind.orderLand(this.landPad));
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
    }

    private void add(Category cat, String label, @Nullable String tip, boolean closes, Runnable action) {
        add(cat, label, tip, closes, action, null);
    }

    private void add(Category cat, String label, @Nullable String tip, boolean closes,
                     Runnable action, @Nullable StepperKind stepper) {
        this.catalog.add(new OrderEntry(cat, label, tip, closes, action, stepper));
    }

    private void rebuildCells() {
        this.cells.clear();
        int y = 0;
        int col = 0;
        int cellW = (this.contentInnerW - CELL_GAP * (GRID_COLS - 1)) / GRID_COLS;

        for (OrderEntry e : this.catalog) {
            if (this.category != Category.ALL && e.cat != this.category) continue;

            if (e.stepper != null) {
                if (col != 0) {
                    y += CELL_H + CELL_GAP;
                    col = 0;
                }
                this.cells.add(new Cell(e, 0, y, this.contentInnerW, CELL_H));
                y += CELL_H + CELL_GAP;
                continue;
            }

            int x = col * (cellW + CELL_GAP);
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
        this.contentHeight = Math.max(y - CELL_GAP, 0);
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - this.listHeight);
    }

    // --- SEM / area helpers -------------------------------------------------

    private void issueSemOrder(OrderType order) {
        List<Integer> ids = new ArrayList<>(TdtSelection.resolve(TdtSelection.SCAN_RADIUS));
        if (ids.isEmpty()) {
            if (this.minecraft != null && this.minecraft.player != null) {
                BoardKeybind.hint(this.minecraft.player, "message.tacz_sewv.board.no_units");
            }
            return;
        }
        ids.sort((a, b) -> Integer.compare(b, a));
        for (int i = 0; i < ids.size(); i++) {
            ModNetworking.sendToServer(new PacketIssueOrder(ids.get(i), order, Vec3.ZERO, i, -1));
        }
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
        if (TdtSelection.selectedCount() > 0) return true;
        List<Integer> all = TdtSelection.resolve(TdtSelection.SCAN_RADIUS);
        if (all.isEmpty()) {
            if (this.minecraft != null && this.minecraft.player != null) {
                BoardKeybind.hint(this.minecraft.player, "message.tacz_sewv.tdt.need_selection");
            }
            return false;
        }
        TdtSelection.selectAll();
        return true;
    }

    private void orderReachGuard() {
        BoardKeybind.withOwnedUnits(pmc -> true, "message.tacz_sewv.guard.reach.none",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketReachGuard(unitIds)));
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

        if (this.floatKind != null && clickFloat(mouseX, mouseY)) {
            clickSound();
            return true;
        }
        if (this.floatKind != null) {
            this.floatKind = null;
            this.floatAnim = 0.0F;
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
        for (Category c : Category.values()) {
            String label = I18n.get("gui.tacz_sewv.tdt.cat." + c.name().toLowerCase());
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
        int allW = 40;
        int allH = 14;
        if (mx >= allX && mx < allX + allW && my >= allY && my < allY + allH) {
            TdtSelection.toggleSelectAll();
            return true;
        }

        Map<VehicleMarker.Kind, List<TdtSelection.Entry>> byKind = TdtSelection.byKind();
        int x = this.panelLeft + PAD + 46;
        int y = this.ribbonTop + 6;
        for (Map.Entry<VehicleMarker.Kind, List<TdtSelection.Entry>> e : byKind.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            if (mx >= x && mx < x + ICON_SIZE && my >= y && my < y + ICON_SIZE) {
                if (units.size() == 1) {
                    TdtSelection.toggle(units.get(0).id());
                } else {
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
        if (this.floatKind == null) return false;
        List<TdtSelection.Entry> units = TdtSelection.byKind().getOrDefault(this.floatKind, List.of());
        if (units.isEmpty()) {
            this.floatKind = null;
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
            TdtSelection.selectKind(this.floatKind);
            this.floatKind = null;
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

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
        renderTabs(g);
        renderList(g, mouseX, mouseY);
        renderRibbon(g);
        if (this.floatKind != null) {
            renderFloat(g);
        }

        if (this.pendingTip != null) {
            g.renderTooltip(this.font, Component.translatable(this.pendingTip), mouseX, mouseY);
        }
    }

    private void renderTabs(GuiGraphics g) {
        int y = PAD + 14;
        int x = this.panelLeft + PAD;
        for (Category c : Category.values()) {
            String label = I18n.get("gui.tacz_sewv.tdt.cat." + c.name().toLowerCase());
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
            g.fill(sx, sy, sx + STRIPE_W, sy + cell.h(), COL_STRIPE);
            // Hairline bottom
            g.fill(sx, sy + cell.h() - 1, sx + cell.w(), sy + cell.h(), COL_BORDER);

            int textColor = inactive ? COL_MUTED : COL_TEXT;
            Component label = Component.translatable(cell.entry().labelKey);

            if (cell.entry().stepper != null) {
                StepperSpec spec = stepperSpec(cell.entry().stepper);
                String suffix = cell.entry().stepper == StepperKind.LINE ? unitPerRow : unitBlocks;
                String value = spec.get.getAsInt() + suffix;
                int plusLeft = sx + cell.w() - STEP_BTN;
                int valueW = this.font.width(value);
                int minusLeft = plusLeft - 4 - valueW - 4 - STEP_BTN;

                g.drawString(this.font, label, sx + STRIPE_W + 4, sy + (CELL_H - 8) / 2, textColor, false);

                drawStepBtn(g, minusLeft, sy + 2, "-", hover);
                int valueColor = spec.get.getAsInt() < spec.redBelow ? 0xFFE07070 : COL_MUTED;
                g.drawCenteredString(this.font, value, minusLeft + STEP_BTN + 4 + valueW / 2,
                        sy + (CELL_H - 8) / 2, valueColor);
                drawStepBtn(g, plusLeft, sy + 2, "+", hover);
            } else {
                g.drawString(this.font, label, sx + STRIPE_W + 4, sy + (CELL_H - 8) / 2, textColor, false);
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
        g.fill(allX, allY, allX + 40, allY + 14, COL_SURFACE);
        if (all) {
            g.fill(allX, allY + 13, allX + 40, allY + 14, COL_ACCENT);
        }
        g.drawCenteredString(this.font, allLabel, allX + 20, allY + 3, COL_TEXT);

        Map<VehicleMarker.Kind, List<TdtSelection.Entry>> byKind = TdtSelection.byKind();
        int x = allX + 46;
        int y = this.ribbonTop + 6;
        for (Map.Entry<VehicleMarker.Kind, List<TdtSelection.Entry>> e : byKind.entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            boolean anySelected = false;
            for (TdtSelection.Entry u : units) {
                if (TdtSelection.isSelected(u.id())) {
                    anySelected = true;
                    break;
                }
            }
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
            String badge = "x" + units.size();
            g.drawString(this.font, badge, x + ICON_SIZE - this.font.width(badge), y + ICON_SIZE - 8, COL_TEXT, true);
            x += ICON_SIZE + 6;
            if (x + ICON_SIZE > this.panelLeft + this.panelW - PAD) break;
        }
        this.ribbonAlpha.keySet().removeIf(k -> !byKind.containsKey(k) || byKind.get(k).isEmpty());

        String count = I18n.get("gui.tacz_sewv.tdt.selection_count",
                TdtSelection.selectedCount(), TdtSelection.scanned().size());
        g.drawCenteredString(this.font, count,
                this.panelLeft + this.panelW / 2, this.ribbonTop + RIBBON_H - 12, COL_MUTED);
    }

    private void renderFloat(GuiGraphics g) {
        List<TdtSelection.Entry> units = TdtSelection.byKind().getOrDefault(this.floatKind, List.of());
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

        g.drawCenteredString(this.font, I18n.get("gui.tacz_sewv.tdt.float_select_all"),
                fx + fw / 2, fy + pad + 2, COL_ACCENT);

        int ix = fx + pad;
        int iy = fy + pad + 16;
        ResourceLocation tex = KIND_TEX.get(this.floatKind);
        for (TdtSelection.Entry unit : units) {
            boolean sel = TdtSelection.isSelected(unit.id());
            if (sel) {
                g.fill(ix - 1, iy - 1, ix + FLOAT_ICON + 1, iy + FLOAT_ICON + 1, COL_ACCENT);
            }
            g.setColor(1.0f, 1.0f, 1.0f, sel ? 1.0f : 0.45f);
            g.blit(tex, ix, iy, 0, 0, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON, FLOAT_ICON);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            ix += FLOAT_ICON + 4;
            if (ix + FLOAT_ICON > fx + fw - pad) {
                ix = fx + pad;
                iy += FLOAT_ICON + 4;
            }
        }
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
