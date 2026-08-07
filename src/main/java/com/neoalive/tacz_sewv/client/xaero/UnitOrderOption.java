package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
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
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketReachGuard;
import com.neoalive.tacz_sewv.network.PacketSweepAndAdvance;

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
        FOLLOW("follow_me", "message.tacz_sewv.map.following", false, Category.MOVEMENT),
        HOLD("hold", "message.tacz_sewv.map.held", false, Category.STANCE),
        FREE_FIRE("free_fire", "message.tacz_sewv.map.free_fire", false, Category.STANCE),
        CEASE_FIRE("cease_fire", "message.tacz_sewv.map.cease_fire", false, Category.STANCE),
        TAKEOFF("takeoff", null, false, Category.AIR),
        LAND_HERE("land_here", null, true, Category.AIR),
        PATROL_HERE("patrol_here", null, true, Category.AREA_TASK),
        SAD_HERE("sad_here", null, true, Category.AREA_TASK),
        SWEEP_AND_ADVANCE("sweep_and_advance", null, true, Category.AREA_TASK),
        CRUISE("cruise", null, false, Category.MOVEMENT),
        SET_GUARD("set_guard", null, false, Category.MOVEMENT),
        REACH_GUARD("reach_guard", null, false, Category.MOVEMENT),
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
    private final int selLeft;
    private final int selTop;
    private final int selRight;
    private final int selBottom;
    private final boolean hasTileSelection;

    public UnitOrderOption(int index, IRightClickableElement target, Action action,
                           int x, int y, int z, ResourceKey<Level> dimension, int selectedCount,
                           MapTileSelection tileSelection) {
        super(action.labelKey, action.category.style, index, target);
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        if (tileSelection != null) {
            this.hasTileSelection = true;
            this.selLeft = tileSelection.getLeft();
            this.selTop = tileSelection.getTop();
            this.selRight = tileSelection.getRight();
            this.selBottom = tileSelection.getBottom();
        } else {
            this.hasTileSelection = false;
            this.selLeft = this.selTop = this.selRight = this.selBottom = 0;
        }
        boolean active = selectedCount > 0;
        if (action == Action.SWEEP_AND_ADVANCE) {
            active = active && hasTileSelection;
        }
        if (action == Action.REACH_GUARD) {
            active = active && selectedHaveGuard();
        }
        setActive(active);
        setNameFormatArgs(selectedCount);
    }

    @Override
    public void onAction(Screen screen) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (InvasionHudClient.isActive()) {
            hint("message.tacz_sewv.invasion.orders_locked");
            return;
        }

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
            if (CruisePlot.arm()) hint("message.tacz_sewv.cruise.plotting", 0);
            return;
        }

        if (this.action == Action.SET_GUARD) {
            if (GuardPlot.arm()) hint("message.tacz_sewv.guard.plotting");
            return;
        }

        if (this.action == Action.REACH_GUARD) {
            NetworkHandler.CHANNEL.sendToServer(new PacketReachGuard(new ArrayList<>(drivers)));
            return;
        }

        if (this.action == Action.DISMISS) {
            // Stands crews off patrol, search AND cruise in one go — they share one state slot, so
            // the server needs no idea which of the three it is cancelling.
            NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(
                    new ArrayList<>(drivers), 0, PacketPatrolVehicle.MODE_DISMISS));
            return;
        }

        if (this.action == Action.PATROL_HERE || this.action == Action.SAD_HERE) {
            // Radius comes from the terminal's stepper, which is the player's standing preference
            // and survives the screen being closed — a right-click menu has nowhere to put one.
            // The area centres on the CLICKED point, which is the whole reason these are map orders.
            boolean search = this.action == Action.SAD_HERE;
            NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(new ArrayList<>(drivers),
                    search ? TdtScreen.searchRadius() : TdtScreen.patrolRadius(),
                    search ? IVehiclePatrol.MODE_SEARCH : IVehiclePatrol.MODE_PATROL,
                    mapPos(player)));
            return;
        }

        if (this.action == Action.SWEEP_AND_ADVANCE) {
            if (!this.hasTileSelection) {
                hint("message.tacz_sewv.sweep.need_selection");
                return;
            }
            ResourceKey<Level> dim = this.dimension != null ? this.dimension : player.level().dimension();
            NetworkHandler.CHANNEL.sendToServer(new PacketSweepAndAdvance(
                    new ArrayList<>(drivers), dim.location(),
                    this.selLeft, this.selTop, this.selRight, this.selBottom));
            return;
        }

        if (this.action == Action.TAKEOFF) {
            // This mod's own channel: flight state is IHelicopterPilot, not a SEM order. The server
            // filters the selection down to actual aircraft pilots and reports the count itself.
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    new ArrayList<>(drivers), IHelicopterPilot.HELI_CMD_TAKEOFF, null,
                    TdtScreen.heliAltitude()));
            return;
        }

        if (this.action == Action.LAND_HERE) {
            // Land the aircraft ON the clicked point (its pad), same flight channel as takeoff. A plane
            // flies a glideslope onto it; a helicopter sets down vertically. Server filters to pilots.
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    new ArrayList<>(drivers), IHelicopterPilot.HELI_CMD_LANDING, mapPos(player), 0));
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
            case FOLLOW -> OrderType.FOLLOW_COMMANDER;
            case HOLD -> OrderType.HOLD_POSITION;
            case FREE_FIRE -> OrderType.FREE_FIRE;
            case CEASE_FIRE -> OrderType.CEASE_FIRE;
            case TAKEOFF, LAND_HERE, PATROL_HERE, SAD_HERE, SWEEP_AND_ADVANCE, CRUISE,
                    SET_GUARD, REACH_GUARD, DISMISS ->
                    throw new IllegalStateException(this.action + " is not a SEM order");
        };
    }

    /** True when every currently selected OWN marker reports a cached guard position. */
    private static boolean selectedHaveGuard() {
        Set<Integer> selected = MapMarkers.selected();
        if (selected.isEmpty()) return false;
        for (int id : selected) {
            boolean found = false;
            for (VehicleMarker m : MapMarkers.markers()) {
                if (m.driverId() != id) continue;
                found = true;
                if (!m.hasGuard()) return false;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    /**
     * Height is unknown on an unexplored tile. The player's own Y is the best guess there is, and
     * the crew's navigation drops onto the ground from it; 32767 would just be nonsense.
     */
    private Vec3 destination(Player player) {
        double destY = this.y == NO_HEIGHT ? player.getY() : this.y + 1;
        return new Vec3(this.x + 0.5, destY, this.z + 0.5);
    }

    /**
     * The BlockPos the player clicked on the MAP — X/Z from the click, Y from the surface there (or
     * the player's own Y on an unexplored tile). The single source of the map-selected position for
     * every map-positional order (patrol/search/land here), so none of them read the player's spot.
     */
    private BlockPos mapPos(Player player) {
        return new BlockPos(this.x, (int) destination(player).y, this.z);
    }

    /** Every order entry, in menu order, for the position the player right-clicked. */
    public static List<RightClickOption> allFor(int firstIndex, IRightClickableElement target,
                                                int x, int y, int z, ResourceKey<Level> dimension,
                                                int selectedCount, MapTileSelection tileSelection) {
        List<RightClickOption> options = new ArrayList<>();
        for (Action action : Action.values()) {
            options.add(new UnitOrderOption(firstIndex + options.size(), target, action,
                    x, y, z, dimension, selectedCount, tileSelection));
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
