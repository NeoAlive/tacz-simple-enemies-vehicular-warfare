package com.neoalive.tacz_sewv.client.xaero;

import com.neoalive.tacz_sewv.client.MapMarkers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.Set;

/**
 * "Move selected units here" in the world map's right-click menu.
 *
 * <p>A class of its own rather than an anonymous one inside {@code MixinGuiMap}: an inner class
 * declared in a mixin gets merged into the target along with whatever synthetic accessors javac
 * emitted for it, which is exactly the kind of thing that works until it doesn't. The clicked
 * position is passed in, so this holds no reference to the map screen beyond the menu target Xaero
 * requires.
 *
 * <p>The order goes out on <b>SEM's</b> channel as its own {@code PacketIssueOrder}: it already
 * means {@code MOVE_TO_POSITION}, already refuses units the sender does not own, and a mounted
 * crew's drive goal already resolves that order into a destination. One packet per selected driver
 * — SEM commands units one at a time, and the units are named by their driver because that is who
 * the drive goal runs on.
 */
public class MoveHereOption extends RightClickOption {

    /** Xaero's "no surface height known here" sentinel — an unexplored tile, or cave mode. */
    private static final int NO_HEIGHT = 32767;

    private final int x;
    private final int y;
    private final int z;
    private final ResourceKey<Level> dimension;

    public MoveHereOption(int index, IRightClickableElement target,
                          int x, int y, int z, ResourceKey<Level> dimension, int selectedCount) {
        super("gui.tacz_sewv.map.move_here", index, target);
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

        // A move order across dimensions means nothing to a ground crew, and the coordinates would
        // be read in the wrong frame anyway.
        if (this.dimension != null && !this.dimension.equals(player.level().dimension())) {
            hint("message.tacz_sewv.map.wrong_dimension");
            return;
        }

        // Height is unknown on an unexplored tile. The player's own Y is the best guess there is,
        // and the crew's navigation drops onto the ground from it; 32767 would just be nonsense.
        double destY = this.y == NO_HEIGHT ? player.getY() : this.y + 1;
        Vec3 destination = new Vec3(this.x + 0.5, destY, this.z + 0.5);

        Set<Integer> drivers = MapMarkers.selected();
        for (int driverId : drivers) {
            ModNetworking.CHANNEL.sendToServer(
                    new PacketIssueOrder(driverId, OrderType.MOVE_TO_POSITION, destination, 0, -1));
        }
        MapMarkers.clearSelection();
        hint(drivers.size() == 1 ? "message.tacz_sewv.map.ordered.single"
                                 : "message.tacz_sewv.map.ordered.multiple", drivers.size());
    }

    private static void hint(String key, Object... args) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
