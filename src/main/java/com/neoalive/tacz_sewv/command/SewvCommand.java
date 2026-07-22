package com.neoalive.tacz_sewv.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.EmplacementSpawner;
import com.neoalive.tacz_sewv.util.EmplacementSpawner.Emplacement;
import com.neoalive.tacz_sewv.util.SupportSpawner;
import com.neoalive.tacz_sewv.util.SupportSpawner.SupportRole;
import com.neoalive.tacz_sewv.util.TankSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SewvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .then(Commands.literal("spawn")
                        .requires(source -> source.hasPermission(2)) // operators only
                        .then(tankSpawn("ustank", TankFaction.US))
                        .then(tankSpawn("rutank", TankFaction.RU))
                        .then(tankSpawn("pmctank", TankFaction.PMC))
                        .then(shipSpawn("usship", TankFaction.US))
                        .then(shipSpawn("ruship", TankFaction.RU))
                        .then(shipSpawn("pmcship", TankFaction.PMC))
                        .then(emplacementSpawn("usmortar", TankFaction.US, Emplacement.MORTAR))
                        .then(emplacementSpawn("rumortar", TankFaction.RU, Emplacement.MORTAR))
                        .then(emplacementSpawn("pmcmortar", TankFaction.PMC, Emplacement.MORTAR))
                        .then(emplacementSpawn("ustow", TankFaction.US, Emplacement.TOW))
                        .then(emplacementSpawn("rutow", TankFaction.RU, Emplacement.TOW))
                        .then(emplacementSpawn("pmctow", TankFaction.PMC, Emplacement.TOW))
                        .then(supportSpawn("rumedic", true, SupportRole.MEDIC))
                        .then(supportSpawn("usmedic", false, SupportRole.MEDIC))
                        .then(supportSpawn("ruengineer", true, SupportRole.ENGINEER))
                        .then(supportSpawn("usengineer", false, SupportRole.ENGINEER))
                )
                // Ungated (unlike spawn, above): any player can check on their own units.
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
        );
    }

    // Reports each nearby owned PMC unit's current standing order, in the same precedence the
    // AI itself resolves them (see CrewTargetPriorityGoal/VehicleTargeting): escort, then a
    // mortar claim or fire mission, then a patrol/search area task, then formation, else idle.
    // Bounded by the same radius every other TDT order already scans within.
    private static int status(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.status.player_only"));
            return 0;
        }

        boolean[] any = {false};
        for (PmcUnitEntity pmc : player.level().getEntitiesOfClass(PmcUnitEntity.class,
                player.getBoundingBox().inflate(SewvConfig.BOARD_SCAN_RADIUS.get()))) {
            if (!pmc.isOwnedBy(player)) continue;
            any[0] = true;
            source.sendSuccess(() -> Component.translatable(
                    "command.tacz_sewv.status.line", pmc.getDisplayName(), describeStatus(pmc)), false);
        }
        if (!any[0]) {
            source.sendSuccess(() -> Component.translatable("command.tacz_sewv.status.none_owned"), false);
        }
        return 1;
    }

    private static Component describeStatus(PmcUnitEntity pmc) {
        if (((IEscort) pmc).tacz_sewv$isEscorting()) {
            return Component.translatable("command.tacz_sewv.status.escorting");
        }

        IMortarCrew mortarCrew = (IMortarCrew) pmc;
        if (mortarCrew.sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR) {
            return Component.translatable("command.tacz_sewv.status.mortar");
        }
        FireMission mission = mortarCrew.sewv$getFireMission();
        if (mission != null) {
            return Component.translatable("command.tacz_sewv.status.fire_mission", mission.pos().toShortString());
        }

        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        if (patrol.sewv$isPatrolling()) {
            String key = patrol.sewv$getPatrolMode() == IVehiclePatrol.MODE_SEARCH
                    ? "command.tacz_sewv.status.search" : "command.tacz_sewv.status.patrol";
            return Component.translatable(key, patrol.sewv$getPatrolRadius());
        }

        if (((IFormationMember) pmc).sewv$getFormationDirection() != null) {
            return Component.translatable("command.tacz_sewv.status.formation");
        }

        return Component.translatable("command.tacz_sewv.status.idle");
    }

    // Support units take no vehicle id; only an optional position, like the emplacements.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> supportSpawn(
            String literal, boolean ru, SupportRole role) {
        return Commands.literal(literal)
                .executes(ctx -> spawnSupport(ctx.getSource(), ru, role, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnSupport(ctx.getSource(), ru, role,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))));
    }

    private static int spawnSupport(CommandSourceStack source, boolean ru, SupportRole role,
                                    @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();
        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));

        if (SupportSpawner.spawn(level, pos, ru, role) == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.support_fail"));
            return 0;
        }

        String label = (ru ? "RU " : "US ") + role.name().toLowerCase();
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", label, pos.toShortString()), true);
        return 1;
    }

    // Emplacements take no vehicle id (there is exactly one mortar and one TOW), so the
    // only optional argument is the position:
    //   /sewv spawn rumortar               at the source, ground-snapped
    //   /sewv spawn rumortar <x y z>       at the coordinates (given Y)
    // A mortar spawned this way has no fire mission — it shoots what its crew can see, the
    // same as one a player ordered a unit onto. The mortar_shelling event is what hands out
    // standing missions.
    // Every crew arrives able to fire. RU/US crews carry an unlimited issued supply (they have
    // no inventory to hold a stack in); a PMC crew gets its inventory filled with real stacks
    // it can run out of and the owner can top up (sneak+right-click).
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> emplacementSpawn(
            String literal, TankFaction faction, Emplacement type) {
        return Commands.literal(literal)
                .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))));
    }

    private static int spawnEmplacement(CommandSourceStack source, TankFaction faction,
                                        Emplacement type, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));

        if (EmplacementSpawner.spawn(level, pos, type, faction, ownerId, null) == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.emplacement_fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }

    // Each tank literal takes an OPTIONAL spawn position and an OPTIONAL vehicle id:
    //   /sewv spawn ustank                     random vehicle at the source (ground-snapped)
    //   /sewv spawn ustank <id>                that vehicle at the source
    //   /sewv spawn ustank <x y z>             random vehicle at the coordinates (given Y)
    //   /sewv spawn ustank <x y z> <id>        that vehicle at the coordinates
    // The pos branch is registered BEFORE the vehicle branch so numeric input parses
    // as coordinates; a namespaced vehicle id can't parse as a BlockPos, so it falls
    // through to the greedy vehicle branch. The id stays a greedy string (a ':' needs
    // no quoting) and tab-completion still suggests the faction's configured pool.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tankSpawn(String literal, TankFaction faction) {
        return Commands.literal(literal)
                .executes(ctx -> spawnTank(ctx.getSource(), faction, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnTank(ctx.getSource(), faction, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggestPool(faction, b))
                                .executes(ctx -> spawnTank(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPool(faction, b))
                        .executes(ctx -> spawnTank(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static CompletableFuture<Suggestions> suggestPool(TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(faction.vehiclePool().stream().map(String::valueOf), builder);
    }

    // Mirrors tankSpawn/spawnTank exactly, against the faction's ship pool instead. Ships are a
    // dedicated pool/spawn path (TankSpawner.spawnShipWithCrew, water-surface positioning) rather
    // than another entry in the ground/air one, so this isn't just tankSpawn with a different id.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> shipSpawn(String literal, TankFaction faction) {
        return Commands.literal(literal)
                .executes(ctx -> spawnShip(ctx.getSource(), faction, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnShip(ctx.getSource(), faction, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggestShipPool(faction, b))
                                .executes(ctx -> spawnShip(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestShipPool(faction, b))
                        .executes(ctx -> spawnShip(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static CompletableFuture<Suggestions> suggestShipPool(TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(faction.shipPool().stream().map(String::valueOf), builder);
    }

    private static int spawnTank(CommandSourceStack source, TankFaction faction,
                                 @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
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

        // Explicit coordinates are used exactly as given (the operator's Y is respected);
        // with none, fall back to the source position snapped to the ground surface.
        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        VehicleEntity tank = TankSpawner.spawnTankWithCrew(level, pos, faction, ownerId, vehicleId);

        if (tank == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }

    private static int spawnShip(CommandSourceStack source, TankFaction faction,
                                  @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        if (vehicleId != null && !faction.shipPool().contains(vehicleId)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }

        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        // Unlike spawnTank, the no-explicit-pos fallback is NOT snapped to ground height —
        // TankSpawner.findClearWaterSpawn resolves its own per-column Y while spiralling for
        // water, so the source position's raw X/Z (with its own Y only as a chunk-unloaded
        // fallback) is all it needs.
        BlockPos requestedPos = explicitPos != null ? explicitPos : BlockPos.containing(source.getPosition());
        VehicleEntity ship = TankSpawner.spawnShipWithCrew(level, requestedPos, faction, ownerId, vehicleId);

        if (ship == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), ship.blockPosition().toShortString()), true);
        return 1;
    }
}
