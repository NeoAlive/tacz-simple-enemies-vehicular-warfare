package com.neoalive.tacz_sewv.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.neoalive.tacz_sewv.util.TankSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SewvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .requires(source -> source.hasPermission(2)) // operators only
                .then(Commands.literal("spawn")
                        .then(tankSpawn("ustank", TankFaction.US))
                        .then(tankSpawn("rutank", TankFaction.RU))
                        .then(tankSpawn("pmctank", TankFaction.PMC))
                )
        );
    }

    // Each tank literal takes an OPTIONAL vehicle id: `/sewv spawn ustank` picks at
    // random from the faction pool, `/sewv spawn ustank <id>` forces that specific
    // pooled vehicle. The id is a greedy string so entity ids with a ':' need no
    // quoting, and tab-completion suggests the faction's configured pool entries.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tankSpawn(String literal, TankFaction faction) {
        return Commands.literal(literal)
                .executes(ctx -> spawnTank(ctx.getSource(), faction, null))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPool(faction, b))
                        .executes(ctx -> spawnTank(ctx.getSource(), faction, StringArgumentType.getString(ctx, "vehicle"))));
    }

    private static CompletableFuture<Suggestions> suggestPool(TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(faction.vehiclePool().stream().map(String::valueOf), builder);
    }

    private static int spawnTank(CommandSourceStack source, TankFaction faction, @Nullable String vehicleId) {
        ServerLevel level = source.getLevel();

        // A specific id is only honored if the config pool actually contains it — catch
        // it here so the operator gets a clear reason rather than the generic failure.
        if (vehicleId != null && !faction.vehiclePool().contains(vehicleId)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }

        // A PMC crew spawned by a player belongs to that player, so it answers
        // to their SEM command menu; RU/US crews are never owned.
        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        BlockPos pos = TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        VehicleEntity tank = TankSpawner.spawnTankWithCrew(level, pos, faction, ownerId, vehicleId);

        if (tank == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }
}
