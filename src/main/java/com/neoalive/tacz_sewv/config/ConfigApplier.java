package com.neoalive.tacz_sewv.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;

public final class ConfigApplier {

    private ConfigApplier() {}

    public static Map<Integer, Object> captureServerSnapshot(MinecraftServer server) {
        Map<Integer, Object> out = new HashMap<>();
        for (ConfigEntry e : ConfigRegistry.forScope(ConfigScope.SERVER)) {
            if (e.type == ConfigValueType.SHORTCUT) continue;
            if (e.type == ConfigValueType.GAMERULE_BOOL) {
                if (e.gameruleKey != null) {
                    out.put(e.index, server.getGameRules().getBoolean(e.gameruleKey));
                }
            } else {
                out.put(e.index, e.read());
            }
        }
        return out;
    }

    public static Map<Integer, Object> captureClientSnapshot() {
        Map<Integer, Object> out = new HashMap<>();
        for (ConfigEntry e : ConfigRegistry.forScope(ConfigScope.CLIENT)) {
            out.put(e.index, e.read());
        }
        return out;
    }

    public static void applyClient(Map<Integer, String> drafts) {
        for (Map.Entry<Integer, String> change : drafts.entrySet()) {
            ConfigEntry entry = ConfigRegistry.byIndex(change.getKey());
            if (entry == null || entry.scope != ConfigScope.CLIENT) continue;
            Object parsed = ConfigValidator.parse(entry, change.getValue());
            if (parsed != null) entry.write(parsed);
        }
        ConfigPersistence.saveClient();
    }

    public static boolean applyServer(ServerPlayer player, Map<Integer, String> drafts) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.config.no_permission")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        for (Map.Entry<Integer, String> change : drafts.entrySet()) {
            ConfigEntry entry = ConfigRegistry.byIndex(change.getKey());
            if (entry == null || entry.scope != ConfigScope.SERVER) continue;

            Object parsed = ConfigValidator.parse(entry, change.getValue());
            if (parsed == null) {
                player.displayClientMessage(Component.translatable("message.tacz_sewv.config.invalid",
                                Component.translatable(entry.labelKey()))
                        .withStyle(ChatFormatting.RED), true);
                return false;
            }

            if (entry.type == ConfigValueType.GAMERULE_BOOL && entry.gameruleKey != null) {
                server.getGameRules().getRule(entry.gameruleKey).set((Boolean) parsed, server);
            } else {
                entry.write(parsed);
            }
        }

        ConfigPersistence.saveServer();
        Doctrine.refreshPresets();
        player.displayClientMessage(Component.translatable("message.tacz_sewv.config.saved")
                .withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    public static List<ConfigEntry> writableServerEntries() {
        return ConfigRegistry.forScope(ConfigScope.SERVER).stream()
                .filter(e -> e.type != ConfigValueType.SHORTCUT)
                .toList();
    }
}
