package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.client.radial.WedgeEntry;
import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Static registry of quick-command pipelines and the root wedge tree the wheel opens on.
 * Adding a pipeline later means a new implementing class + a line here — not input/render edits.
 */
public final class QuickCommandRegistry {

    public static final String ID_QUICK_EVAC = "quick_evac";
    public static final String ID_QUICK_PATROL = "quick_patrol";
    public static final String ID_QUICK_SEARCH = "quick_search";
    public static final String ID_QUICK_TAKEOFF = "quick_takeoff";
    public static final String ID_QUICK_LANDING = "quick_landing";
    public static final String ID_QUICK_EMERGENCY_LAND = "quick_emergency_land";
    public static final String ID_QUICK_LAND_HELIPAD = "quick_land_helipad";
    public static final String ID_RAPPEL_SELF = "rappel_self";
    public static final String ID_RAPPEL_CREW = "rappel_crew";
    public static final String ID_QUICK_BOARD = "quick_board";
    public static final String ID_BOARD_QUEUED = "board_queued";
    public static final String ID_QUICK_ENTRENCH = "quick_entrench";
    public static final String ID_QUICK_REFILL = "quick_refill";
    public static final String ID_QUICK_CANCEL = "quick_cancel";
    public static final String ID_QUICK_FOLLOW = "quick_follow";
    public static final String ID_QUICK_DISMOUNT = "quick_dismount";
    public static final String ID_QUICK_HOLD = "quick_hold";
    public static final String ID_QUICK_CEASE_FIRE = "quick_cease_fire";
    public static final String ID_QUICK_FREE_FIRE = "quick_free_fire";
    public static final String ID_QUICK_ATTACK = "quick_attack";
    public static final String ID_QUICK_MOVE = "quick_move";
    public static final String ID_PLATOON_JOIN = "platoon_join";
    public static final String ID_PLATOON_EXIT = "platoon_exit";
    public static final String ID_PLATOON_AUTO_ORDERS = "platoon_auto_orders";
    public static final String ID_QUICK_ROUTE_FOB = "quick_route_fob";
    public static final String ID_REVIVE_CALL = "revive_call";
    public static final String ID_FORM_WEDGE = "form_wedge";
    public static final String ID_FORM_COLUMN = "form_column";
    public static final String ID_FORM_LINE = "form_line";
    public static final String ID_FORM_ECHELON_LEFT = "form_echelon_left";
    public static final String ID_FORM_ECHELON_RIGHT = "form_echelon_right";
    public static final String ID_SUPPORT_CARPET_BOMB = "support_carpet_bomb";
    public static final String ID_SUPPORT_AGM = "support_agm";
    public static final String ID_SUPPORT_CANNON = "support_cannon";
    public static final String ID_SUPPORT_ARTILLERY = "support_artillery";
    public static final String ID_SUPPORT_MORTAR = "support_mortar";

    /** Handheld-radio fire missions. Signal orange; every aircraft order shares the airplane glyph. */
    public static final int SUPPORT_ACCENT = 0xF08A24;
    private static final String AIR_GLYPH = "\u2708";
    public static final WedgeEntry.CategoryEntry SUPPORT_CATEGORY = new WedgeEntry.CategoryEntry(
            "Support", "\u260E", SUPPORT_ACCENT, List.of(
                    new WedgeEntry.PipelineEntry("Carpet Bomb", AIR_GLYPH, ID_SUPPORT_CARPET_BOMB, SUPPORT_ACCENT),
                    new WedgeEntry.PipelineEntry("Anti Ground Missile", AIR_GLYPH, ID_SUPPORT_AGM, SUPPORT_ACCENT),
                    new WedgeEntry.PipelineEntry("Cannon", AIR_GLYPH, ID_SUPPORT_CANNON, SUPPORT_ACCENT),
                    new WedgeEntry.PipelineEntry("Artillery Fire", "\u2739", ID_SUPPORT_ARTILLERY, SUPPORT_ACCENT),
                    new WedgeEntry.PipelineEntry("Mortar Shelling", "\u2604", ID_SUPPORT_MORTAR, SUPPORT_ACCENT)));

    private static final Map<String, QuickCommandPipeline> BY_ID = new HashMap<>();
    private static List<WedgeEntry> ROOT = List.of();

    private QuickCommandRegistry() {}

    /** Call once from mod init (server + client both need the root wedge tree for the wheel). */
    public static void init() {
        BY_ID.clear();
        register(ID_QUICK_EVAC, new QuickEvacPipeline());
        register(ID_QUICK_BOARD, new QuickBoardPipeline());
        register(ID_QUICK_ENTRENCH, new QuickEntrenchPipeline());
        register(ID_QUICK_REFILL, new QuickRefillPipeline());
        register(ID_QUICK_CANCEL, new QuickCancelPipeline());
        register(ID_QUICK_FOLLOW, new QuickSemOrderPipeline(OrderType.FOLLOW_COMMANDER,
                "message.tacz_sewv.quick_follow.started"));
        register(ID_QUICK_HOLD, new QuickSemOrderPipeline(OrderType.HOLD_POSITION,
                "message.tacz_sewv.quick_hold.started"));
        register(ID_QUICK_CEASE_FIRE, new QuickSemOrderPipeline(OrderType.CEASE_FIRE,
                "message.tacz_sewv.quick_cease_fire.started"));
        register(ID_QUICK_FREE_FIRE, new QuickSemOrderPipeline(OrderType.FREE_FIRE,
                "message.tacz_sewv.quick_free_fire.started"));
        register(ID_QUICK_DISMOUNT, new QuickDismountPipeline());
        register(ID_QUICK_ATTACK, (issuer, ctx) -> {}); // client-armed; server no-op
        register(ID_QUICK_ROUTE_FOB, new QuickRouteFobPipeline());
        register(ID_REVIVE_CALL, new QuickReviveCallPipeline());
        // Formation leaves are client-committed via PacketVehicleFormation (not PacketQuickCommand).

        List<WedgeEntry> general = new ArrayList<>();
        general.add(leaf("Quick Board", "\u2399", ID_QUICK_BOARD, 0xE8B84A));
        general.add(leaf("Board Queued", "\u21A7", ID_BOARD_QUEUED, 0xE8B84A));
        general.add(leaf("Quick Follow", "\u21AA", ID_QUICK_FOLLOW, 0xE8B84A));
        general.add(leaf("Quick Dismount", "\u2193", ID_QUICK_DISMOUNT, 0xE8B84A));
        general.add(leaf("Quick Hold", "\u25A1", ID_QUICK_HOLD, 0xE8B84A));
        general.add(leaf("Move To", "\u27A4", ID_QUICK_MOVE, 0xE8B84A));
        general.add(leaf("Attack That", "\u2694", ID_QUICK_ATTACK, 0xE8B84A));
        general.add(leaf("Cease Fire", "\u2298", ID_QUICK_CEASE_FIRE, 0xE8B84A));
        general.add(leaf("Free Fire", "\u26A1", ID_QUICK_FREE_FIRE, 0xE8B84A));
        if (PlayerReviveCompat.isLoaded()) {
            general.add(leaf("Revive Call", "\u271A", ID_REVIVE_CALL, 0xE8B84A));
        }

        ROOT = List.of(
                new WedgeEntry.CategoryEntry("General", "\u2605", 0xE8B84A, List.copyOf(general)),
                new WedgeEntry.CategoryEntry("Land", "\u26F0", 0x6BA84A, List.of(
                        leaf("Quick Entrench", "\u26F0", ID_QUICK_ENTRENCH, 0x6BA84A),
                        leaf("Quick Refill", "\u21BB", ID_QUICK_REFILL, 0x6BA84A),
                        leaf("Route to FOB", "\u2302", ID_QUICK_ROUTE_FOB, 0x6BA84A))),
                new WedgeEntry.CategoryEntry("Air", "\u2708", 0x4AB8E8, List.of(
                        leaf("Quick Takeoff", "\u2191", ID_QUICK_TAKEOFF, 0x4AB8E8),
                        leaf("Quick Landing", "\u2193", ID_QUICK_LANDING, 0x4AB8E8),
                        leaf("Land at Helipad", "▣", ID_QUICK_LAND_HELIPAD, 0x4AB8E8),
                        leaf("Emergency Land", "\u26A0", ID_QUICK_EMERGENCY_LAND, 0x4AB8E8),
                        leaf("Rappel Crew", "\u21CA", ID_RAPPEL_CREW, 0x4AB8E8),
                        leaf("Rappel Self", "\u21F3", ID_RAPPEL_SELF, 0x4AB8E8))),
                new WedgeEntry.CategoryEntry("Procedures", "\u2699", 0x5AB8A0, List.of(
                        leaf("Quick Evacuation", "\u21E7", ID_QUICK_EVAC, 0x5AB8A0),
                        leaf("Search & Destroy", "\u25CE", ID_QUICK_SEARCH, 0x5AB8A0),
                        leaf("Patrol", "\u27F3", ID_QUICK_PATROL, 0x5AB8A0))),
                new WedgeEntry.CategoryEntry("Formation", "\u25C8", 0xA06BD4, List.of(
                        leaf("Wedge", "\u25B2", ID_FORM_WEDGE, 0xA06BD4),
                        leaf("Column", "\u25B3", ID_FORM_COLUMN, 0xA06BD4),
                        leaf("Line", "\u2501", ID_FORM_LINE, 0xA06BD4),
                        leaf("Echelon L", "\u25E2", ID_FORM_ECHELON_LEFT, 0xA06BD4),
                        leaf("Echelon R", "\u25E3", ID_FORM_ECHELON_RIGHT, 0xA06BD4))),
                new WedgeEntry.CategoryEntry("Platoon", "\u2691", 0xE07AA6, List.of(
                        leaf("Join Platoon", "\u2295", ID_PLATOON_JOIN, 0xE07AA6),
                        leaf("Exit Platoon", "\u2296", ID_PLATOON_EXIT, 0xE07AA6),
                        leaf("Toggle Orders", "\u21C4", ID_PLATOON_AUTO_ORDERS, 0xE07AA6))),
                SUPPORT_CATEGORY,
                leaf("Cancel", "\u2715", ID_QUICK_CANCEL, 0xE85A4A));
    }

    private static WedgeEntry.PipelineEntry leaf(String label, String icon, String id, int accent) {
        return new WedgeEntry.PipelineEntry(label, icon, id, accent);
    }

    private static void register(String id, QuickCommandPipeline pipeline) {
        BY_ID.put(id, pipeline);
    }

    @Nullable
    public static QuickCommandPipeline get(String id) {
        return BY_ID.get(id);
    }

    public static List<WedgeEntry> rootWedges() {
        return ROOT;
    }

    /**
     * The radio call a Support leaf makes, or null for any other id. Client-committed via
     * {@code PacketRadioCommand} (not PacketQuickCommand); only the mortar takes the delay.
     */
    @Nullable
    public static RadioSettings.State supportSettings(String pipelineId, int delaySeconds) {
        return switch (pipelineId) {
            case ID_SUPPORT_CARPET_BOMB -> new RadioSettings.State(RadioFrequency.AIR, 0, PlaneAttackMode.BOMB);
            case ID_SUPPORT_AGM -> new RadioSettings.State(RadioFrequency.AIR, 0, PlaneAttackMode.GUIDED);
            case ID_SUPPORT_CANNON -> new RadioSettings.State(RadioFrequency.AIR, 0, PlaneAttackMode.CANNON);
            case ID_SUPPORT_ARTILLERY -> new RadioSettings.State(RadioFrequency.ARTILLERY, 0, PlaneAttackMode.AUTO);
            case ID_SUPPORT_MORTAR -> new RadioSettings.State(RadioFrequency.MORTAR, delaySeconds, PlaneAttackMode.AUTO);
            default -> null;
        };
    }

    /** True when the pipeline needs on-foot PMCs only (board / mortar / refill walks). */
    public static boolean requiresOnFoot(String pipelineId) {
        return ID_QUICK_BOARD.equals(pipelineId)
                || ID_QUICK_ENTRENCH.equals(pipelineId)
                || ID_QUICK_REFILL.equals(pipelineId)
                || ID_QUICK_EVAC.equals(pipelineId);
    }

    /** True when the leaf is a Combined Arms formation shape (client PacketVehicleFormation path). */
    public static boolean isFormationPipeline(String pipelineId) {
        return ID_FORM_WEDGE.equals(pipelineId)
                || ID_FORM_COLUMN.equals(pipelineId)
                || ID_FORM_LINE.equals(pipelineId)
                || ID_FORM_ECHELON_LEFT.equals(pipelineId)
                || ID_FORM_ECHELON_RIGHT.equals(pipelineId);
    }

    @Nullable
    public static FormationShape formationShapeOf(String pipelineId) {
        return switch (pipelineId) {
            case ID_FORM_WEDGE -> FormationShape.WEDGE;
            case ID_FORM_COLUMN -> FormationShape.COLUMN;
            case ID_FORM_LINE -> FormationShape.LINE;
            case ID_FORM_ECHELON_LEFT -> FormationShape.ECHELON_LEFT;
            case ID_FORM_ECHELON_RIGHT -> FormationShape.ECHELON_RIGHT;
            default -> null;
        };
    }
}
