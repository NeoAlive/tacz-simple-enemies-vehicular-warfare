package com.neoalive.tacz_sewv.config;

import javax.annotation.Nullable;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
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
    public static final ForgeConfigSpec.IntValue NOTIFICATION_SCREEN_SECONDS;

    // Formerly ModGameRules debug toggles — live via Config UI Client → Debug.
    public static final ForgeConfigSpec.BooleanValue GROUND_PATHING_DEBUG;
    public static final ForgeConfigSpec.BooleanValue SHIP_PATHING_DEBUG;
    public static final ForgeConfigSpec.BooleanValue SEWV_DIAG_DEBUG;
    public static final ForgeConfigSpec.BooleanValue OUTER_RING_DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue HELI_COMBAT_DEBUG;
    public static final ForgeConfigSpec.BooleanValue HELI_FLIGHT_DEBUG;
    public static final ForgeConfigSpec.BooleanValue PLANE_COMBAT_DEBUG;
    public static final ForgeConfigSpec.BooleanValue MORTAR_DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue BALLISTIC_TRANSLATION_DEBUG;
    public static final ForgeConfigSpec.BooleanValue ORDER_FAILURE_DEBUG;
    public static final ForgeConfigSpec.BooleanValue TARGET_VETO_DEBUG;
    public static final ForgeConfigSpec.BooleanValue TRIPOD_SHIELD_FLARE_ALWAYS_ON;
    public static final ForgeConfigSpec.BooleanValue TRIPOD_SHIELD_WIREFRAME;
    public static final ForgeConfigSpec.BooleanValue SHOW_SPAWN_PROBES;
    public static final ForgeConfigSpec.BooleanValue INDIVIDUAL_TACTICS_DEBUG;
    public static final ForgeConfigSpec.BooleanValue FOB_DEBUG;

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
        NOTIFICATION_SCREEN_SECONDS = builder
                .comment("How many seconds a HUD notification stays on screen before the next queued one",
                        "(or the banner slides away).")
                .defineInRange("notificationScreenSeconds", 5, 1, 30);
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

        builder.push("debug");
        GROUND_PATHING_DEBUG = builder.comment("Log ground vehicle pathing / shoreline probes.")
                .define("groundPathingDebug", false);
        SHIP_PATHING_DEBUG = builder.comment("Log ship pathing / depth probes.")
                .define("shipPathingDebug", false);
        SEWV_DIAG_DEBUG = builder.comment("General [sewv-diag] channels (targeting, scan, claim, …).")
                .define("sewvDiagDebug", false);
        OUTER_RING_DEBUG_LOGGING = builder.comment("Log outer-ring awareness / cue polls.")
                .define("outerRingDebugLogging", false);
        HELI_COMBAT_DEBUG = builder.comment("Helicopter combat / run-phase diagnosis (logs + overlay).")
                .define("heliCombatDebug", false);
        HELI_FLIGHT_DEBUG = builder.comment("Helicopter flyToward / hover investigation logs.")
                .define("heliFlightDebug", false);
        PLANE_COMBAT_DEBUG = builder.comment("Fixed-wing combat / landing diagnosis (logs + client arcs).")
                .define("planeCombatDebug", false);
        MORTAR_DEBUG_LOGGING = builder.comment("Log mortar / Type-63 crew gates (why a tube is holding).")
                .define("mortarDebugLogging", false);
        BALLISTIC_TRANSLATION_DEBUG = builder.comment("Print TaCZ→SBW ballistic translation for every translated hit.")
                .define("ballisticTranslationDebug", false);
        ORDER_FAILURE_DEBUG = builder.comment("Log refused orders (default on — silent until something fails).")
                .define("orderFailureDebug", true);
        TARGET_VETO_DEBUG = builder.comment("Log target vetoes (the noisy half of order-failure reporting).")
                .define("targetVetoDebug", true);
        TRIPOD_SHIELD_FLARE_ALWAYS_ON = builder.comment("Extermination: keep pod-shield flare sparks always on.")
                .define("tripodShieldFlareAlwaysOn", false);
        TRIPOD_SHIELD_WIREFRAME = builder.comment("Extermination: draw pod-shield debug wireframe.")
                .define("tripodShieldWireframe", false);
        SHOW_SPAWN_PROBES = builder.comment("Render spawn_probe blocks with the barrier placeholder texture.")
                .define("showSpawnProbes", false);
        INDIVIDUAL_TACTICS_DEBUG = builder.comment("Log cover-cache bake and per-crew posture.")
                .define("individualTacticsDebug", false);
        FOB_DEBUG = builder.comment("FOB route, assign, resupply, and stale-state logging.")
                .define("fobDebug", false);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientConfig() {}

    /**
     * Safe read for debug flags from common/server code. Client config is always loaded in
     * singleplayer; on a dedicated server with no client config baked, returns the default.
     */
    public static boolean flag(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (Throwable t) {
            return Boolean.TRUE.equals(value.getDefault());
        }
    }

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
        if (event.getConfig().getSpec() == SPEC) {
            FactionColors.refreshConfigArgb();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.neoalive.tacz_sewv.client.NotificationHud.refreshScreenTimeCache());
        }
    }
}
