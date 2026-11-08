package com.neoalive.tacz_sewv.client.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.command.RelightJob;

/**
 * {@code /sewvc relight [radiusChunks]}: the same recheck on the client light engine, for light a
 * multiplayer client kept after a flash ended.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientRelight {

    private static final int SECTIONS_PER_TICK = 48;
    private static RelightJob job;

    private ClientRelight() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sewvc")
                .then(Commands.literal("relight")
                        .executes(ctx -> start(ctx.getSource(), 8))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> start(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int start(CommandSourceStack source, int radius) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) return 0;
        job = new RelightJob(mc.level, player.chunkPosition().x, player.chunkPosition().z, radius);
        source.sendSuccess(() -> Component.literal("Relighting client light around you..."), false);
        return 1;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || job == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != job.level()) {
            job = null;
            return;
        }
        if (job.step(SECTIONS_PER_TICK)) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Client relight done: rechecked "
                        + job.checked() + " lit blocks in " + job.sections() + " sections."), false);
            }
            job = null;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) job = null;
    }
}
