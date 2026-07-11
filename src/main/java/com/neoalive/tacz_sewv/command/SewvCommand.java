package com.neoalive.tacz_sewv.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class SewvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .requires(source -> source.hasPermission(2)) // operators only
                .then(Commands.literal("spawn")
                        .then(Commands.literal("ustank").executes(ctx -> spawnTank(ctx.getSource(), false)))
                        .then(Commands.literal("rutank").executes(ctx -> spawnTank(ctx.getSource(), true)))
                )
        );
    }

    private static int spawnTank(CommandSourceStack source, boolean isRu) {
        ServerLevel level = source.getLevel();

        BlockPos pos = TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        VehicleEntity tank = TankSpawner.spawnTankWithDriver(level, pos, isRu);

        if (tank == null) {
            source.sendFailure(Component.literal("Couldn't spawn the tank, no space here, or SuperbWarfare isn't loaded."));
            return 0;
        }

        String faction = isRu ? "RU" : "US";
        source.sendSuccess(() -> Component.literal("Spawned " + faction + " tank at " + pos.toShortString()), true);
        return 1;
    }
}
