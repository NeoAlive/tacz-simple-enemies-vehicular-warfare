package com.neoalive.tacz_sewv.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import com.neoalive.tacz_sewv.zone.SpawnZoneData;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;
import com.neoalive.tacz_sewv.zone.ZoneType;

/** {@code /sewv zones add|remove <type>} and {@code /sewv debug removeSpawnZones <scope>}. */
final class ZoneCommand {

    private static final String[] TYPES = {"prioritization", "nozone"};
    private static final String[] SCOPES = {"all", "nearby", "all_prioritization", "all_nozones"};
    private static final int NEARBY_CHUNKS = 8;

    private ZoneCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> zones(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add").then(typeArg(true)))
                .then(Commands.literal("remove").then(typeArg(false)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> typeArg(
            boolean add) {
        return Commands.argument("type", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(TYPES, b))
                .executes(ctx -> mark(ctx.getSource(), StringArgumentType.getString(ctx, "type"), add));
    }

    static LiteralArgumentBuilder<CommandSourceStack> debugRemove() {
        return Commands.literal("removeSpawnZones")
                .then(Commands.argument("scope", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(SCOPES, b))
                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "scope"))));
    }

    private static int mark(CommandSourceStack source, String typeName, boolean add) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.tacz_sewv.zones.player_only"));
            return 0;
        }
        ZoneType type = ZoneType.parse(typeName);
        if (type == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.zones.bad_type", typeName));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        ChunkPos cp = player.chunkPosition();
        SpawnZoneData data = SpawnZoneData.get(level);
        Component label = Component.translatable("gui.tacz_sewv.zones.type." + type.name().toLowerCase());
        if (add) {
            boolean changed = data.setManual(cp.x, cp.z, type);
            source.sendSuccess(() -> Component.translatable(changed
                    ? "command.tacz_sewv.zones.added" : "command.tacz_sewv.zones.unchanged", label, cp.x, cp.z), true);
            return changed ? 1 : 0;
        }
        if (data.layers().manualType(ZoneGeometry.pack(cp.x, cp.z)) != type) {
            source.sendFailure(Component.translatable("command.tacz_sewv.zones.not_marked", label, cp.x, cp.z));
            return 0;
        }
        data.setManual(cp.x, cp.z, ZoneType.NEUTRAL);
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.zones.removed", label, cp.x, cp.z), true);
        if (data.layers().beaconCovered(ZoneGeometry.pack(cp.x, cp.z))) {
            source.sendSuccess(() -> Component.translatable("command.tacz_sewv.zones.beacon_still"), false);
        }
        return 1;
    }

    private static int remove(CommandSourceStack source, String scope) {
        int removed = 0;
        int beaconLeft = 0;
        switch (scope) {
            case "nearby" -> {
                ServerLevel level = source.getLevel();
                int cx = ((int) Math.floor(source.getPosition().x)) >> 4;
                int cz = ((int) Math.floor(source.getPosition().z)) >> 4;
                SpawnZoneData data = SpawnZoneData.get(level);
                removed = data.clearManual(null, k -> Math.abs(ZoneGeometry.x(k) - cx) <= NEARBY_CHUNKS
                        && Math.abs(ZoneGeometry.z(k) - cz) <= NEARBY_CHUNKS);
                beaconLeft = data.layers().beaconCoverCount();
            }
            case "all", "all_prioritization", "all_nozones" -> {
                ZoneType only = switch (scope) {
                    case "all_prioritization" -> ZoneType.PRIORITY;
                    case "all_nozones" -> ZoneType.NOZONE;
                    default -> null;
                };
                for (ServerLevel level : source.getServer().getAllLevels()) {
                    SpawnZoneData data = SpawnZoneData.get(level);
                    removed += data.clearManual(only, null);
                    beaconLeft += data.layers().beaconCoverCount();
                }
            }
            default -> {
                source.sendFailure(Component.translatable("command.tacz_sewv.zones.bad_scope", scope));
                return 0;
            }
        }
        int r = removed, b = beaconLeft;
        source.sendSuccess(() -> Component.translatable("command.tacz_sewv.zones.cleared", r, b), true);
        return removed;
    }
}
