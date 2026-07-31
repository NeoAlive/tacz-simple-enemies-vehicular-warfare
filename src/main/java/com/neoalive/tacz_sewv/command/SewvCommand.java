package com.neoalive.tacz_sewv.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.debug.GunCacheProbe;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.entity.ai.HullFacts;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;
import com.neoalive.tacz_sewv.invasion.InvasionSession;
import com.neoalive.tacz_sewv.invasion.InvasionSpawn;
import com.neoalive.tacz_sewv.util.EmplacementSpawner;
import com.neoalive.tacz_sewv.util.EmplacementSpawner.Emplacement;
import com.neoalive.tacz_sewv.util.SupportSpawner;
import com.neoalive.tacz_sewv.util.SupportSpawner.SupportRole;
import com.neoalive.tacz_sewv.util.TankSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehicleDrops;
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
                        .then(planeSpawn("usplane", TankFaction.US))
                        .then(planeSpawn("ruplane", TankFaction.RU))
                        .then(planeSpawn("pmcplane", TankFaction.PMC))
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
                .then(Commands.literal("pool")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> openPoolEditor(ctx.getSource())))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("rappel")
                                .executes(ctx -> debugRappel(ctx.getSource())))
                        .then(Commands.literal("guncache")
                                .executes(ctx -> debugGunCache(ctx.getSource()))))
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
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.invasion.started",
                spawn.bases(), spawn.aiVehicles(), spawn.playerVehicles()), true);
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

        VehicleEntity weapon = EmplacementSpawner.spawn(level, pos, type, faction, ownerId, null);
        if (weapon == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.emplacement_fail"));
            return 0;
        }
        VehicleDrops.markCrewAndHull(weapon);

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
                                .suggests((c, b) -> suggestPool(c.getSource(), faction, b))
                                .executes(ctx -> spawnTank(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPool(c.getSource(), faction, b))
                        .executes(ctx -> spawnTank(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static CompletableFuture<Suggestions> suggestPool(CommandSourceStack source, TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                faction.vehiclePool(source.getLevel()).stream().map(String::valueOf), builder);
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
                                .suggests((c, b) -> suggestShipPool(c.getSource(), faction, b))
                                .executes(ctx -> spawnShip(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestShipPool(c.getSource(), faction, b))
                        .executes(ctx -> spawnShip(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static CompletableFuture<Suggestions> suggestShipPool(CommandSourceStack source, TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                faction.shipPool(source.getLevel()).stream().map(String::valueOf), builder);
    }

    // Mirrors shipSpawn against the faction's plane pool. RU/US go airborne; PMC takes off from ground.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> planeSpawn(String literal, TankFaction faction) {
        return Commands.literal(literal)
                .executes(ctx -> spawnPlane(ctx.getSource(), faction, null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> spawnPlane(ctx.getSource(), faction, null,
                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                        .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggestPlanePool(c.getSource(), faction, b))
                                .executes(ctx -> spawnPlane(ctx.getSource(), faction,
                                        StringArgumentType.getString(ctx, "vehicle"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.argument("vehicle", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestPlanePool(c.getSource(), faction, b))
                        .executes(ctx -> spawnPlane(ctx.getSource(), faction,
                                StringArgumentType.getString(ctx, "vehicle"), null)));
    }

    private static CompletableFuture<Suggestions> suggestPlanePool(CommandSourceStack source, TankFaction faction, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                faction.planePool(source.getLevel()).stream().map(String::valueOf), builder);
    }

    private static int spawnTank(CommandSourceStack source, TankFaction faction,
                                 @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        // A specific id is only honored if the world pool actually contains it — catch
        // it here so the operator gets a clear reason rather than the generic failure.
        if (vehicleId != null && !faction.vehiclePool(level).contains(vehicleId)) {
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
        VehicleDrops.markCrewAndHull(tank);

        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.spawn.success", faction.name(), pos.toShortString()), true);
        return 1;
    }

    private static int spawnShip(CommandSourceStack source, TankFaction faction,
                                  @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        if (vehicleId != null && !faction.shipPool(level).contains(vehicleId)) {
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
        VehicleDrops.markCrewAndHull(ship);

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), ship.blockPosition().toShortString()), true);
        return 1;
    }

    private static int spawnPlane(CommandSourceStack source, TankFaction faction,
                                  @Nullable String vehicleId, @Nullable BlockPos explicitPos) {
        ServerLevel level = source.getLevel();

        if (vehicleId != null && !faction.planePool(level).contains(vehicleId)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.not_in_pool", vehicleId, faction.name()));
            return 0;
        }

        UUID ownerId = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;

        BlockPos requestedPos = explicitPos != null
                ? explicitPos
                : TankSpawner.adjustHeight(level, BlockPos.containing(source.getPosition()));
        VehicleEntity plane = TankSpawner.spawnPlaneWithCrew(level, requestedPos, faction, ownerId, vehicleId);

        if (plane == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.spawn.fail"));
            return 0;
        }
        VehicleDrops.markCrewAndHull(plane);

        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.spawn.success", faction.name(), plane.blockPosition().toShortString()), true);
        return 1;
    }
}
