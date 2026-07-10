package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import com.neoalive.tacz_sewv.client.BoardKeybind;

public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

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
    }
}