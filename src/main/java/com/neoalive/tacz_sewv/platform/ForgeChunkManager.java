package com.neoalive.tacz_sewv.platform;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Stand-in for Forge's {@code ForgeChunkManager}: the same {@code forceChunk(level, modId, owner,
 * x, z, add, ticking)} calls, routed through this mod's single NeoForge {@link TicketController}.
 * The {@code modId} argument is kept for call-site parity and must be this mod's.
 */
public final class ForgeChunkManager {

    private static volatile LoadingValidationCallback validation;

    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "tickets"),
            (level, helper) -> {
                LoadingValidationCallback cb = validation;
                if (cb != null) cb.validateTickets(level, helper);
            });

    private ForgeChunkManager() {}

    /** Mod-bus listener; {@code TaczSewv} adds it. */
    public static void onRegisterControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /** Forge's {@code setForcedChunkLoadingCallback}: runs as saved tickets are about to be reinstated. */
    public static void setForcedChunkLoadingCallback(String modId, LoadingValidationCallback callback) {
        checkModId(modId);
        validation = callback;
    }

    public static boolean forceChunk(ServerLevel level, String modId, Entity owner, int chunkX, int chunkZ,
                                     boolean add, boolean ticking) {
        checkModId(modId);
        return CONTROLLER.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }

    public static boolean forceChunk(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ,
                                     boolean add, boolean ticking) {
        checkModId(modId);
        return CONTROLLER.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }

    public static boolean forceChunk(ServerLevel level, String modId, BlockPos owner, int chunkX, int chunkZ,
                                     boolean add, boolean ticking) {
        checkModId(modId);
        return CONTROLLER.forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }

    private static void checkModId(String modId) {
        if (!TaczSewv.MODID.equals(modId)) {
            throw new IllegalArgumentException("Only " + TaczSewv.MODID + " tickets go through this controller, got " + modId);
        }
    }
}
