package com.neoalive.tacz_sewv.platform;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * NeoForge port: 1.21 item stacks carry data components, so building or serialising one needs a
 * {@link HolderLookup.Provider}. Most call sites have a level and should pass
 * {@code level.registryAccess()}; this is for the few static helpers that do not (sidearm builders,
 * catalog probes). Server registries first, then the client level's, then the built-ins.
 */
public final class RegistryLookup {

    private RegistryLookup() {}

    public static HolderLookup.Provider current() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            HolderLookup.Provider client = ClientAccess.registries();
            if (client != null) return client;
        }
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    /** Only loaded on the client (guarded by the dist check above). */
    private static final class ClientAccess {
        static HolderLookup.Provider registries() {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            return level == null ? null : level.registryAccess();
        }
    }
}
