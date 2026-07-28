package com.neoalive.tacz_sewv.config;

import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;

public final class ClientConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;
    public static final ForgeConfigSpec.BooleanValue FACTION_COLORS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_RU;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_US;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_PMC;
    public static final ForgeConfigSpec.BooleanValue MAP_MARKERS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MAP_LIVE;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_ICONS;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_HEALTH_BAR;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_ENERGY_BAR;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_COMMAND_DEBUG;
    public static final ForgeConfigSpec.BooleanValue HELI_SHOW_RUN_PHASE;

    /** Session flip for the map-markers keybind; {@code null} means “use config”. */
    @Nullable
    private static volatile Boolean MAP_MARKERS_SESSION_OVERRIDE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("interaction");
        SHOW_ORDER_FEEDBACK = builder
                .comment("Show action-bar confirmations for successful orders.")
                .define("showOrderFeedback", true);
        builder.pop();

        builder.push("overlay");
        FACTION_COLORS_ENABLED = builder
                .comment("Color Superb Warfare's hover overlay by the crew's faction.")
                .define("factionColorsEnabled", true);
        COLOR_RU = builder
                .comment("RU overlay/map color as RRGGBB.")
                .define("colorRu", "FF5555");
        COLOR_US = builder
                .comment("US overlay/map color as RRGGBB.")
                .define("colorUs", "5555FF");
        COLOR_PMC = builder
                .comment("PMC overlay/map color as RRGGBB.")
                .define("colorPmc", "55FF55");
        HELI_SHOW_RUN_PHASE = builder
                .comment("Append AI helicopter firing-run phase (INGRESS/ATTACK/BREAK/…) to the hover name.")
                .define("heliShowRunPhase", true);
        builder.pop();

        builder.push("map");
        MAP_MARKERS_ENABLED = builder
                .comment("Show SEWV unit/vehicle markers and map ordering UI on Xaero's World Map.",
                        "User-facing clutter toggle (not debug). Toggle in-game with the map markers keybind.")
                .define("mapMarkersEnabled", true);
        MAP_LIVE = builder
                .comment("Keep singleplayer running while Xaero's map is open.")
                .define("mapLive", true);
        MAP_SHOW_ICONS = builder
                .comment("Draw unit-type icons on map markers.")
                .define("mapShowIcons", true);
        MAP_SHOW_HEALTH_BAR = builder
                .comment("Draw health bars under owned PMC vehicle markers.")
                .define("mapShowHealthBar", true);
        MAP_SHOW_ENERGY_BAR = builder
                .comment("Draw energy bars under owned PMC vehicle markers.")
                .define("mapShowEnergyBar", true);
        MAP_SHOW_COMMAND_DEBUG = builder
                .comment("Debug: commander ★/·, BattleField overlay, play name, per-tank role tags (BoF/MNV/…).")
                .define("mapShowCommandDebug", false);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientConfig() {}

    /**
     * Effective map-marker visibility. Prefer this over {@link #MAP_MARKERS_ENABLED}{@code .get()}
     * so the in-game keybind session override is honoured without rewriting the toml every press.
     */
    public static boolean mapMarkersEnabled() {
        Boolean override = MAP_MARKERS_SESSION_OVERRIDE;
        return override != null ? override : MAP_MARKERS_ENABLED.get();
    }

    /** Toggle markers for this client session; returns the new visible state. */
    public static boolean toggleMapMarkersSession() {
        boolean next = !mapMarkersEnabled();
        MAP_MARKERS_SESSION_OVERRIDE = next;
        return next;
    }

    public static int parseColor(String hex, int fallback) {
        try {
            return 0xFF000000 | (Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
