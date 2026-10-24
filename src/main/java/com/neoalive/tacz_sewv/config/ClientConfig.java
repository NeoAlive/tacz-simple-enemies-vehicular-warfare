package com.neoalive.tacz_sewv.config;

import javax.annotation.Nullable;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.platform.DistExecutor;

@EventBusSubscriber(modid = TaczSewv.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;
    public static final ModConfigSpec.BooleanValue FACTION_COLORS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> COLOR_RU;
    public static final ModConfigSpec.ConfigValue<String> COLOR_US;
    public static final ModConfigSpec.ConfigValue<String> COLOR_PMC;
    public static final ModConfigSpec.BooleanValue MAP_MARKERS_ENABLED;
    public static final ModConfigSpec.BooleanValue MAP_LIVE;
    public static final ModConfigSpec.BooleanValue MAP_SHOW_ICONS;
    public static final ModConfigSpec.BooleanValue MAP_SHOW_HEALTH_BAR;
    public static final ModConfigSpec.BooleanValue MAP_SHOW_ENERGY_BAR;
    public static final ModConfigSpec.BooleanValue MAP_SHOW_COMMAND_DEBUG;
    public static final ModConfigSpec.BooleanValue HELI_SHOW_RUN_PHASE;
    public static final ModConfigSpec.BooleanValue NOTIFICATIONS_ENABLED;
    public static final ModConfigSpec.IntValue NOTIFICATION_SCREEN_SECONDS;
    public static final ModConfigSpec.IntValue NOTIFICATION_MAX_VISIBLE;
    public static final ModConfigSpec.BooleanValue HELMET_GRAYSCALE;
    public static final ModConfigSpec.BooleanValue SHOW_LAG_TIP;

    // Formerly ModGameRules debug toggles — live via Config UI Client → Debug.
    public static final ModConfigSpec.BooleanValue GROUND_PATHING_DEBUG;
    public static final ModConfigSpec.BooleanValue SHIP_PATHING_DEBUG;
    public static final ModConfigSpec.BooleanValue SEWV_DIAG_DEBUG;
    public static final ModConfigSpec.BooleanValue HELI_COMBAT_DEBUG;
    public static final ModConfigSpec.BooleanValue HELI_FLIGHT_DEBUG;
    public static final ModConfigSpec.BooleanValue PLANE_COMBAT_DEBUG;
    public static final ModConfigSpec.BooleanValue MORTAR_DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue BALLISTIC_TRANSLATION_DEBUG;
    public static final ModConfigSpec.BooleanValue ORDER_FAILURE_DEBUG;
    public static final ModConfigSpec.BooleanValue SHOW_SPAWN_PROBES;
    public static final ModConfigSpec.BooleanValue INDIVIDUAL_TACTICS_DEBUG;
    public static final ModConfigSpec.BooleanValue COLLECTIVE_AI_DEBUG;
    public static final ModConfigSpec.BooleanValue INDIVIDUAL_AI_DEBUG;
    public static final ModConfigSpec.BooleanValue FOB_DEBUG;

    /** Temporary on/off from the map-markers keybind; null = follow the setting below. */
    @Nullable
    private static volatile Boolean MAP_MARKERS_SESSION_OVERRIDE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

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
        NOTIFICATIONS_ENABLED = builder
                .comment("Show HUD notification banners (unit downed, vehicle destroyed, ammo out, and so on).")
                .define("notificationsEnabled", true);
        NOTIFICATION_SCREEN_SECONDS = builder
                .comment("How many seconds each HUD notification ticket stays on screen before it slides away.")
                .defineInRange("notificationScreenSeconds", 5, 1, 30);
        NOTIFICATION_MAX_VISIBLE = builder
                .comment("How many HUD notification tickets may show stacked at once (extras wait in a backlog).")
                .defineInRange("notificationMaxVisible", 3, 1, 8);
        HELMET_GRAYSCALE = builder
                .comment("Desaturate only the PMC MICH DogTag UV to low-contrast grey (logo alpha kept).")
                .define("helmetGrayscale", true);
        SHOW_LAG_TIP = builder
                .comment("On joining a world, print a chat tip on disabling SuperbWarfare's projectile chunk loading.")
                .define("showLagTip", true);
        builder.pop();

        builder.push("map");
        MAP_MARKERS_ENABLED = builder
                .comment("Show unit and vehicle markers on Xaero's World Map, and allow ordering from the map.",
                        "You can also toggle this in-game with the map markers keybind.")
                .define("mapMarkersEnabled", true);
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
        SEWV_DIAG_DEBUG = builder.comment("General [sewv-diag] channels (targeting, scan, claim and so on).")
                .define("sewvDiagDebug", false);
        HELI_COMBAT_DEBUG = builder.comment("Helicopter combat / run-phase diagnosis (logs + overlay).")
                .define("heliCombatDebug", false);
        HELI_FLIGHT_DEBUG = builder.comment("Helicopter flight-stack telemetry: one [sewv heli] line per hull per second",
                        "(procedure, state, reference, errors, saturation). Server side; singleplayer reads this flag.")
                .define("heliFlightDebug", false);
        PLANE_COMBAT_DEBUG = builder.comment("Fixed-wing combat / landing diagnosis (logs + client arcs).")
                .define("planeCombatDebug", false);
        MORTAR_DEBUG_LOGGING = builder.comment("Log mortar / Type-63 crew gates (why a tube is holding).")
                .define("mortarDebugLogging", false);
        BALLISTIC_TRANSLATION_DEBUG = builder.comment("Log the TaCZ to SBW damage translation for every translated hit.")
                .define("ballisticTranslationDebug", false);
        ORDER_FAILURE_DEBUG = builder.comment("Log refused orders. On by default, and silent until something fails.")
                .define("orderFailureDebug", true);
        SHOW_SPAWN_PROBES = builder.comment("Render spawn_probe blocks with the barrier placeholder texture.")
                .define("showSpawnProbes", false);
        INDIVIDUAL_TACTICS_DEBUG = builder.comment("Log cover-cache bake and per-crew posture.")
                .define("individualTacticsDebug", false);
        COLLECTIVE_AI_DEBUG = builder.comment("Log battle groups, plays, per-crew roles/destinations and idle formation clusters.")
                .define("collectiveAiDebug", false);
        INDIVIDUAL_AI_DEBUG = builder.comment("Log each ground crew's utility decision (plan, scores, blocked actions, tasked role).")
                .define("individualAiDebug", false);
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
    public static boolean flag(ModConfigSpec.BooleanValue value) {
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
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                com.neoalive.tacz_sewv.client.NotificationHud.refreshScreenTimeCache();
                com.neoalive.tacz_sewv.client.skin.HelmetLogoTextures.clear();
            });
        }
    }
}
