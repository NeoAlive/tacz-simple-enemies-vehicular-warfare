package com.neoalive.tacz_sewv.config;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.world.level.GameRules;

public final class ConfigEntry {

    public final int index;
    public final String key;
    public final ConfigScope scope;
    public final String category;
    public final ConfigValueType type;
    @Nullable public final Double min;
    @Nullable public final Double max;
    @Nullable public final List<String> enumOptions;
    @Nullable public final GameRules.Key<GameRules.BooleanValue> gameruleKey;
    @Nullable public final String shortcutAction;

    private final Supplier<Object> reader;
    @Nullable private final Consumer<Object> writer;
    @Nullable private final Supplier<Object> defaultReader;

    ConfigEntry(int index, String key, ConfigScope scope, String category, ConfigValueType type,
                @Nullable Double min, @Nullable Double max, @Nullable List<String> enumOptions,
                @Nullable GameRules.Key<GameRules.BooleanValue> gameruleKey,
                @Nullable String shortcutAction,
                Supplier<Object> reader, @Nullable Consumer<Object> writer,
                @Nullable Supplier<Object> defaultReader) {
        this.index = index;
        this.key = key;
        this.scope = scope;
        this.category = category;
        this.type = type;
        this.min = min;
        this.max = max;
        this.enumOptions = enumOptions;
        this.gameruleKey = gameruleKey;
        this.shortcutAction = shortcutAction;
        this.reader = reader;
        this.writer = writer;
        this.defaultReader = defaultReader;
    }

    public Object read() {
        return reader.get();
    }

    public void write(Object value) {
        if (writer == null) {
            throw new IllegalStateException("Entry " + key + " is not writable");
        }
        writer.accept(value);
    }

    public boolean isWritable() {
        return writer != null;
    }

    public String draftString() {
        return formatValue(read());
    }

    public String defaultDraftString() {
        Object v = this.defaultReader != null ? this.defaultReader.get() : read();
        return formatValue(v);
    }

    private static String formatValue(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object line : list) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    public static String enumOptionLabelKey(String entryKey, String option) {
        return "config.tacz_sewv." + entryKey + ".option." + option;
    }

    public static String enumOptionTooltipKey(String entryKey, String option) {
        return enumOptionLabelKey(entryKey, option) + ".tooltip";
    }

    public String labelKey() {
        return "config.tacz_sewv." + key;
    }

    public String tooltipKey() {
        return labelKey() + ".tooltip";
    }

    public static String categoryLabelKey(String categoryId) {
        return "gui.tacz_sewv.config.category." + categoryId;
    }
}
