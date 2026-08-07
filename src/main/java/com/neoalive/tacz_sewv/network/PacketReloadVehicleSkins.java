package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;

/** S→C: re-scan {@code config/tacz_sewv/vehicle_skins/} and re-register DynamicTextures. */
public final class PacketReloadVehicleSkins {

    public PacketReloadVehicleSkins() {
    }

    public PacketReloadVehicleSkins(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> VehicleSkinRegistry::reload));
        ctx.get().setPacketHandled(true);
    }
}
