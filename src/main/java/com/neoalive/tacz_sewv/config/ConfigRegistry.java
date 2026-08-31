package com.neoalive.tacz_sewv.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.world.level.GameRules;
import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigRegistry {

    private static final List<ConfigEntry> ENTRIES = new ArrayList<>();
    private static final Map<String, ConfigEntry> BY_KEY = new LinkedHashMap<>();
    private static final Map<Integer, ConfigEntry> BY_INDEX = new LinkedHashMap<>();
    private static boolean bootstrapped;

    private ConfigRegistry() {}

    public static void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        ConfigRegistryBootstrap.registerAll(new Builder());
    }

    public static List<ConfigEntry> entries() {
        bootstrap();
        return Collections.unmodifiableList(ENTRIES);
    }

    public static ConfigEntry byIndex(int index) {
        bootstrap();
        return BY_INDEX.get(index);
    }

    @Nullable
    public static ConfigEntry byKey(String key) {
        bootstrap();
        return BY_KEY.get(key);
    }

    public static List<ConfigEntry> forScope(ConfigScope scope) {
        bootstrap();
        return ENTRIES.stream().filter(e -> e.scope == scope).toList();
    }

    public static List<String> categoriesForScope(ConfigScope scope) {
        bootstrap();
        List<String> out = new ArrayList<>();
        for (ConfigEntry e : ENTRIES) {
            if (e.scope == scope && !out.contains(e.category)) {
                out.add(e.category);
            }
        }
        return out;
    }

    public static List<ConfigEntry> forCategory(ConfigScope scope, String category) {
        bootstrap();
        return ENTRIES.stream()
                .filter(e -> e.scope == scope && e.category.equals(category))
                .toList();
    }

    public static int entryCount() {
        bootstrap();
        return ENTRIES.size();
    }

    public static final class Builder {
        private int nextIndex;

        public Builder bool(ConfigScope scope, String category, String key,
                          ForgeConfigSpec.BooleanValue spec, Consumer<Boolean> write) {
            return add(scope, category, key, ConfigValueType.BOOLEAN, null, null, null, null, null,
                    () -> spec.get(), v -> write.accept((Boolean) v), () -> spec.getDefault());
        }

        public Builder bool(ConfigScope scope, String category, String key,
                          Supplier<Boolean> read, Consumer<Boolean> write) {
            return add(scope, category, key, ConfigValueType.BOOLEAN, null, null, null, null, null,
                    () -> read.get(), v -> write.accept((Boolean) v), null);
        }

        public Builder intRange(ConfigScope scope, String category, String key, int min, int max,
                                ForgeConfigSpec.IntValue spec, Consumer<Integer> write) {
            return add(scope, category, key, ConfigValueType.INT, (double) min, (double) max, null, null, null,
                    () -> spec.get(), v -> write.accept(((Number) v).intValue()), () -> spec.getDefault());
        }

        public Builder intRange(ConfigScope scope, String category, String key, int min, int max,
                                Supplier<Integer> read, Consumer<Integer> write) {
            return add(scope, category, key, ConfigValueType.INT, (double) min, (double) max, null, null, null,
                    () -> read.get(), v -> write.accept(((Number) v).intValue()), null);
        }

        public Builder doubleRange(ConfigScope scope, String category, String key, double min, double max,
                                   ForgeConfigSpec.DoubleValue spec, Consumer<Double> write) {
            return add(scope, category, key, ConfigValueType.DOUBLE, min, max, null, null, null,
                    () -> spec.get(), v -> write.accept(((Number) v).doubleValue()), () -> spec.getDefault());
        }

        public Builder doubleRange(ConfigScope scope, String category, String key, double min, double max,
                                   Supplier<Double> read, Consumer<Double> write) {
            return add(scope, category, key, ConfigValueType.DOUBLE, min, max, null, null, null,
                    () -> read.get(), v -> write.accept(((Number) v).doubleValue()), null);
        }

        public Builder string(ConfigScope scope, String category, String key,
                              ForgeConfigSpec.ConfigValue<String> spec, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.STRING, null, null, null, null, null,
                    () -> spec.get(), v -> write.accept((String) v), () -> spec.getDefault());
        }

        public Builder string(ConfigScope scope, String category, String key,
                              Supplier<String> read, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.STRING, null, null, null, null, null,
                    () -> read.get(), v -> write.accept((String) v), null);
        }

        public Builder resourceId(ConfigScope scope, String category, String key,
                                  ForgeConfigSpec.ConfigValue<String> spec, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.RESOURCE_ID, null, null, null, null, null,
                    () -> spec.get(), v -> write.accept((String) v), () -> spec.getDefault());
        }

        public Builder resourceId(ConfigScope scope, String category, String key,
                                  Supplier<String> read, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.RESOURCE_ID, null, null, null, null, null,
                    () -> read.get(), v -> write.accept((String) v), null);
        }

        public Builder hexColor(ConfigScope scope, String category, String key,
                                ForgeConfigSpec.ConfigValue<String> spec, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.HEX_COLOR, null, null, null, null, null,
                    () -> spec.get(), v -> write.accept((String) v), () -> spec.getDefault());
        }

        public Builder hexColor(ConfigScope scope, String category, String key,
                                Supplier<String> read, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.HEX_COLOR, null, null, null, null, null,
                    () -> read.get(), v -> write.accept((String) v), null);
        }

        public Builder enumChoice(ConfigScope scope, String category, String key, List<String> options,
                                  ForgeConfigSpec.ConfigValue<String> spec, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.ENUM, null, null, List.copyOf(options), null, null,
                    () -> spec.get(), v -> write.accept((String) v), () -> spec.getDefault());
        }

        public Builder enumChoice(ConfigScope scope, String category, String key, List<String> options,
                                  Supplier<String> read, Consumer<String> write) {
            return add(scope, category, key, ConfigValueType.ENUM, null, null, List.copyOf(options), null, null,
                    () -> read.get(), v -> write.accept((String) v), null);
        }

        public Builder multilineIds(ConfigScope scope, String category, String key,
                                    ForgeConfigSpec.ConfigValue<List<? extends String>> spec,
                                    Consumer<List<String>> write) {
            return add(scope, category, key, ConfigValueType.MULTILINE_IDS, null, null, null, null, null,
                    () -> spec.get(), v -> write.accept((List<String>) v), () -> spec.getDefault());
        }

        public Builder multilineIds(ConfigScope scope, String category, String key,
                                    Supplier<List<String>> read, Consumer<List<String>> write) {
            return add(scope, category, key, ConfigValueType.MULTILINE_IDS, null, null, null, null, null,
                    () -> read.get(), v -> write.accept((List<String>) v), null);
        }

        public Builder shortcut(ConfigScope scope, String category, String key, String action) {
            return add(scope, category, key, ConfigValueType.SHORTCUT, null, null, null, null, action,
                    () -> "", null, null);
        }

        public Builder gamerule(ConfigScope scope, String category, String key,
                                GameRules.Key<GameRules.BooleanValue> rule, boolean defaultOn) {
            return add(scope, category, key, ConfigValueType.GAMERULE_BOOL, null, null, null, rule, null,
                    () -> false, null, () -> defaultOn);
        }

        public Builder gamerule(ConfigScope scope, String category, String key,
                                GameRules.Key<GameRules.BooleanValue> rule) {
            return gamerule(scope, category, key, rule, true);
        }

        private Builder add(ConfigScope scope, String category, String key, ConfigValueType type,
                            @Nullable Double min, @Nullable Double max, @Nullable List<String> enumOptions,
                            @Nullable GameRules.Key<GameRules.BooleanValue> gameruleKey,
                            @Nullable String shortcutAction,
                            Supplier<Object> read, @Nullable Consumer<Object> write,
                            @Nullable Supplier<Object> defaultReader) {
            int index = nextIndex++;
            ConfigEntry entry = new ConfigEntry(index, key, scope, category, type, min, max, enumOptions,
                    gameruleKey, shortcutAction, read, write, defaultReader);
            ENTRIES.add(entry);
            BY_KEY.put(key, entry);
            BY_INDEX.put(index, entry);
            return this;
        }
    }
}
