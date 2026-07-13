package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    // Bumped when the wire format changes (2: list sizes/ids became VarInts) so a
    // mismatched client/server pair is rejected at handshake instead of misparsing.
    private static final String PROTOCOL_VERSION = "2";

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
    }
}