package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    /**
     * Action-bar result of an order: {@code base + ".none"/".single"/".multiple"} by count —
     * GRAY when nothing took the order, {@code color} otherwise. Every caller passes the count
     * of units the SERVER actually accepted, never the client's optimistic guess.
     */
    public static void orderFeedback(Player player, String base, int count, ChatFormatting color, Object... args) {
        if (!SewvConfig.SHOW_ORDER_FEEDBACK.get()) return;
        String key = base + (count == 0 ? ".none" : count == 1 ? ".single" : ".multiple");
        player.displayClientMessage(Component.translatable(key, args)
                .withStyle(count == 0 ? ChatFormatting.GRAY : color), true);
    }

    // Bumped when the wire format changes (2: list sizes/ids became VarInts; 3: added the mortar
    // order; 4: added the vehicle formation order; 5: added the patrol order; 6: formation carries
    // a shape id + row size, and the heli command carries a cruise altitude; 7: board carries a
    // passenger-only flag and the area-task order carries a patrol/search mode; 8: added the escort
    // order; 9: added the owned-vehicle map sync, this channel's first server->client packet;
    // 10: map markers carry an allegiance so other factions can be shown; 11: the area task carries
    // an optional origin, so it can be centred on a map click instead of on the sender; 12: the
    // area task also carries a cruise route) so a mismatched client/server pair is rejected at
    // handshake instead of misparsing.
    private static final String PROTOCOL_VERSION = "12";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TaczSewv.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    public static void register() {
        CHANNEL.registerMessage(
                nextId(),
                PacketBoardVehicle.class,
                PacketBoardVehicle::encode,
                PacketBoardVehicle::new,
                PacketBoardVehicle::handle
        );

        CHANNEL.registerMessage(
        nextId(),
        PacketDismountVehicle.class,
        PacketDismountVehicle::encode,
        PacketDismountVehicle::new,
        PacketDismountVehicle::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketHelicopterCommand.class,
                PacketHelicopterCommand::encode,
                PacketHelicopterCommand::new,
                PacketHelicopterCommand::handle
        );

        // nextId() is a plain counter, so new packets go on the end — inserting one
        // above would renumber every packet after it.
        CHANNEL.registerMessage(
                nextId(),
                PacketManMortar.class,
                PacketManMortar::encode,
                PacketManMortar::new,
                PacketManMortar::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketVehicleFormation.class,
                PacketVehicleFormation::encode,
                PacketVehicleFormation::new,
                PacketVehicleFormation::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketPatrolVehicle.class,
                PacketPatrolVehicle::encode,
                PacketPatrolVehicle::new,
                PacketPatrolVehicle::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketEscort.class,
                PacketEscort::encode,
                PacketEscort::new,
                PacketEscort::handle
        );

        // The one server->client packet here (PacketDistributor.PLAYER, see OwnedVehicleTracker).
        CHANNEL.registerMessage(
                nextId(),
                PacketOwnedVehicles.class,
                PacketOwnedVehicles::encode,
                PacketOwnedVehicles::new,
                PacketOwnedVehicles::handle
        );
    }
}