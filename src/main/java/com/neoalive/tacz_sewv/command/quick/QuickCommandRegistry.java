package com.neoalive.tacz_sewv.command.quick;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.client.radial.WedgeEntry;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

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
    public static final String ID_QUICK_ATTACK = "quick_attack";
    public static final String ID_QUICK_CAPTURE_MEDIC = "quick_capture_medic";
    public static final String ID_QUICK_ROUTE_FOB = "quick_route_fob";
    public static final String ID_FORM_SEM_WEDGE = "form_sem_wedge";
    public static final String ID_FORM_SEM_COLUMN = "form_sem_column";
    public static final String ID_FORM_WEDGE = "form_wedge";
    public static final String ID_FORM_COLUMN = "form_column";
    public static final String ID_FORM_LINE = "form_line";
    public static final String ID_FORM_ECHELON_LEFT = "form_echelon_left";
    public static final String ID_FORM_ECHELON_RIGHT = "form_echelon_right";

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
        register(ID_QUICK_DISMOUNT, new QuickDismountPipeline());
        register(ID_QUICK_ATTACK, (issuer, ctx) -> {}); // client-armed; server no-op
        register(ID_QUICK_CAPTURE_MEDIC, new QuickCaptureMedicPipeline());
        register(ID_QUICK_ROUTE_FOB, new QuickRouteFobPipeline());
        register(ID_FORM_SEM_WEDGE, new QuickSemOrderPipeline(OrderType.FORM_WEDGE,
                "message.tacz_sewv.quick_form.started"));
        register(ID_FORM_SEM_COLUMN, new QuickSemOrderPipeline(OrderType.FORM_COLUMN,
                "message.tacz_sewv.quick_form.started"));
        register(ID_FORM_WEDGE, new QuickFormationPipeline(FormationShape.WEDGE));
        register(ID_FORM_COLUMN, new QuickFormationPipeline(FormationShape.COLUMN));
        register(ID_FORM_LINE, new QuickFormationPipeline(FormationShape.LINE));
        register(ID_FORM_ECHELON_LEFT, new QuickFormationPipeline(FormationShape.ECHELON_LEFT));
        register(ID_FORM_ECHELON_RIGHT, new QuickFormationPipeline(FormationShape.ECHELON_RIGHT));

        ROOT = List.of(
                new WedgeEntry.CategoryEntry("General", "\u2605", 0xE8B84A, List.of(
                        leaf("Quick Board", "\u2399", ID_QUICK_BOARD, 0xE8B84A),
                        leaf("Board Queued", "\u21A7", ID_BOARD_QUEUED, 0xE8B84A),
                        leaf("Quick Follow", "\u21AA", ID_QUICK_FOLLOW, 0xE8B84A),
                        leaf("Quick Dismount", "\u2193", ID_QUICK_DISMOUNT, 0xE8B84A),
                        leaf("Quick Hold", "\u25A1", ID_QUICK_HOLD, 0xE8B84A),
                        leaf("Attack That", "\u2694", ID_QUICK_ATTACK, 0xE8B84A))),
                new WedgeEntry.CategoryEntry("Land", "\u26F0", 0x6BA84A, List.of(
                        leaf("Quick Entrench", "\u26F0", ID_QUICK_ENTRENCH, 0x6BA84A),
                        leaf("Quick Refill", "\u21BB", ID_QUICK_REFILL, 0x6BA84A),
                        leaf("Capture Medic", "\u271A", ID_QUICK_CAPTURE_MEDIC, 0x6BA84A),
                        leaf("Route to FOB", "\u2302", ID_QUICK_ROUTE_FOB, 0x6BA84A))),
                new WedgeEntry.CategoryEntry("Air", "\u2708", 0x4AB8E8, List.of(
                        leaf("Quick Takeoff", "\u2191", ID_QUICK_TAKEOFF, 0x4AB8E8),
                        leaf("Quick Landing", "\u2193", ID_QUICK_LANDING, 0x4AB8E8),
                        leaf("Emergency Land", "\u26A0", ID_QUICK_EMERGENCY_LAND, 0x4AB8E8),
                        leaf("Rappel Crew", "\u21CA", ID_RAPPEL_CREW, 0x4AB8E8),
                        leaf("Rappel Self", "\u21F3", ID_RAPPEL_SELF, 0x4AB8E8))),
                new WedgeEntry.CategoryEntry("Procedures", "\u2699", 0x5AB8A0, List.of(
                        leaf("Quick Evacuation", "\u21E7", ID_QUICK_EVAC, 0x5AB8A0),
                        leaf("Search & Destroy", "\u25CE", ID_QUICK_SEARCH, 0x5AB8A0),
                        leaf("Patrol", "\u27F3", ID_QUICK_PATROL, 0x5AB8A0))),
                new WedgeEntry.CategoryEntry("Formation", "\u25C8", 0xA06BD4, List.of(
                        leaf("SEM Wedge", "\u25B2", ID_FORM_SEM_WEDGE, 0xA06BD4),
                        leaf("SEM Column", "\u25B3", ID_FORM_SEM_COLUMN, 0xA06BD4),
                        leaf("Wedge", "\u25B2", ID_FORM_WEDGE, 0xA06BD4),
                        leaf("Column", "\u25B3", ID_FORM_COLUMN, 0xA06BD4),
                        leaf("Line", "\u2501", ID_FORM_LINE, 0xA06BD4),
                        leaf("Echelon L", "\u25E2", ID_FORM_ECHELON_LEFT, 0xA06BD4),
                        leaf("Echelon R", "\u25E3", ID_FORM_ECHELON_RIGHT, 0xA06BD4))),
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

    /** True when the pipeline needs on-foot PMCs only (board / mortar / refill walks). */
    public static boolean requiresOnFoot(String pipelineId) {
        return ID_QUICK_BOARD.equals(pipelineId)
                || ID_QUICK_ENTRENCH.equals(pipelineId)
                || ID_QUICK_REFILL.equals(pipelineId)
                || ID_QUICK_EVAC.equals(pipelineId);
    }
}
