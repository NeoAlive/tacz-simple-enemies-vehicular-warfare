package com.neoalive.tacz_sewv.platform;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * NeoForge port: 1.21 dropped {@code FriendlyByteBuf.readComponent/writeComponent}; components now
 * need registry access, which every play payload buffer has (it is a {@link RegistryFriendlyByteBuf}
 * — see {@link SimpleChannel}). Message classes keep their {@code FriendlyByteBuf} signatures.
 */
public final class BufCompat {

    private BufCompat() {}

    public static Component readComponent(FriendlyByteBuf buf) {
        return ComponentSerialization.TRUSTED_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf);
    }

    public static void writeComponent(FriendlyByteBuf buf, Component component) {
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, component);
    }
}
