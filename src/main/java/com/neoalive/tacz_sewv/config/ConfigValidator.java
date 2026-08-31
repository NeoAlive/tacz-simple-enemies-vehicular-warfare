package com.neoalive.tacz_sewv.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.resources.ResourceLocation;

public final class ConfigValidator {

    private ConfigValidator() {}

    public static boolean isValid(ConfigEntry entry, String draft) {
        return parse(entry, draft) != null;
    }

    /** Returns parsed value, or null when invalid. */
    public static Object parse(ConfigEntry entry, String draft) {
        return switch (entry.type) {
            case BOOLEAN -> parseBool(draft);
            case INT -> parseInt(entry, draft);
            case DOUBLE -> parseDouble(entry, draft);
            case STRING -> draft;
            case ENUM -> parseEnum(entry, draft);
            case HEX_COLOR -> parseHex(draft);
            case RESOURCE_ID -> parseResourceId(draft);
            case MULTILINE_IDS -> parseMultilineIds(draft);
            case GAMERULE_BOOL -> parseBool(draft);
            case SHORTCUT -> "";
        };
    }

    public static String formatDraft(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object line : list) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
        if (value instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(value);
    }

    private static Boolean parseBool(String draft) {
        String s = draft.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "on".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "off".equals(s)) return false;
        return null;
    }

    private static Integer parseInt(ConfigEntry entry, String draft) {
        try {
            int v = Integer.parseInt(draft.trim());
            if (entry.min != null && v < entry.min) return null;
            if (entry.max != null && v > entry.max) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(ConfigEntry entry, String draft) {
        try {
            double v = Double.parseDouble(draft.trim());
            if (entry.min != null && v < entry.min) return null;
            if (entry.max != null && v > entry.max) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseEnum(ConfigEntry entry, String draft) {
        if (entry.enumOptions == null) return null;
        String s = draft.trim().toLowerCase(Locale.ROOT);
        for (String opt : entry.enumOptions) {
            if (opt.equalsIgnoreCase(s)) return opt;
        }
        return null;
    }

    private static String parseHex(String draft) {
        String s = draft.trim().replace("#", "");
        if (!s.matches("[0-9A-Fa-f]{6}")) return null;
        return s.toUpperCase(Locale.ROOT);
    }

    private static String parseResourceId(String draft) {
        String s = draft.trim();
        return ResourceLocation.tryParse(s) != null ? s : null;
    }

    private static List<String> parseMultilineIds(String draft) {
        if (draft.isBlank()) return List.of();
        String[] lines = draft.split("\n");
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (ResourceLocation.tryParse(s) == null) return null;
            out.add(s);
        }
        return out;
    }
}
