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
    public static final ModConfigSpec.BooleanValue MINIMAP_MARKERS_ENABLED;
    public static final ModConfigSpec.DoubleValue MINIMAP_ICON_SCALE;
    public static final ModConfigSpec.BooleanValue MINIMAP_SHOW_OVERLAYS;
    public static final ModConfigSpec.BooleanValue MINIMAP_EDGE_PINS;
    public static final ModConfigSpec.BooleanValue MINIMAP_HIDE_RADAR_FOR_MARKED;
    public static final ModConfigSpec.BooleanValue HELI_SHOW_RUN_PHASE;
    public static final ModConfigSpec.BooleanValue NOTIFICATIONS_ENABLED;
    public static final ModConfigSpec.IntValue NOTIFICATION_SCREEN_SECONDS;
    public static final ModConfigSpec.IntValue NOTIFICATION_MAX_VISIBLE;
    public static final ModConfigSpec.BooleanValue RADIO_NOTIFICATION_SOUND;
    public static final ModConfigSpec.BooleanValue RADIO_CHATTER;
    public static final ModConfigSpec.BooleanValue RADIO_VOICE_EFFECT;
    public static final ModConfigSpec.BooleanValue RADIO_VOICE_CLICKS;
    public static final ModConfigSpec.BooleanValue VOICE_ORDERS_ENABLED;
    public static final ModConfigSpec.BooleanValue VOICE_ORDERS_TRANSCRIPT;
    public static final ModConfigSpec.DoubleValue VOICE_ORDERS_MIN_CONFIDENCE;
    public static final ModConfigSpec.ConfigValue<String> VOICE_ORDERS_MIC_DEVICE;
    public static final ModConfigSpec.BooleanValue VOICE_SETUP_PROMPT;
    public static final ModConfigSpec.BooleanValue VOICE_ORDERS_CUES;
    public static final ModConfigSpec.BooleanValue VOICE_ORDERS_TRANSMIT;
    public static final ModConfigSpec.BooleanValue VOICE_ORDERS_ON_RADIO;
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

    public static final ModConfigSpec.BooleanValue GEMRENDER_PARTS_PALETTE_LIFT;
    public static final ModConfigSpec.BooleanValue DOG_TAG_ICON_CACHE;
    public static final ModConfigSpec.BooleanValue VEHICLE_REST_SKIP;
    public static final ModConfigSpec.IntValue VEHICLE_FAR_SIM_DISTANCE;
    public static final ModConfigSpec.IntValue VEHICLE_TURN_SOUND_DISTANCE;
    public static final ModConfigSpec.BooleanValue SEM_UNIT_FRUSTUM_CULL;
    public static final ModConfigSpec.BooleanValue TACZ_THIRD_PERSON_FLUSH_SKIP;
    public static final ModConfigSpec.BooleanValue TACZ_FAST_CUBE_EMIT;
    public static final ModConfigSpec.BooleanValue SEM_ATTACHMENT_LOD;
    public static final ModConfigSpec.IntValue SEM_ATTACHMENT_LOD_DISTANCE;
    public static final ModConfigSpec.BooleanValue SEM_ARMOR_LOD;
    public static final ModConfigSpec.IntValue SEM_ARMOR_LOD_DISTANCE;

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
        RADIO_NOTIFICATION_SOUND = builder
                .comment("Play a radio click or squelch with each HUD notification.")
                .define("radioNotificationSound", true);
        RADIO_CHATTER = builder
                .comment("Play random ambient radio traffic while you carry a handheld radio.")
                .define("radioChatter", true);
        RADIO_VOICE_EFFECT = builder
                .comment("Run voice heard over a handheld radio (Simple Voice Chat) through the radio effect.")
                .define("radioVoiceEffect", true);
        RADIO_VOICE_CLICKS = builder
                .comment("Play press-to-talk clicks at the start and end of radio voice transmissions.")
                .define("radioVoiceClicks", true);
        VOICE_ORDERS_ENABLED = builder
                .comment("Push to talk voice orders (needs VoskLib and a speech model picked in its settings).")
                .define("voiceOrdersEnabled", true);
        VOICE_ORDERS_TRANSCRIPT = builder
                .comment("Show what was heard while the voice order key is held, and the order it became.")
                .define("voiceOrdersTranscript", true);
        VOICE_ORDERS_MIN_CONFIDENCE = builder
                .comment("Words the recognizer is less sure of than this are ignored (0 keeps everything).")
                .defineInRange("voiceOrdersMinConfidence", 0.5, 0.0, 1.0);
        VOICE_ORDERS_MIC_DEVICE = builder
                .comment("Part of the name of the microphone to record voice orders from (the log lists them). Empty uses the system default.")
                .define("voiceOrdersMicDevice", "");
        VOICE_ORDERS_CUES = builder
                .comment("Play a radio key click when the voice order microphone opens and a click and squelch when it closes.")
                .define("voiceOrdersCues", true);
        VOICE_SETUP_PROMPT = builder
                .comment("Offer voice order setup on the title screen while VoskLib has no speech model.")
                .define("voiceSetupPrompt", true);
        VOICE_ORDERS_TRANSMIT = builder
                .comment("With Simple Voice Chat, let other players hear you while you speak a voice order.")
                .define("voiceOrdersTransmit", false);
        VOICE_ORDERS_ON_RADIO = builder
                .comment("With Simple Voice Chat, also take orders from what you say on a handheld radio, no key needed.")
                .define("voiceOrdersOnRadio", false);
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
        MINIMAP_MARKERS_ENABLED = builder
                .comment("Also draw the map markers on Xaero's Minimap. Off still hides them all when",
                        "mapMarkersEnabled (or its keybind) is off.")
                .define("minimapMarkersEnabled", true);
        MINIMAP_ICON_SCALE = builder
                .comment("Size of markers on the minimap, relative to the World Map.")
                .defineInRange("minimapIconScale", 0.6, 0.2, 2.0);
        MINIMAP_SHOW_OVERLAYS = builder
                .comment("Draw standing orders, saved pathways and runways on the minimap.")
                .define("minimapShowOverlays", true);
        MINIMAP_EDGE_PINS = builder
                .comment("Pin your own units to the minimap's rim when they are out of range.")
                .define("minimapEdgePins", true);
        MINIMAP_HIDE_RADAR_FOR_MARKED = builder
                .comment("Hide Xaero's own radar icon for units that already have a marker (and their crews).")
                .define("minimapHideRadarForMarked", true);
        builder.pop();

        builder.push("compat");
        GEMRENDER_PARTS_PALETTE_LIFT = builder
                .comment("Coltan + GemRender only. Let rigid-parts models import with more than 256 bones.",
                        "GemRender rejects them although the parts path never stores a palette index, so tracked",
                        "hulls (T-90A, Bradley, M1A2, BMP-2 and others) fall back to SBW's slower renderer.",
                        "Read when a model loads; reload resources after changing it.")
                .define("gemrenderPartsPaletteLift", true);
        DOG_TAG_ICON_CACHE = builder
                .comment("Reuse each vehicle's dog tag icon texture until its pattern changes, instead of",
                        "rebuilding and uploading it every frame.")
                .define("dogTagIconCache", true);
        VEHICLE_REST_SKIP = builder
                .comment("Skip client-side collision for vehicles you are not riding while they are parked.",
                        "The server stays authoritative; position still follows its updates.")
                .define("vehicleRestSkip", true);
        VEHICLE_FAR_SIM_DISTANCE = builder
                .comment("Beyond this many blocks, vehicles you are not riding skip client-side collision,",
                        "follow server updates more tightly and refresh their slope tilt less often. 0 = off.")
                .defineInRange("vehicleFarSimDistance", 48, 0, 1024);
        VEHICLE_TURN_SOUND_DISTANCE = builder
                .comment("Do not start turret traverse sounds for vehicles farther than this many blocks. 0 = off.")
                .defineInRange("vehicleTurnSoundDistance", 48, 0, 1024);
        SEM_UNIT_FRUSTUM_CULL = builder
                .comment("Skip drawing SEM units that are outside the view. SEM draws every unit inside its",
                        "render distance, including the ones behind the camera.")
                .define("semUnitFrustumCull", true);
        TACZ_THIRD_PERSON_FLUSH_SKIP = builder
                .comment("Without a shader pack, let third-person TaCZ guns without a scope join the normal",
                        "entity batch instead of forcing a flush per gun and per attachment.")
                .define("taczThirdPersonFlushSkip", true);
        TACZ_FAST_CUBE_EMIT = builder
                .comment("Write TaCZ gun and attachment cubes without per-vertex allocations. Same vertices.")
                .define("taczFastCubeEmit", true);
        SEM_ATTACHMENT_LOD = builder
                .comment("Skip attachment models (scopes, grips, muzzles) on third-person guns beyond",
                        "semAttachmentLodDistance. The gun itself is always drawn.")
                .define("semAttachmentLod", true);
        SEM_ATTACHMENT_LOD_DISTANCE = builder
                .comment("Distance in blocks for semAttachmentLod. 0 = off.")
                .defineInRange("semAttachmentLodDistance", 48, 0, 1024);
        SEM_ARMOR_LOD = builder
                .comment("Skip Bedrock armor models on units beyond semArmorLodDistance.")
                .define("semArmorLod", false);
        SEM_ARMOR_LOD_DISTANCE = builder
                .comment("Distance in blocks for semArmorLod. 0 = off.")
                .defineInRange("semArmorLodDistance", 64, 0, 1024);
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

    /** Whether markers are drawn on the minimap right now: the master switch and the minimap's own. */
    public static boolean minimapMarkersEnabled() {
        return mapMarkersEnabled() && MINIMAP_MARKERS_ENABLED.get();
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
