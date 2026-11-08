package com.neoalive.tacz_sewv.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /sewv relight [radiusChunks]}: rechecks block light around the player to clear light
 * left in the world by flashes that ended without a recheck.
 */
public final class RelightCommand {

    static final int DEFAULT_RADIUS = 8;
    static final int MAX_RADIUS = 32;
    static final int SECTIONS_PER_TICK = 48;

    private static final List<Running> RUNNING = new ArrayList<>();
    private static boolean listening;

    private RelightCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .then(Commands.literal("relight")
                        .requires(RelightCommand::allowed)
                        .executes(ctx -> start(ctx.getSource(), DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                .executes(ctx -> start(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static boolean allowed(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        MinecraftServer server = source.getServer();
        return server.isSingleplayer() && source.getEntity() instanceof ServerPlayer player
                && server.isSingleplayerOwner(player.getGameProfile());
    }

    private static int start(CommandSourceStack source, int radius) {
        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
        RUNNING.add(new Running(source, new RelightJob(source.getLevel(), center.x, center.z, radius)));
        if (!listening) {
            MinecraftForge.EVENT_BUS.register(RelightCommand.class);
            listening = true;
        }
        source.sendSuccess(() -> Component.literal("Relighting " + (radius * 2 + 1) + "x" + (radius * 2 + 1)
                + " chunks around you..."), false);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RUNNING.isEmpty()) return;
        RUNNING.removeIf(running -> {
            if (!running.job.step(SECTIONS_PER_TICK)) return false;
            running.source.sendSuccess(() -> Component.literal("Relight done: rechecked " + running.job.checked()
                    + " lit blocks in " + running.job.sections() + " sections."), false);
            return true;
        });
    }

    private record Running(CommandSourceStack source, RelightJob job) {}
}
