package com.neoalive.tacz_sewv.config;

import javax.annotation.Nullable;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.map.FactionColors;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;
    public static final ForgeConfigSpec.BooleanValue FACTION_COLORS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_RU;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_US;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_PMC;
    public static final ForgeConfigSpec.BooleanValue MAP_MARKERS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MAP_TRENCH_MARKERS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MAP_LIVE;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_ICONS;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_HEALTH_BAR;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_ENERGY_BAR;
    public static final ForgeConfigSpec.BooleanValue MAP_SHOW_COMMAND_DEBUG;
    public static final ForgeConfigSpec.BooleanValue HELI_SHOW_RUN_PHASE;

    /** Temporary on/off from the map-markers keybind; null = follow the setting below. */
    @Nullable
    private static volatile Boolean MAP_MARKERS_SESSION_OVERRIDE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("interaction");
        SHOW_ORDER_FEEDBACK = builder
                .comment("Show a short message above the hotbar when an order succeeds.")
                .define("showOrderFeedback", true);
        builder.pop();

        builder.push("overlay");
        FACTION_COLORS_ENABLED = builder
                .comment("Tint Superb Warfare's vehicle hover text by who is crewing it (RU / US / PMC).")
                .define("factionColorsEnabled", true);
        COLOR_RU = builder
                .comment("RU colour as six hex digits (RRGGBB), e.g. FF5555.")
                .define("colorRu", "FF5555");
        COLOR_US = builder
                .comment("US colour as six hex digits (RRGGBB), e.g. 5555FF.")
                .define("colorUs", "5555FF");
        COLOR_PMC = builder
                .comment("PMC colour as six hex digits (RRGGBB), e.g. 55FF55.")
                .define("colorPmc", "55FF55");
        HELI_SHOW_RUN_PHASE = builder
                .comment("On AI helicopters, append the current attack phase to the hover name",
                        "(approach, attack, break away, and so on).")
                .define("heliShowRunPhase", true);
        builder.pop();

        builder.push("map");
        MAP_MARKERS_ENABLED = builder
                .comment("Show unit and vehicle markers on Xaero's World Map, and allow ordering from the map.",
                        "You can also toggle this in-game with the map markers keybind.")
                .define("mapMarkersEnabled", true);
        MAP_TRENCH_MARKERS_ENABLED = builder
                .comment("Show trench-network and emplacement markers on Xaero's World Map.")
                .define("mapTrenchMarkersEnabled", true);
        MAP_LIVE = builder
                .comment("In singleplayer, keep the world running while Xaero's map screen is open",
                        "(otherwise the game pauses and markers go stale).")
                .define("mapLive", true);
        MAP_SHOW_ICONS = builder
                .comment("Draw vehicle-type icons on map markers.")
                .define("mapShowIcons", true);
        MAP_SHOW_HEALTH_BAR = builder
                .comment("Draw a health bar under your own PMC vehicle markers.")
                .define("mapShowHealthBar", true);
        MAP_SHOW_ENERGY_BAR = builder
                .comment("Draw an energy/fuel bar under your own PMC vehicle markers.")
                .define("mapShowEnergyBar", true);
        MAP_SHOW_COMMAND_DEBUG = builder
                .comment("Developer overlay: battle-group commander marks, play names, and per-tank role tags.",
                        "Leave off unless you are debugging AI command.")
                .define("mapShowCommandDebug", false);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientConfig() {}

    /**
     * Whether map markers are shown right now. Uses the keybind override when set,
     * otherwise the config value — so toggling in-game does not rewrite the config file.
     */
    public static boolean mapMarkersEnabled() {
        Boolean override = MAP_MARKERS_SESSION_OVERRIDE;
        return override != null ? override : MAP_MARKERS_ENABLED.get();
    }

    public static boolean mapTrenchMarkersEnabled() {
        return MAP_TRENCH_MARKERS_ENABLED.get();
    }

    /** Flip markers on/off for this session; returns whether they are now visible. */
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

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) FactionColors.refreshConfigArgb();
    }
}
