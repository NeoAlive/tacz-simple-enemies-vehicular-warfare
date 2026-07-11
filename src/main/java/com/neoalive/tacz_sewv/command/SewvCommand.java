package com.neoalive.tacz_sewv.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.neoalive.tacz_sewv.util.TankSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SewvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .requires(source -> source.hasPermission(2)) // operators only
                .then(Commands.literal("spawn")
                        .then(Commands.literal("ustank").executes(ctx -> spawnTank(ctx.getSource(), TankFaction.US)))
                        .then(Commands.literal("rutank").executes(ctx -> spawnTank(ctx.getSource(), TankFaction.RU)))
                        .then(Commands.literal("pmctank").executes(ctx -> spawnTank(ctx.getSource(), TankFaction.PMC)))
                )
        );
    }

    private static int spawnTank(CommandSourceStack source, TankFaction faction) {
        ServerLevel level = source.getLevel();

        // A PMC crew spawned by a player belongs to that player, so it answers
        // to their SEM command menu; RU/US crews are never owned.
        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        BlockPos pos = TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        VehicleEntity tank = TankSpawner.spawnTankWithDriver(level, pos, faction, ownerId);

        if (tank == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }
}
