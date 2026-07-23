package com.neoalive.tacz_sewv.client.xaero;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One order in the world map's right-click menu, issued to every marker the player has selected.
 *
 * <p>A single class over an {@link Action} enum rather than one class per order: the entries differ
 * only in which packet they send and what they are called, and Xaero's menu wants a flat list of
 * {@link RightClickOption}s anyway. It is a real class and not an anonymous one inside
 * {@code MixinGuiMap} because an inner class declared in a mixin gets merged into the target along
 * with whatever synthetic accessors javac emitted for it.
 *
 * <p>Most of these ride <b>SEM's own {@code PacketIssueOrder}</b>: it already carries the whole
 * {@code OrderType} set, already refuses a unit the sender does not own, and the drive goal already
 * resolves each order for a mounted crew — so this mod owns no order packet for them, and the
 * crew's radio acknowledgement (see {@code MixinPacketIssueOrder}) comes along for free. Takeoff is
 * the exception: flight state is this mod's, so it goes on this mod's channel.
 *
 * <p>Patrol and search are the other two that need the clicked point: they centre their area on it
 * instead of on the player, which is the whole reason the packet learned to carry an origin. Both
 * also stand the crew off any standing SEM order, and conversely any SEM order stands it off the
 * area task ({@code MixinPacketIssueOrder}) — an area task outranks the order queue, so without
 * that pair the second order of any such pair would silently do nothing.
 *
 * <p>Entries carry a symbol (in the lang file, so a pack can strip them) and a category colour.
 * The symbols are plain BMP characters — arrows and geometric shapes — which render on any client:
 * Minecraft's default font falls back to unifont, shipped through the asset index, for everything
 * outside its own glyph sheets. Nothing here uses emoji, which have no such fallback.
 *
 * <p><b>Only {@link Action#MOVE} clears the selection</b>, because a move is a dispatch — the
 * selection has done its job. The rest are stance changes you may well want to stack ("cease fire,
 * then move there"), so they leave it alone.
 */
public class UnitOrderOption extends RightClickOption {

    /** Xaero's "no surface height known here" sentinel — an unexplored tile, or cave mode. */
    private static final int NO_HEIGHT = 32767;

    /**
     * What the entry does. {@code ackKey} is null where the <b>server</b> reports the result
     * (takeoff counts how many of the selection were actually helicopter pilots, which the client
     * cannot know), so the player never gets told twice.
     */
    /**
     * The colour families the menu is grouped by, mirroring the terminal's columns — the map has no
     * room for headers, so colour carries the grouping instead. Xaero applies this through the
     * {@code Style} its own entries use for the grey coordinate readout, and it loses to
     * {@code DARK_GRAY} whenever the entry is inactive, so an unavailable order still reads as
     * unavailable rather than as its category.
     */
    private enum Category {
        MOVEMENT(ChatFormatting.AQUA),
        STANCE(ChatFormatting.GREEN),
        AREA_TASK(ChatFormatting.YELLOW),
        AIR(ChatFormatting.LIGHT_PURPLE),
        STAND_DOWN(ChatFormatting.RED);

        final Style style;

        Category(ChatFormatting color) {
            this.style = Style.EMPTY.withColor(color);
        }
    }

    public enum Action {
        MOVE("move_here", "message.tacz_sewv.map.ordered", true, Category.MOVEMENT),
        HOLD("hold", "message.tacz_sewv.map.held", false, Category.STANCE),
        FREE_FIRE("free_fire", "message.tacz_sewv.map.free_fire", false, Category.STANCE),
        CEASE_FIRE("cease_fire", "message.tacz_sewv.map.cease_fire", false, Category.STANCE),
        TAKEOFF("takeoff", null, false, Category.AIR),
        PATROL("patrol", null, true, Category.AREA_TASK),
        SEARCH("search", null, true, Category.AREA_TASK),
        CRUISE("cruise", null, false, Category.MOVEMENT),
        DISMISS("dismiss", null, false, Category.STAND_DOWN);

        final String labelKey;
        final String ackKey;
        final boolean positional;
        final Category category;

        Action(String label, String ackKey, boolean positional, Category category) {
            this.labelKey = "gui.tacz_sewv.map." + label;
            this.ackKey = ackKey;
            this.positional = positional;
            this.category = category;
        }
    }

    private final Action action;
    private final int x;
    private final int y;
    private final int z;
    private final ResourceKey<Level> dimension;

    public UnitOrderOption(int index, IRightClickableElement target, Action action,
                           int x, int y, int z, ResourceKey<Level> dimension, int selectedCount) {
        super(action.labelKey, action.category.style, index, target);
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        setActive(selectedCount > 0);
        setNameFormatArgs(selectedCount);
    }

    @Override
    public void onAction(Screen screen) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        Set<Integer> drivers = MapMarkers.selected();
        if (drivers.isEmpty()) return;

        // A positional order across dimensions means nothing to a ground crew, and the coordinates
        // would be read in the wrong frame anyway.
        if (this.action.positional && this.dimension != null
                && !this.dimension.equals(player.level().dimension())) {
            hint("message.tacz_sewv.map.wrong_dimension");
            return;
        }

        if (this.action == Action.CRUISE) {
            // Arms the plotting mode instead of ordering anything: the route does not exist yet.
            // MixinGuiMap owns everything from here — clicks, the drawn route, confirm and cancel.
            if (CruisePlot.arm()) hint("message.tacz_sewv.cruise.plotting", 0);
            return;
        }

        if (this.action == Action.DISMISS) {
            // Stands crews off patrol, search AND cruise in one go — they share one state slot, so
            // the server needs no idea which of the three it is cancelling.
            NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(
                    new ArrayList<>(drivers), 0, PacketPatrolVehicle.MODE_DISMISS));
            return;
        }

        if (this.action == Action.PATROL || this.action == Action.SEARCH) {
            // Radius comes from the terminal's stepper, which is the player's standing preference
            // and survives the screen being closed — a right-click menu has nowhere to put one.
            boolean search = this.action == Action.SEARCH;
            NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(new ArrayList<>(drivers),
                    search ? TdtScreen.searchRadius() : TdtScreen.patrolRadius(),
                    search ? IVehiclePatrol.MODE_SEARCH : IVehiclePatrol.MODE_PATROL,
                    new BlockPos(this.x, (int) destination(player).y, this.z)));
            return;
        }

        if (this.action == Action.TAKEOFF) {
            // This mod's own channel: flight state is IHelicopterPilot, not a SEM order. The server
            // filters the selection down to actual helicopter pilots and reports the count itself.
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    new ArrayList<>(drivers), IHelicopterPilot.HELI_CMD_TAKEOFF, null,
                    TdtScreen.heliAltitude()));
            return;
        }

        Vec3 destination = this.action.positional ? destination(player) : Vec3.ZERO;
        for (int driverId : drivers) {
            ModNetworking.CHANNEL.sendToServer(
                    new PacketIssueOrder(driverId, orderType(), destination, 0, -1));
        }
        if (this.action == Action.MOVE) MapMarkers.clearSelection();
        if (this.action.ackKey != null) hint(this.action.ackKey, drivers.size());
    }

    private OrderType orderType() {
        return switch (this.action) {
            case MOVE -> OrderType.MOVE_TO_POSITION;
            case HOLD -> OrderType.HOLD_POSITION;
            case FREE_FIRE -> OrderType.FREE_FIRE;
            case CEASE_FIRE -> OrderType.CEASE_FIRE;
            case TAKEOFF, PATROL, SEARCH, CRUISE, DISMISS ->
                    throw new IllegalStateException(this.action + " is not a SEM order");
        };
    }

    /**
     * Height is unknown on an unexplored tile. The player's own Y is the best guess there is, and
     * the crew's navigation drops onto the ground from it; 32767 would just be nonsense.
     */
    private Vec3 destination(Player player) {
        double destY = this.y == NO_HEIGHT ? player.getY() : this.y + 1;
        return new Vec3(this.x + 0.5, destY, this.z + 0.5);
    }

    /** Every order entry, in menu order, for the position the player right-clicked. */
    public static List<RightClickOption> allFor(int firstIndex, IRightClickableElement target,
                                                int x, int y, int z, ResourceKey<Level> dimension,
                                                int selectedCount) {
        List<RightClickOption> options = new ArrayList<>();
        for (Action action : Action.values()) {
            options.add(new UnitOrderOption(firstIndex + options.size(), target, action,
                    x, y, z, dimension, selectedCount));
        }
        return options;
    }

    private static void hint(String key, Object... args) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
