package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.FormationShape;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Command terminal opened by the Tactical Data Terminal item. A textureless menu of order buttons
 * in vertical columns by category: Ground, Air, Formations.
 *
 * <p>Board, Land and every formation need what the player was aiming at / facing, but a screen
 * releases the mouse — so the board target, the landing block and the formation axis are all
 * captured from the crosshair/facing in {@link #open()} the moment the terminal opens, and the
 * buttons act on that snapshot. Patrol needs no aim; its origin is the player.
 */
public class TdtScreen extends Screen {

    private static final int COL_W = 100;
    private static final int COL_GAP = 16;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;
    private static final int ROW_H = BTN_H + BTN_GAP;
    // Extra breathing room between an area-task control group and whatever follows it.
    private static final int GROUP_GAP = 8;
    private static final int STEP_BTN_W = 22; // the +/- buttons flanking a stepper's readout

    private static final int RADIUS_STEP = 16;
    private static final int DEFAULT_RADIUS = 256;
    // The radius stepper floors below MIN_RADIUS on purpose, so lowering it too far and pressing
    // Patrol surfaces the "too small" message instead of silently clamping.
    private static final int RADIUS_FLOOR = RADIUS_STEP;
    private static final int ALT_STEP = 5;

    @Nullable
    private final Entity boardTarget; // vehicle or mortar under the crosshair at open
    @Nullable
    private final BlockPos landPad;   // long-range block pick at open
    private final int formationAxis;  // the cardinal the player faced at open (IFormationMember id)

    // Stepper values are STATIC: they are the player's standing preference, not screen state, so
    // they survive closing the terminal instead of snapping back to the default on every open. That
    // also makes them the one place the world map's order menu reads its radius and altitude from —
    // the map has no room for a stepper, and a second set of defaults would drift from these.
    private static int patrolRadius = DEFAULT_RADIUS;
    private static int searchRadius = DEFAULT_RADIUS;
    private static int heliAltitude = IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
    private static int lineRowSize = PacketVehicleFormation.DEFAULT_ROW_SIZE;

    /** Last patrol radius the player set on the terminal, for callers with no stepper of their own. */
    public static int patrolRadius() {
        return patrolRadius;
    }

    /** Last search radius the player set on the terminal. */
    public static int searchRadius() {
        return searchRadius;
    }

    /** Last cruise altitude the player set on the terminal. */
    public static int heliAltitude() {
        return heliAltitude;
    }

    private int leftX;   // Ground column
    private int midX;    // Air column
    private int rightX;  // Formations column
    private int headerY;

    private final List<StepperReadout> readouts = new ArrayList<>();

    private record StepperReadout(int cx, int y, IntSupplier value, String suffix, int redBelow) {}

    private TdtScreen(@Nullable Entity boardTarget, @Nullable BlockPos landPad, int formationAxis) {
        super(Component.translatable("gui.tacz_sewv.tdt.title"));
        this.boardTarget = boardTarget;
        this.landPad = landPad;
        this.formationAxis = formationAxis;
    }

    /** Client-side entry from the item: snapshot the aim and facing, then show the terminal. */
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
        this.readouts.clear();
        // Resolved once per screen open rather than hardcoded literals, so the stepper suffixes
        // are translatable like every other piece of text in this screen.
        String unitBlocks = I18n.get("gui.tacz_sewv.tdt.unit.blocks");
        String unitPerRow = I18n.get("gui.tacz_sewv.tdt.unit.per_row");
        int totalW = COL_W * 3 + COL_GAP * 2;
        this.leftX = (this.width - totalW) / 2;
        this.midX = this.leftX + COL_W + COL_GAP;
        this.rightX = this.midX + COL_W + COL_GAP;
        this.headerY = this.height / 2 - 105;
        int btnY = this.headerY + 14;

        // Ground column. Walked with a cursor rather than fixed row indices: each area task is a
        // button plus its own stepper, and GROUP_GAP sets those pairs apart so they read as one
        // control each instead of a uniform stack.
        int y = btnY;
        addColumnButton(this.leftX, y, "gui.tacz_sewv.tdt.board",
                () -> BoardKeybind.orderBoard(this.boardTarget, false));
        y += ROW_H;
        addColumnButton(this.leftX, y, "gui.tacz_sewv.tdt.board_passenger",
                () -> BoardKeybind.orderBoard(this.boardTarget, true), "gui.tacz_sewv.tdt.board_passenger.tip");
        y += ROW_H;
        addColumnButton(this.leftX, y, "gui.tacz_sewv.tdt.dismount", BoardKeybind::orderDismount);
        y += ROW_H;
        // Escort closes the terminal and arms a selection mode (left-click a vehicle to escort,
        // right-click to cancel) — the vehicle can't be aim-captured at open like Board's, because
        // the player picks it deliberately AFTER choosing the order. armEscort() does the closing
        // itself via addColumnButton's onClose().
        addColumnButton(this.leftX, y, "gui.tacz_sewv.tdt.escort",
                ClientEvents::armEscort, "gui.tacz_sewv.tdt.escort.tip");
        y += ROW_H + GROUP_GAP;

        addButton(this.leftX, y, "gui.tacz_sewv.tdt.patrol",
                () -> orderAreaTask(patrolRadius, IVehiclePatrol.MODE_PATROL), "gui.tacz_sewv.tdt.patrol.tip");
        y += ROW_H;
        addStepper(this.leftX, y, () -> patrolRadius, v -> patrolRadius = v,
                RADIUS_FLOOR, PacketPatrolVehicle.MAX_RADIUS, RADIUS_STEP, unitBlocks, PacketPatrolVehicle.MIN_RADIUS,
                "gui.tacz_sewv.tdt.patrol.tip");
        y += ROW_H + GROUP_GAP;

        addButton(this.leftX, y, "gui.tacz_sewv.tdt.search",
                () -> orderAreaTask(searchRadius, IVehiclePatrol.MODE_SEARCH), "gui.tacz_sewv.tdt.search.tip");
        y += ROW_H;
        addStepper(this.leftX, y, () -> searchRadius, v -> searchRadius = v,
                RADIUS_FLOOR, PacketPatrolVehicle.MAX_RADIUS, RADIUS_STEP, unitBlocks, PacketPatrolVehicle.MIN_RADIUS,
                "gui.tacz_sewv.tdt.search.tip");
        y += ROW_H + GROUP_GAP;

        // Stands crews down off either area task, back onto their normal AI — without emptying
        // seats the way Dismount does.
        addColumnButton(this.leftX, y, "gui.tacz_sewv.tdt.dismiss",
                () -> BoardKeybind.orderAreaTask(0, PacketPatrolVehicle.MODE_DISMISS),
                "gui.tacz_sewv.tdt.dismiss.tip");

        // Air column: takeoff, its live cruise-altitude stepper, then land.
        addColumnButton(this.midX, btnY, "gui.tacz_sewv.tdt.takeoff",
                () -> HelicopterKeybind.orderTakeoff(heliAltitude));
        addStepper(this.midX, rowY(btnY, 1), () -> heliAltitude, v -> heliAltitude = v,
                PacketHelicopterCommand.MIN_ALTITUDE, PacketHelicopterCommand.MAX_ALTITUDE, ALT_STEP, unitBlocks, 0,
                "gui.tacz_sewv.tdt.altitude.tip");
        addColumnButton(this.midX, rowY(btnY, 2), "gui.tacz_sewv.tdt.land",
                () -> HelicopterKeybind.orderLand(this.landPad));

        // Formations column: wedge / column / line + its row-size stepper / echelon left / right.
        addFormationButton(this.rightX, btnY, "gui.tacz_sewv.tdt.wedge", FormationShape.WEDGE);
        addFormationButton(this.rightX, rowY(btnY, 1), "gui.tacz_sewv.tdt.column", FormationShape.COLUMN);
        addFormationButton(this.rightX, rowY(btnY, 2), "gui.tacz_sewv.tdt.line", FormationShape.LINE);
        addStepper(this.rightX, rowY(btnY, 3), () -> lineRowSize, v -> lineRowSize = v,
                PacketVehicleFormation.MIN_ROW_SIZE, PacketVehicleFormation.MAX_ROW_SIZE, 1, unitPerRow, 0,
                "gui.tacz_sewv.tdt.line.tip");
        addFormationButton(this.rightX, rowY(btnY, 4), "gui.tacz_sewv.tdt.echelon_left", FormationShape.ECHELON_LEFT);
        addFormationButton(this.rightX, rowY(btnY, 5), "gui.tacz_sewv.tdt.echelon_right", FormationShape.ECHELON_RIGHT);
    }

    private static int rowY(int firstRowY, int row) {
        return firstRowY + row * ROW_H;
    }

    private void addColumnButton(int x, int y, String key, Runnable order) {
        addColumnButton(x, y, key, order, null);
    }

    private void addColumnButton(int x, int y, String key, Runnable order, @Nullable String tooltipKey) {
        addButton(x, y, key, () -> {
            order.run();
            onClose();
        }, tooltipKey);
    }

    /**
     * Fires the handler and nothing else — the handler decides whether to close. The area tasks
     * need this: they reject a radius under the floor and must leave the terminal up so the player
     * can correct it, which {@link #addColumnButton} would undo by closing regardless.
     */
    private void addButton(int x, int y, String key, Runnable onPress, @Nullable String tooltipKey) {
        Button.Builder builder = Button.builder(Component.translatable(key), b -> onPress.run())
                .bounds(x, y, COL_W, BTN_H);
        if (tooltipKey != null) builder.tooltip(Tooltip.create(Component.translatable(tooltipKey)));
        addRenderableWidget(builder.build());
    }

    private void addFormationButton(int x, int y, String key, FormationShape shape) {
        addColumnButton(x, y, key, () -> BoardKeybind.orderFormation(shape, this.formationAxis, lineRowSize));
    }

    // [+][value][-] : + raises, - lowers, both clamped; the value is drawn between them in render().
    // The tooltip goes on both buttons, since the readout between them is drawn text, not a widget.
    private void addStepper(int x, int y, IntSupplier get, IntConsumer set,
                            int min, int max, int step, String suffix, int redBelow, String tooltipKey) {
        Tooltip tip = Tooltip.create(Component.translatable(tooltipKey));
        addRenderableWidget(Button.builder(Component.literal("+"),
                b -> set.accept(Math.min(max, get.getAsInt() + step)))
                .tooltip(tip).bounds(x, y, STEP_BTN_W, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("-"),
                b -> set.accept(Math.max(min, get.getAsInt() - step)))
                .tooltip(tip).bounds(x + COL_W - STEP_BTN_W, y, STEP_BTN_W, BTN_H).build());
        this.readouts.add(new StepperReadout(x + COL_W / 2, y, get, suffix, redBelow));
    }

    // Both area tasks keep the terminal open on a rejected radius so the player can correct it.
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.headerY - 20, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.tdt.col.ground"),
                this.leftX + COL_W / 2, this.headerY, 0xFFA0A0A0);
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.tdt.col.air"),
                this.midX + COL_W / 2, this.headerY, 0xFFA0A0A0);
        g.drawCenteredString(this.font, Component.translatable("gui.tacz_sewv.tdt.col.formations"),
                this.rightX + COL_W / 2, this.headerY, 0xFFA0A0A0);
        super.render(g, mouseX, mouseY, partialTick);
        // Stepper readouts, drawn over the gap between each +/- pair, red when below their floor.
        for (StepperReadout r : this.readouts) {
            int value = r.value().getAsInt();
            int color = value < r.redBelow() ? 0xFFFF5555 : 0xFFFFFFFF;
            g.drawCenteredString(this.font, value + r.suffix(), r.cx(), r.y() + (BTN_H - 8) / 2, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
