package com.neoalive.tacz_sewv.compat.lv;

import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * {@code /sewv spawn <faction> lv [pos] [vehicle]} — the Limitless Vehicles sibling of the
 * {@code tank}/{@code ship} literals, drawing from the faction's LV pool. Added to the tree only
 * when LV is loaded. A boat id finds water on its own, so the position is not snapped for it.
 */
public final class LvCommands {

    private LvCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node(TankFaction faction) {
        return Commands.literal("lv")
                .executes(ctx -> spawn(ctx.getSource(), faction, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawn(ctx.getSource(), faction, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        LvSpawner.pool(faction).stream().map(String::valueOf), b))
                                .executes(ctx -> spawn(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                LvSpawner.pool(faction).stream().map(String::valueOf), b))
                        .executes(ctx -> spawn(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static int spawn(CommandSourceStack source, TankFaction faction,
                             @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();
        if (vehicleId != null && !LvSpawner.pool(faction).contains(vehicleId)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }
        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;
        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        Entity hull = LvCompat.spawnCrewed(level, pos, faction, ownerId, vehicleId);
        if (hull == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }
        BlockPos reported = hull.blockPosition();
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), reported.toShortString()), true);
        return 1;
    }
}
