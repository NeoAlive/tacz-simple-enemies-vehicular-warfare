package com.neoalive.tacz_sewv.client.gui.config;

import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import com.neoalive.tacz_sewv.config.ConfigEntry;

public final class ConfigCategoryStyle {

    private static final Map<String, String> EMOJI = Map.ofEntries(
            Map.entry("shortcuts", "\u26A1"),
            Map.entry("world_rules", "\uD83C\uDF0D"),
            Map.entry("events", "\uD83D\uDCA5"),
            Map.entry("resources", "\uD83D\uDCE6"),
            Map.entry("soldiers", "\uD83C\uDF96\uFE0F"),
            Map.entry("structures", "\uD83C\uDFDB\uFE0F"),
            Map.entry("crew_ai", "\u2699\uFE0F"),
            Map.entry("command", "\uD83D\uDCDC"),
            Map.entry("platoon", "\uD83D\uDC65"),
            Map.entry("flight", "\u2708\uFE0F"),
            Map.entry("indirect_fire", "\uD83C\uDFAF"),
            Map.entry("voicelines", "\uD83D\uDD0A"),
            Map.entry("orders", "\uD83D\uDCE1"),
            Map.entry("boarding", "\uD83D\uDEAA"),
            Map.entry("map_intel", "\uD83D\uDDFA\uFE0F"),
            Map.entry("sweep", "\uD83D\uDD04"),
            Map.entry("invasion", "\u26A1"),
            Map.entry("doctrine", "\uD83D\uDCCA"),
            Map.entry("ballistics", "\uD83D\uDCA2"),
            Map.entry("compat_extermination", "\uD83D\uDC7D"),
            Map.entry("compat_trees", "\uD83C\uDF33"),
            Map.entry("interaction", "\uD83D\uDD90"),
            Map.entry("overlay", "\uD83C\uDFA8"),
            Map.entry("map", "\uD83D\uDCCD"));

    private static final Map<String, Integer> COLOR = Map.ofEntries(
            Map.entry("shortcuts", 0xFFFFD54F),
            Map.entry("world_rules", 0xFF81C784),
            Map.entry("events", 0xFFFF8A65),
            Map.entry("resources", 0xFF4DD0E1),
            Map.entry("soldiers", 0xFFE57373),
            Map.entry("structures", 0xFFBA68C8),
            Map.entry("crew_ai", 0xFF64B5F6),
            Map.entry("command", 0xFFFFB74D),
            Map.entry("platoon", 0xFFA1887F),
            Map.entry("flight", 0xFF90CAF9),
            Map.entry("indirect_fire", 0xFFFF7043),
            Map.entry("voicelines", 0xFFCE93D8),
            Map.entry("orders", 0xFF4FC3F7),
            Map.entry("boarding", 0xFFAED581),
            Map.entry("map_intel", 0xFF80DEEA),
            Map.entry("sweep", 0xFFFFF176),
            Map.entry("invasion", 0xFFEF5350),
            Map.entry("doctrine", 0xFF7986CB),
            Map.entry("ballistics", 0xFFFFAB40),
            Map.entry("compat_extermination", 0xFFAB47BC),
            Map.entry("compat_trees", 0xFF66BB6A),
            Map.entry("interaction", 0xFF26C6DA),
            Map.entry("overlay", 0xFFF48FB1),
            Map.entry("map", 0xFF29B6F6));

    private ConfigCategoryStyle() {}

    public static Component ribbonLabel(String categoryId) {
        String emoji = EMOJI.getOrDefault(categoryId, "\u2022");
        int color = COLOR.getOrDefault(categoryId, 0xFFE8ECF0);
        return Component.literal(emoji + " ")
                .withStyle(Style.EMPTY.withColor(color))
                .append(Component.translatable(ConfigEntry.categoryLabelKey(categoryId))
                        .withStyle(Style.EMPTY.withColor(color)));
    }

    public static int accentColor(String categoryId) {
        return COLOR.getOrDefault(categoryId, 0xFF4FD1C5);
    }

    public static int selectedBackground(String categoryId) {
        int c = accentColor(categoryId);
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        return 0xFF000000 | ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (b * 45 / 100);
    }
}
