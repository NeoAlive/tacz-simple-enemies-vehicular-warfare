package com.neoalive.tacz_sewv.command;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.GunCacheProbe;
import com.neoalive.tacz_sewv.debug.PerfProbe;
import com.neoalive.tacz_sewv.debug.SewvConfigFix;
import com.neoalive.tacz_sewv.debug.SewvDebugDump;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.entity.ai.support.DigFoxholeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SandbagSupport;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.InvasionHudTracker;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;
import com.neoalive.tacz_sewv.invasion.InvasionSession;
import com.neoalive.tacz_sewv.invasion.InvasionSpawn;
import com.neoalive.tacz_sewv.invasion.InvasionTickets;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketReloadVehicleSkins;
import com.neoalive.tacz_sewv.spawn.EmplacementSpawner;
import com.neoalive.tacz_sewv.spawn.EmplacementSpawner.Emplacement;
import com.neoalive.tacz_sewv.spawn.SupportSpawner;
import com.neoalive.tacz_sewv.spawn.SupportSpawner.SupportRole;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehicleDrops;

public class SewvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sewv")
                .then(Commands.literal("spawn")
                        .requires(source -> source.hasPermission(2)) // operators only
                        // /sewv spawn <ru|us|pmc> <type> [entries] [location] — faction and vehicle/unit
                        // type are separate arguments rather than combined literals (ustank, rumortar,
                        // ...) so the tree reads as one consistent shape instead of one literal per
                        // faction/type pair.
                        .then(factionSpawn("ru", TankFaction.RU))
                        .then(factionSpawn("us", TankFaction.US))
                        .then(factionSpawn("pmc", TankFaction.PMC))
                )
                // Ungated (unlike spawn, above): any player can check on their own units.
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("pool")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> openPoolEditor(ctx.getSource()))
                        .then(Commands.literal("vehicles")
                                .executes(ctx -> openPoolEditor(ctx.getSource())))
                        .then(Commands.literal("misc")
                                .executes(ctx -> openMiscEditor(ctx.getSource()))))
                .then(Commands.literal("targetPriority")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> openTargetPriority(ctx.getSource())))
                .then(Commands.literal("targeting")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> openTargetPriority(ctx.getSource())))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("rappel")
                                .executes(ctx -> debugRappel(ctx.getSource())))
                        .then(Commands.literal("guncache")
                                .executes(ctx -> debugGunCache(ctx.getSource())))
                        .then(Commands.literal("reloadSkins")
                                .executes(ctx -> debugReloadSkins(ctx.getSource(), false)))
                        .then(Commands.literal("poolSkinResetToDefault")
                                .executes(ctx -> debugReloadSkins(ctx.getSource(), true)))
                        .then(Commands.literal("dump")
                                .executes(ctx -> debugDump(ctx.getSource())))
                        .then(Commands.literal("perf")
                                .executes(ctx -> debugPerf(ctx.getSource())))
                        .then(Commands.literal("StartConfigFix")
                                .executes(ctx -> debugStartConfigFix(ctx.getSource())))
                        .then(Commands.literal("digFoxhole")
                                .executes(ctx -> debugDigFoxhole(ctx.getSource())))
                        .then(Commands.literal("seatSandbag")
                                .executes(ctx -> debugSeatSandbag(ctx.getSource())))
                        .then(Commands.literal("InvasionForceEnd")
                                .executes(ctx -> debugInvasionForceEnd(ctx.getSource())))
                        .then(Commands.literal("InvasionResetTeamBlockCounter")
                                .executes(ctx -> debugInvasionResetTeamBlockCounter(ctx.getSource())))
                        .then(Commands.literal("InvasionResetAllPoints")
                                .executes(ctx -> debugInvasionResetAllPoints(ctx.getSource())))
                        .then(Commands.literal("ShowSpawnProbes")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> debugShowSpawnProbes(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("IndividualTactics")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> debugIndividualTactics(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "value"))))))
                .then(Commands.literal("diplomacy")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("relation", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"ally", "enemy"}, b))
                                        .then(Commands.argument("faction", StringArgumentType.greedyString())
                                                .suggests(SewvCommand::suggestOpenPacFactions)
                                                .executes(ctx -> diplomacyAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "relation"),
                                                        StringArgumentType.getString(ctx, "faction"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("faction", StringArgumentType.greedyString())
                                        .suggests(SewvCommand::suggestOpenPacFactions)
                                        .executes(ctx -> diplomacyRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "faction")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> diplomacyList(ctx.getSource()))))
                // Stage G: validate → ticket → teleport → spawn; stop clears tickets/orders/spawns.
                .then(Commands.literal("invasion")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start")
                                .executes(ctx -> invasionStart(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .executes(ctx -> invasionStop(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> invasionStatus(ctx.getSource()))))
        );
    }

    private static int invasionStart(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        InvasionSession.StartResult result = InvasionSession.start(level);
        if (result instanceof InvasionSession.StartResult.Fail fail) {
            for (String error : fail.errors()) {
                source.sendFailure(invasionErrorComponent(error));
            }
            return 0;
        }
        InvasionSession.StartResult.Ok ok = (InvasionSession.StartResult.Ok) result;
        for (String warn : ok.warnings()) {
            source.sendSuccess(() -> invasionWarnComponent(warn), false);
        }
        InvasionSpawn.Result spawn = ok.spawn();
        if (ok.spawnDelaySeconds() > 0) {
            source.sendSuccess(() -> Component.translatable(
                    "command.tacz_sewv.invasion.started_delayed",
                    ok.spawnDelaySeconds(), spawn.bases()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("command.tacz_sewv.invasion.started",
                    spawn.bases(), spawn.aiVehicles(), spawn.playerVehicles()), true);
        }
        return 1;
    }

    private static int invasionStop(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        boolean memoryActive = InvasionSession.isActive(level);
        boolean savedActive = InvasionLayout.get(level).isSessionActive();
        if (!memoryActive && !savedActive) {
            source.sendFailure(Component.translatable("command.tacz_sewv.invasion.not_active"));
            return 0;
        }
        InvasionSession.deactivate(level);
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.invasion.stopped"), true);
        return 1;
    }

    private static Component invasionErrorComponent(String code) {
        return Component.translatable(invasionMessageKey("fail", code), invasionMessageArg(code));
    }

    private static Component invasionWarnComponent(String code) {
        return Component.translatable(invasionMessageKey("warn", code), invasionMessageArg(code));
    }

    /** Map {@code key:detail} codes from {@link InvasionValidate} onto lang keys. */
    private static String invasionMessageKey(String kind, String code) {
        int colon = code.indexOf(':');
        String head = colon < 0 ? code : code.substring(0, colon);
        return "command.tacz_sewv.invasion." + kind + "." + head;
    }

    private static Object invasionMessageArg(String code) {
        int colon = code.indexOf(':');
        return colon < 0 ? "" : code.substring(colon + 1);
    }

    private static int invasionStatus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        boolean active = InvasionSession.isActive(level);
        source.sendSuccess(() -> Component.translatable(
                active ? "command.tacz_sewv.invasion.status.active"
                        : "command.tacz_sewv.invasion.status.idle"), false);

        BlockPos origin = BlockPos.containing(source.getPosition());
        int reported = 0;
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        for (int x = cx - 3; x <= cx + 3 && reported < 12; x++) {
            for (int z = cz - 3; z <= cz + 3 && reported < 12; z++) {
                if (!level.hasChunk(x, z)) continue;
                for (var be : level.getChunk(x, z).getBlockEntities().values()) {
                    if (!(be instanceof CapturableBlockEntity capturable)) continue;
                    if (capturable.getBlockPos().distSqr(origin) > 64 * 64) continue;
                    String line = CaptureSupport.describe(capturable);
                    source.sendSuccess(() -> Component.literal(line), false);
                    reported++;
                    if (reported >= 12) break;
                }
            }
        }
        if (reported == 0) {
            source.sendSuccess(() -> Component.translatable(
                    "command.tacz_sewv.invasion.status.none_nearby"), false);
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            var team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
            String teamName = team == null ? "none" : team.getName();
            source.sendSuccess(() -> Component.translatable(
                    "command.tacz_sewv.invasion.status.you",
                    player.gameMode.getGameModeForPlayer().getName(),
                    teamName), false);
            BlockPos respawn = player.getRespawnPosition();
            String respawnDim = player.getRespawnDimension().location().toString();
            source.sendSuccess(() -> Component.literal(String.format(
                    "respawn=%s @ %s",
                    respawn == null ? "cleared" : respawn.toShortString(),
                    respawnDim)), false);
        }
        if (active) {
            int hulls = 0, crew = 0;
            for (Entity entity : level.getAllEntities()) {
                if (!entity.getPersistentData().getBoolean(
                        com.neoalive.tacz_sewv.invasion.InvasionTags.SPAWN)) continue;
                if (entity instanceof VehicleEntity) hulls++;
                else if (!(entity instanceof ServerPlayer)) crew++;
            }
            int h = hulls, c = crew;
            source.sendSuccess(() -> Component.literal(
                    "invasion_spawn tagged: hulls=" + h + " crew=" + c), false);
        }
        return active ? 1 : 0;
    }

    private static int openPoolEditor(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.pool.player_only"));
            return 0;
        }
        return com.neoalive.tacz_sewv.util.PoolEditorAccess.open(player);
    }

    private static int openMiscEditor(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.pool.player_only"));
            return 0;
        }
        return com.neoalive.tacz_sewv.invasion.MiscEditorAccess.open(player);
    }

    private static int openTargetPriority(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.pool.player_only"));
            return 0;
        }
        return com.neoalive.tacz_sewv.util.TargetPriorityAccess.open(player);
    }

    private static CompletableFuture<Suggestions> suggestOpenPacFactions(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder) {
        if (!OpenPacCompat.isLoaded()) {
            return Suggestions.empty();
        }
        return SharedSuggestionProvider.suggest(
                OpenPacCompat.factionNames(ctx.getSource().getServer()), builder);
    }

    private static int diplomacyAdd(CommandSourceStack source, String relationWord, String otherFaction) {
        if (!OpenPacCompat.isLoaded()) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.no_openpac"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.player_only"));
            return 0;
        }
        String self = OpenPacCompat.factionName(source.getServer(), player.getUUID());
        if (self == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.no_party"));
            return 0;
        }
        String resolved = resolveFactionName(source.getServer(), otherFaction);
        if (resolved == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.unknown_faction", otherFaction));
            return 0;
        }
        DiplomacyData.Relation relation = "ally".equalsIgnoreCase(relationWord)
                ? DiplomacyData.Relation.ALLY
                : "enemy".equalsIgnoreCase(relationWord) ? DiplomacyData.Relation.ENEMY : null;
        if (relation == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.bad_relation"));
            return 0;
        }
        DiplomacyData data = DiplomacyData.get(source.getLevel());
        if (!data.set(self, resolved, relation)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.diplomacy.added",
                relation.name().toLowerCase(), resolved), true);
        return 1;
    }

    private static int diplomacyRemove(CommandSourceStack source, String otherFaction) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.player_only"));
            return 0;
        }
        String self = OpenPacCompat.isLoaded()
                ? OpenPacCompat.factionName(source.getServer(), player.getUUID())
                : null;
        if (self == null) {
            source.sendFailure(Component.translatable(
                    OpenPacCompat.isLoaded()
                            ? "command.tacz_sewv.diplomacy.no_party"
                            : "command.tacz_sewv.diplomacy.no_openpac"));
            return 0;
        }
        String resolved = resolveFactionName(source.getServer(), otherFaction);
        if (resolved == null) resolved = otherFaction.trim();
        final String name = resolved;
        DiplomacyData data = DiplomacyData.get(source.getLevel());
        if (!data.remove(self, name)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.diplomacy.not_found", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.diplomacy.removed", name), true);
        return 1;
    }

    private static int diplomacyList(CommandSourceStack source) {
        DiplomacyData data = DiplomacyData.get(source.getLevel());
        var snap = data.snapshot();
        if (snap.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.tacz_sewv.diplomacy.empty"), false);
            return 1;
        }
        snap.forEach((key, rel) -> source.sendSuccess(
                () -> Component.literal(DiplomacyData.formatPair(key, rel)), false));
        return snap.size();
    }

    @Nullable
    private static String resolveFactionName(net.minecraft.server.MinecraftServer server, String raw) {
        if (!OpenPacCompat.isLoaded()) return null;
        for (String name : OpenPacCompat.factionNames(server)) {
            if (name.equalsIgnoreCase(raw.trim())) return name;
        }
        return null;
    }

    /** Stage-1 rappel: toggle hover-lock on the looked-at / nearest helicopter. */
    private static int debugRappel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.rappel.player_only"));
            return 0;
        }
        VehicleEntity heli = findDebugHelicopter(player);
        if (heli == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.rappel.none"));
            return 0;
        }
        boolean next = !DriveHelicopterGoal.isRappelRequested(heli);
        DriveHelicopterGoal.setRappelRequested(heli, next);
        source.sendSuccess(() -> Component.translatable(
                next ? "command.tacz_sewv.debug.rappel.on" : "command.tacz_sewv.debug.rappel.off",
                heli.getDisplayName(), heli.getId()), true);
        return 1;
    }

    /**
     * Force a nearby Combat Engineer to place {@code grass_trench_1}, bypassing autonomous
     * age / ground-eligibility / hasDug gates. Still marks {@code sewv:hasDugFoxhole} on success.
     */
    private static int debugDigFoxhole(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos near = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : BlockPos.containing(source.getPosition());
        AbstractUnit engineer = DigFoxholeSupport.findNearestCombatEngineer(level, near, 64.0);
        if (engineer == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.digFoxhole.none"));
            return 0;
        }
        if (!DigFoxholeSupport.place(level, engineer)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.digFoxhole.fail",
                    engineer.getDisplayName(), engineer.getId()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.debug.digFoxhole.ok",
                engineer.getDisplayName(), engineer.getId()), true);
        return 1;
    }

    /**
     * Force the nearest idle RU/US unit onto the nearest free sandbag via ENTRENCHED assign
     * (same path as player entrench / SeekEntrenchmentGoal). No auto-leave timer — clear with
     * dismiss, not dismount.
     */
    private static int debugSeatSandbag(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos near = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : BlockPos.containing(source.getPosition());
        AbstractUnit unit = SandbagSupport.findNearestIdleFactionUnit(level, near, 64.0);
        if (unit == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.seatSandbag.none_unit"));
            return 0;
        }
        BlockPos bag = SandbagSupport.findNearestFree(level, near, 64.0, unit);
        if (bag == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.seatSandbag.none_bag"));
            return 0;
        }
        if (EntrenchSupport.assign(level, java.util.List.of(unit), bag) <= 0) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.seatSandbag.fail",
                    unit.getDisplayName(), unit.getId()));
            return 0;
        }
        // Immediate mount if already in range (otherwise EntrenchGoal walks then seats).
        SandbagSupport.tryMount(level, bag, unit);
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.debug.seatSandbag.ok",
                unit.getDisplayName(), unit.getId(),
                bag.getX(), bag.getY(), bag.getZ()), true);
        return 1;
    }

    private static int debugInvasionForceEnd(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        InvasionSession.forceEnd(level);
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.debug.InvasionForceEnd.ok"), true);
        return 1;
    }

    private static int debugInvasionResetTeamBlockCounter(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        InvasionLayout layout = InvasionLayout.get(level);
        java.util.Set<Long> previous = layout.teamBasePositions();
        layout.clearTeamBases();
        for (long packed : previous) {
            BlockPos pos = BlockPos.of(packed);
            InvasionTickets.ensureLoaded(level, pos);
            if (level.getBlockEntity(pos) instanceof TeamBaseBlockEntity) {
                layout.noteTeamBase(pos);
            }
        }
        for (TeamBaseBlockEntity base : InvasionSpawn.findTeamBases(level)) {
            layout.noteTeamBase(base.getBlockPos());
        }
        int count = layout.teamBasePositions().size();
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.InvasionResetTeamBlockCounter.ok", count), true);
        return 1;
    }

    private static int debugInvasionResetAllPoints(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        InvasionLayout layout = InvasionLayout.get(level);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int cleared = 0;
        for (long packed : layout.capturePointPositions()) {
            BlockPos pos = BlockPos.of(packed);
            InvasionTickets.ensureLoaded(level, pos);
            if (!(level.getBlockEntity(pos) instanceof CapturePointBlockEntity point)) continue;
            point.setOwnedTeam("");
            point.clearCaptureProgress();
            seen.add(packed);
            cleared++;
        }
        for (CapturableBlockEntity zone : InvasionSpawn.findLoadedCapturables(level)) {
            if (!(zone instanceof CapturePointBlockEntity point)) continue;
            layout.noteCapturePoint(point.getBlockPos());
            if (!seen.add(point.getBlockPos().asLong())) continue;
            point.setOwnedTeam("");
            point.clearCaptureProgress();
            cleared++;
        }
        if (InvasionSession.isActive(level)) {
            InvasionHudTracker.push(level);
        }
        int n = cleared;
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.InvasionResetAllPoints.ok", n), true);
        return 1;
    }

    private static int debugShowSpawnProbes(CommandSourceStack source, boolean value) {
        ServerLevel level = source.getLevel();
        level.getGameRules().getRule(ModGameRules.SHOW_SPAWN_PROBES).set(value, source.getServer());
        source.sendSuccess(() -> Component.translatable(
                value ? "command.tacz_sewv.debug.ShowSpawnProbes.on"
                        : "command.tacz_sewv.debug.ShowSpawnProbes.off"), true);
        return 1;
    }

    private static int debugIndividualTactics(CommandSourceStack source, boolean value) {
        ServerLevel level = source.getLevel();
        level.getGameRules().getRule(ModGameRules.INDIVIDUAL_TACTICS_DEBUG).set(value, source.getServer());
        source.sendSuccess(() -> Component.translatable(
                value ? "command.tacz_sewv.debug.IndividualTactics.on"
                        : "command.tacz_sewv.debug.IndividualTactics.off"), true);
        return 1;
    }

    /**
     * Spawns a temporary RU crewed hull (or probes the nearest vehicle) and verifies the
     * per-tick gun-map cache stays coherent across weapon-index and ammo switches.
     */
    private static int debugGunCache(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos near = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : level.getSharedSpawnPos();
        VehicleEntity existing = null;
        double best = 32.0 * 32.0;
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class,
                new AABB(near).inflate(32.0), Entity::isAlive)) {
            double d = v.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
            if (d < best) {
                best = d;
                existing = v;
            }
        }
        String result = existing != null
                ? GunCacheProbe.probeHull(existing)
                : GunCacheProbe.run(level, near);
        if (result.startsWith("PASS")) {
            source.sendSuccess(() -> Component.literal(result), true);
            return 1;
        }
        source.sendFailure(Component.literal(result));
        return 0;
    }

    private static int debugPerf(CommandSourceStack source) {
        for (String line : PerfProbe.report(source.getLevel()).split("\n")) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Tell clients to re-scan config/tacz_sewv/vehicle_skins/ without a restart. */
    private static int debugDump(CommandSourceStack source) {
        try {
            Path path = SewvDebugDump.write(source.getServer(), source.getLevel());
            source.sendSuccess(() -> Component.literal(
                    "Diagnostic written to " + path + " — send this file."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Dump failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int debugStartConfigFix(CommandSourceStack source) {
        try {
            SewvConfigFix.Result result = SewvConfigFix.quarantine();
            if (result.moved().isEmpty()) {
                source.sendSuccess(() -> Component.literal("StartConfigFix: no stale files moved."), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "StartConfigFix: moved " + result.moved().size() + " file(s):"), false);
                for (SewvConfigFix.Move m : result.moved()) {
                    source.sendSuccess(() -> Component.literal(
                            "  " + m.from() + " -> " + m.to() + " (" + m.reason() + ")"), false);
                }
            }
            for (String note : result.notes()) {
                source.sendSuccess(() -> Component.literal(note), false);
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("StartConfigFix failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * The work happens client-side: the skin folders live in the <b>client's</b> config directory,
     * which is not the machine this command ran on once a dedicated server is involved.
     */
    private static int debugReloadSkins(CommandSourceStack source, boolean reset) {
        PacketReloadVehicleSkins packet = new PacketReloadVehicleSkins(reset);
        if (source.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        } else if (source.getServer() != null) {
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
        source.sendSuccess(() -> Component.translatable(reset
                ? "command.tacz_sewv.debug.reset_skins"
                : "command.tacz_sewv.debug.reload_skins"), true);
        return 1;
    }

    private static final double DEBUG_HELI_RANGE = 64.0;

    @Nullable
    private static VehicleEntity findDebugHelicopter(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(DEBUG_HELI_RANGE));
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, sweep,
                e -> e instanceof VehicleEntity v && HullFacts.isHelicopterHull(v) && e.isAlive(),
                DEBUG_HELI_RANGE * DEBUG_HELI_RANGE);
        if (hit != null && hit.getEntity() instanceof VehicleEntity looked) {
            return looked;
        }
        VehicleEntity nearest = null;
        double best = DEBUG_HELI_RANGE * DEBUG_HELI_RANGE;
        for (VehicleEntity v : player.level().getEntitiesOfClass(VehicleEntity.class,
                player.getBoundingBox().inflate(DEBUG_HELI_RANGE),
                e -> HullFacts.isHelicopterHull(e) && e.isAlive())) {
            double d = v.distanceToSqr(player);
            if (d < best) {
                best = d;
                nearest = v;
            }
        }
        return nearest;
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
            int mode = patrol.sewv$getPatrolMode();
            if (mode == IVehiclePatrol.MODE_CRUISE) {
                return Component.translatable("command.tacz_sewv.status.cruise");
            }
            String key = mode == IVehiclePatrol.MODE_SEARCH
                    ? "command.tacz_sewv.status.search" : "command.tacz_sewv.status.patrol";
            return Component.translatable(key, patrol.sewv$getPatrolRadius());
        }

        if (((IFormationMember) pmc).sewv$getFormationDirection() != null) {
            return Component.translatable("command.tacz_sewv.status.formation");
        }

        return Component.translatable("command.tacz_sewv.status.idle");
    }

    // One faction node under /sewv spawn, holding every vehicle/unit TYPE for that faction as its
    // own literal (tank/ship/plane/heli/mortar/tow, plus medic/engineer/combatengineer for RU/US
    // and commander for PMC — SEM gives neither RU nor US a commander-equivalent, and medic/
    // engineer/combatengineer are SupportRole-only for PMC, held rather than spawned, so PMC gets
    // no support-unit literals here).
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> factionSpawn(
            String literal, TankFaction faction) {
        var node = Commands.literal(literal)
                .then(vehicleSpawn("tank", faction, TankFaction::vehiclePool,
                        TankSpawner::spawnTankWithCrew, true, false))
                .then(vehicleSpawn("ship", faction, TankFaction::shipPool,
                        TankSpawner::spawnShipWithCrew, false, true))
                .then(vehicleSpawn("plane", faction, TankFaction::planePool,
                        TankSpawner::spawnPlaneWithCrew, true, true))
                .then(vehicleSpawn("heli", faction, TankFaction::heliPool,
                        TankSpawner::spawnHeliWithCrew, true, false))
                .then(emplacementSpawn("mortar", faction, Emplacement.MORTAR, TankFaction::mortarPool))
                .then(emplacementSpawn("tow", faction, Emplacement.TOW, TankFaction::towPool));
        if (faction == TankFaction.PMC) {
            node.then(commanderSpawn("commander"));
        } else {
            boolean ru = faction == TankFaction.RU;
            node.then(supportSpawn("medic", ru, SupportRole.MEDIC))
                    .then(supportSpawn("engineer", ru, SupportRole.ENGINEER))
                    .then(supportSpawn("combatengineer", ru, SupportRole.COMBAT_ENGINEER));
        }
        return node;
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

    // The Commander takes no vehicle id, like the other support units; owned by whoever ran the
    // command (if a player), so radio/order gating has someone to check against.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> commanderSpawn(
            String literal) {
        return Commands.literal(literal)
                .executes(ctx -> spawnCommander(ctx.getSource(), null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnCommander(ctx.getSource(),
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))));
    }

    private static int spawnCommander(CommandSourceStack source, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();
        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        UUID ownerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;

        if (com.neoalive.tacz_sewv.spawn.PmcCommanderSpawner.spawn(level, pos, ownerId) == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.support_fail"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", "PMC Commander", pos.toShortString()), true);
        return 1;
    }

    // Same optional-arg shape as vehicleSpawn: pool id and/or position.
    //   /sewv spawn ru mortar                              random from mortar pool
    //   /sewv spawn ru mortar superbwarfare:type_63        that id at the source
    //   /sewv spawn ru mortar <x y z>                      random at coordinates
    //   /sewv spawn ru mortar <x y z> superbwarfare:tow    (tow pool likewise)
    // A mortar spawned this way has no fire mission — it shoots what its crew can see, the
    // same as one a player ordered a unit onto. The mortar_shelling event is what hands out
    // standing missions.
    // Every crew arrives able to fire. RU/US crews carry an unlimited issued supply (they have
    // no inventory to hold a stack in); a PMC crew gets its inventory filled with real stacks
    // it can run out of and the owner can top up (sneak+right-click).
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> emplacementSpawn(
            String literal, TankFaction faction, Emplacement type,
            BiFunction<TankFaction, ServerLevel, List<? extends String>> pool) {
        return Commands.literal(literal)
                .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type, pool, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type, pool, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggestPool(c.getSource(), pool, faction, b))
                                .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type, pool,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPool(c.getSource(), pool, faction, b))
                        .executes(ctx -> spawnEmplacement(ctx.getSource(), faction, type, pool,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static int spawnEmplacement(CommandSourceStack source, TankFaction faction,
                                        Emplacement type,
                                        BiFunction<TankFaction, ServerLevel, List<? extends String>> pool,
                                        @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        if (vehicleId != null && !pool.apply(faction, level).contains(vehicleId)) {
            source.sendFailure(Component.translatable(
                    "command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }

        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        BlockPos pos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));

        VehicleEntity weapon = com.neoalive.tacz_sewv.spawn.SupportSpawner.withoutCompanions(
                () -> EmplacementSpawner.spawn(level, pos, type, faction, ownerId, null, vehicleId));
        if (weapon == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.emplacement_fail"));
            return 0;
        }
        VehicleDrops.markCrewAndHull(weapon);

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }

    // Each vehicle literal takes an OPTIONAL spawn position and an OPTIONAL vehicle id:
    //   /sewv spawn us tank                     random vehicle at the source (ground-snapped)
    //   /sewv spawn us tank <id>                that vehicle at the source
    //   /sewv spawn us tank <x y z>             random vehicle at the coordinates (given Y)
    //   /sewv spawn us tank <x y z> <id>        that vehicle at the coordinates
    // The pos branch is registered BEFORE the vehicle branch so numeric input parses
    // as coordinates; a namespaced vehicle id can't parse as a BlockPos, so it falls
    // through to the greedy vehicle branch. The id stays a greedy string (a ':' needs
    // no quoting) and tab-completion still suggests the faction's configured pool.
    //
    // One shape for all four hull classes — they differ only in which pool they suggest/
    // validate against, which spawner they call (all share one signature), whether the
    // no-explicit-pos fallback snaps to ground (ships don't: findClearWaterSpawn resolves
    // its own per-column Y while spiralling for water), and whether the success message
    // reports the requested or the spawned hull's actual position (ship/plane: the
    // spawner relocates them).
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> vehicleSpawn(
            String literal, TankFaction faction,
            BiFunction<TankFaction, ServerLevel, List<? extends String>> pool,
            SpawnWithCrewFn spawner, boolean snapToGround, boolean reportActualPos) {
        return Commands.literal(literal)
                .executes(ctx -> spawnVehicle(ctx.getSource(), faction, pool, spawner,
                        snapToGround, reportActualPos, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnVehicle(ctx.getSource(), faction, pool, spawner,
                                snapToGround, reportActualPos, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggestPool(c.getSource(), pool, faction, b))
                                .executes(ctx -> spawnVehicle(ctx.getSource(), faction, pool,
                                        spawner, snapToGround, reportActualPos,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPool(c.getSource(), pool, faction, b))
                        .executes(ctx -> spawnVehicle(ctx.getSource(), faction, pool, spawner,
                                snapToGround, reportActualPos,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    @FunctionalInterface
    private interface SpawnWithCrewFn {
        VehicleEntity spawn(ServerLevel level, BlockPos pos, TankFaction faction,
                            UUID ownerId, String vehicleId);
    }

    private static CompletableFuture<Suggestions> suggestPool(CommandSourceStack source,
                                                              BiFunction<TankFaction, ServerLevel, List<? extends String>> pool,
                                                              TankFaction faction,
                                                              SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                pool.apply(faction, source.getLevel()).stream().map(String::valueOf), builder);
    }

    private static int spawnVehicle(CommandSourceStack source, TankFaction faction,
                                    BiFunction<TankFaction, ServerLevel, List<? extends String>> pool,
                                    SpawnWithCrewFn spawner, boolean snapToGround,
                                    boolean reportActualPos,
                                    @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        // A specific id is only honored if the world pool actually contains it — catch
        // it here so the operator gets a clear reason rather than the generic failure.
        if (vehicleId != null && !pool.apply(faction, level).contains(vehicleId)) {
            source.sendFailure(Component.translatable(
                    "command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }

        // A PMC crew spawned by a player belongs to that player, so it answers
        // to their SEM command menu; RU/US crews are never owned.
        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        // Explicit coordinates are used exactly as given (the operator's Y is respected);
        // with none, fall back to the source position — snapped to ground unless the hull
        // resolves its own placement (ship).
        BlockPos pos = explicitPos != null
                ? explicitPos
                : snapToGround
                ? TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()))
                : BlockPos.containing(source.getPosition());
        VehicleEntity hull = com.neoalive.tacz_sewv.spawn.SupportSpawner.withoutCompanions(
                () -> spawner.spawn(level, pos, faction, ownerId, vehicleId));

        if (hull == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }
        VehicleDrops.markCrewAndHull(hull);

        BlockPos reported = reportActualPos ? hull.blockPosition() : pos;
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), reported.toShortString()), true);
        return 1;
    }
}
