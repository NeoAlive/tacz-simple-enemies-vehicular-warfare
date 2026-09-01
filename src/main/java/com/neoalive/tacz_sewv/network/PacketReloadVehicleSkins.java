package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;

/**
 * S→C: re-scan {@code config/tacz_sewv/vehicle_skins/}, {@code armor_skins/},
 * {@code unit_skins/}, and legacy {@code skin_pools/}, re-registering DynamicTextures.
 *
 * <p>With {@code reset}, the folders are emptied and re-seeded from the jar first. That has to
 * happen on the client — the registries read the <b>client's</b> config folder, which on a
 * dedicated server the command's own machine has no access to.
 */
public final class PacketReloadVehicleSkins {

    private final boolean reset;

    public PacketReloadVehicleSkins() {
        this(false);
    }

    public PacketReloadVehicleSkins(boolean reset) {
        this.reset = reset;
    }

    public PacketReloadVehicleSkins(FriendlyByteBuf buf) {
        this.reset = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.reset);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        boolean doReset = this.reset;
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            if (doReset) {
                VehicleSkinRegistry.resetToDefaults(resources);
                CrewSkinRegistry.resetToDefaults(resources);
            } else {
                VehicleSkinRegistry.reload(resources);
                CrewSkinRegistry.reload(resources);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
