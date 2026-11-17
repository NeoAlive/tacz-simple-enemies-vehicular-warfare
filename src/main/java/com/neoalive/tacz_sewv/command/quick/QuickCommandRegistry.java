package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.client.radial.WedgeEntry;
import com.neoalive.tacz_sewv.command.quick.scavenge.QuickScavengePipeline;
import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldOrders;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.fpas.RadarContact;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.PlaneRole;
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
    public static final String ID_QUICK_SCAVENGE = "quick_scavenge";
    /** Old id of the order Scavenge replaced; kept so a queued entry still resolves. */
    public static final String ID_QUICK_REFILL = "quick_refill";
    /** Wheel-only leaves; the role picked with Ctrl+scroll turns them into a per-kit id. */
    public static final String ID_QUICK_SEARCH_GEAR = "quick_search_gear";
    public static final String ID_QUICK_SWAP_ROLE = "quick_swap_role";
    public static final List<RoleKit.Kit> SEARCH_KITS =
            List.of(RoleKit.Kit.MEDIC, RoleKit.Kit.ENGINEER, RoleKit.Kit.COMBAT_ENGINEER);
    public static final List<RoleKit.Kit> SWAP_KITS = List.of(RoleKit.Kit.values());
    /** Wheel-only leaf; Ctrl+scroll picks whether the unit also crews it ({@link #deployEquipmentId}). */
    public static final String ID_QUICK_DEPLOY_EQUIPMENT = "quick_deploy_equipment";
    /** Wheel-only leaf; the mine and shape picked on the wheel ride the id ({@code QuickMinefieldPipeline#id}). */
    public static final String ID_QUICK_MINEFIELD = "quick_minefield";
    /** Wheel-only leaf; the role picked with a scroll turns it into {@link #planeRoleId}. */
    public static final String ID_PLANE_ROLE = "quick_plane_role";
    public static final String ID_QUICK_CANCEL = "quick_cancel";
    public static final String ID_QUICK_FOLLOW = "quick_follow";
    public static final String ID_QUICK_DISMOUNT = "quick_dismount";
    public static final String ID_QUICK_HOLD = "quick_hold";
    public static final String ID_QUICK_CEASE_FIRE = "quick_cease_fire";
    public static final String ID_QUICK_FREE_FIRE = "quick_free_fire";
    public static final String ID_QUICK_ATTACK = "quick_attack";
    public static final String ID_QUICK_MOVE = "quick_move";
    public static final String ID_QUICK_SUPPRESS = "quick_suppress";
    /** Wheel-only leaf; the hold time picked with Ctrl+scroll turns it into a per-value id. */
    public static final String ID_QUICK_COVER_HOLD = "quick_cover_hold";
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
    // Ported from the old TDT order grid; all client-routed onto their existing packets.
    public static final String ID_BOARD_PASSENGER = "board_passenger";
    public static final String ID_BAIL_OUT = "bail_out";
    public static final String ID_TOW = "tow";
    public static final String ID_ESCORT = "escort";
    public static final String ID_SET_GUARD = "set_guard";
    public static final String ID_REACH_GUARD = "reach_guard";
    public static final String ID_PARATROOP = "paratroop";
    public static final String ID_DISMISS = "dismiss";
    /** Select-category leaves: {@code select_kind:<VehicleMarker.Kind>} / {@code select_platoon:<rgb>}. */
    public static final String SELECT_KIND_PREFIX = "select_kind:";
    public static final String SELECT_PLATOON_PREFIX = "select_platoon:";
    public static final int SELECT_ACCENT = 0x9AA7B4;

    /** The Support category itself. Its leaves carry their subcategory's colour instead. */
    public static final int SUPPORT_ACCENT = 0xF08A24;
    public static final int SUPPORT_AIR_ACCENT = 0x6EC6F0;
    public static final int SUPPORT_ARTILLERY_ACCENT = 0xE0503C;
    public static final int SUPPORT_MORTAR_ACCENT = 0xF08A24;
    public static final int SUPPORT_SIGNAL_ACCENT = 0x5CCB6A;
    /** FPAS Signal leaves: {@code signal_attack_<class>}, one per radar contact class. */
    public static final String SIGNAL_PREFIX = "signal_attack_";
    /**
     * Language-key prefix for every wedge label ({@code gui.tacz_sewv.wheel.<id>}). The queue
     * packet stores the key itself ({@code readUtf(64)}), so a key must stay within 64 characters.
     */
    public static final String LABEL_PREFIX = "gui.tacz_sewv.wheel.";
    private static final String AIR_GLYPH = "\u2708";
    /** Radio leaves, grouped by subcategory; the wheel drops them without a radio. */
    public static final List<WedgeEntry> RADIO_LEAVES = List.of(
            leaf(ID_SUPPORT_CARPET_BOMB, AIR_GLYPH, SUPPORT_AIR_ACCENT),
            leaf(ID_SUPPORT_AGM, AIR_GLYPH, SUPPORT_AIR_ACCENT),
            leaf(ID_SUPPORT_CANNON, AIR_GLYPH, SUPPORT_AIR_ACCENT),
            leaf(ID_SUPPORT_ARTILLERY, "\u2739", SUPPORT_ARTILLERY_ACCENT),
            leaf(ID_SUPPORT_MORTAR, "\u2604", SUPPORT_MORTAR_ACCENT));
    /** Signal leaves; the wheel drops them without a bound Signal Relay. */
    public static final List<WedgeEntry> SIGNAL_LEAVES = signalLeaves();
    public static final WedgeEntry.CategoryEntry SUPPORT_CATEGORY = category(
            "support", "\u260E", SUPPORT_ACCENT, concat(RADIO_LEAVES, SIGNAL_LEAVES));

    private static final Map<String, QuickCommandPipeline> BY_ID = new HashMap<>();
    private static List<WedgeEntry> ROOT = List.of();

    private QuickCommandRegistry() {}

    /** Call once from mod init (server + client both need the root wedge tree for the wheel). */
    public static void init() {
        BY_ID.clear();
        register(ID_QUICK_EVAC, new QuickEvacPipeline());
        register(ID_QUICK_BOARD, new QuickBoardPipeline());
        register(ID_QUICK_ENTRENCH, new QuickEntrenchPipeline());
        QuickScavengePipeline scavenge = new QuickScavengePipeline();
        register(ID_QUICK_SCAVENGE, scavenge);
        register(ID_QUICK_REFILL, scavenge);
        register(ID_QUICK_CANCEL, new QuickCancelPipeline());
        register(ID_QUICK_FOLLOW, new QuickSemOrderPipeline(OrderType.FOLLOW_COMMANDER));
        register(ID_QUICK_HOLD, new QuickSemOrderPipeline(OrderType.HOLD_POSITION));
        register(ID_QUICK_CEASE_FIRE, new QuickSemOrderPipeline(OrderType.CEASE_FIRE));
        register(ID_QUICK_FREE_FIRE, new QuickSemOrderPipeline(OrderType.FREE_FIRE));
        register(ID_QUICK_DISMOUNT, new QuickDismountPipeline());
        register(ID_QUICK_ATTACK, (issuer, ctx) -> {}); // client-armed; server no-op
        register(ID_QUICK_ROUTE_FOB, new QuickRouteFobPipeline());
        register(ID_REVIVE_CALL, new QuickReviveCallPipeline());
        register(ID_QUICK_SUPPRESS, new QuickSuppressPipeline());
        register(deployEquipmentId(true), new QuickDeployEquipmentPipeline(true));
        register(deployEquipmentId(false), new QuickDeployEquipmentPipeline(false));
        for (RadarContact.Cls cls : RadarContact.Cls.values()) register(signalId(cls), new QuickSignalPipeline(cls));
        for (int s = 0; s <= CoverHoldOrders.MAX_HOLD_SECONDS; s += CoverHoldOrders.HOLD_STEP_SECONDS) {
            register(coverHoldId(s), new QuickCoverHoldPipeline(s));
        }
        for (RoleKit.Kit kit : SEARCH_KITS) register(searchGearId(kit), new QuickSearchGearPipeline(kit));
        for (RoleKit.Kit kit : SWAP_KITS) register(swapRoleId(kit), new QuickSwapRolePipeline(kit));
        for (PlaneRole role : PlaneRole.values()) register(planeRoleId(role), new QuickPlaneRolePipeline(role));
        // Formation leaves are client-committed via PacketVehicleFormation (not PacketQuickCommand).

        List<WedgeEntry> general = new ArrayList<>();
        general.add(leaf(ID_QUICK_FOLLOW, "\u21AA", 0xE8B84A));
        general.add(leaf(ID_QUICK_HOLD, "\u25A1", 0xE8B84A));
        general.add(leaf(ID_QUICK_MOVE, "\u27A4", 0xE8B84A));
        general.add(leaf(ID_QUICK_ATTACK, "\u2694", 0xE8B84A));
        general.add(leaf(ID_QUICK_SUPPRESS, "\u2738", 0xE8B84A));
        general.add(leaf(ID_QUICK_CEASE_FIRE, "\u2298", 0xE8B84A));
        general.add(leaf(ID_QUICK_FREE_FIRE, "\u26A1", 0xE8B84A));
        general.add(leaf(ID_ESCORT, "\u25C6", 0xE8B84A));
        general.add(leaf(ID_SET_GUARD, "\u2691", 0xE8B84A));
        general.add(leaf(ID_REACH_GUARD, "\u2192", 0xE8B84A));
        if (PlayerReviveCompat.isLoaded()) {
            general.add(leaf(ID_REVIVE_CALL, "\u271A", 0xE8B84A));
        }

        ROOT = List.of(
                category("general", "\u2605", 0xE8B84A, List.copyOf(general)),
                category("crew", "\u2399", 0xD98A4A, List.of(
                        leaf(ID_QUICK_BOARD, "\u2399", 0xD98A4A),
                        leaf(ID_BOARD_PASSENGER, "\u25AD", 0xD98A4A),
                        leaf(ID_BOARD_QUEUED, "\u21A7", 0xD98A4A),
                        leaf(ID_QUICK_DISMOUNT, "\u2193", 0xD98A4A),
                        leaf(ID_BAIL_OUT, "\u2602", 0xD98A4A),
                        leaf(ID_TOW, "\u26D3", 0xD98A4A))),
                category("land", "\u26F0", 0x6BA84A, List.of(
                        leaf(ID_QUICK_ENTRENCH, "\u26F0", 0x6BA84A),
                        leaf(ID_QUICK_SCAVENGE, "\u21BB", 0x6BA84A),
                        leaf(ID_QUICK_ROUTE_FOB, "\u2302", 0x6BA84A),
                        leaf(ID_QUICK_SEARCH_GEAR, "\u2315", 0x6BA84A),
                        leaf(ID_QUICK_SWAP_ROLE, "\u21C6", 0x6BA84A),
                        leaf(ID_QUICK_DEPLOY_EQUIPMENT, "\u2692", 0x6BA84A),
                        leaf(ID_QUICK_MINEFIELD, "\u2622", 0x6BA84A))),
                category("air", "\u2708", 0x4AB8E8, List.of(
                        leaf(ID_QUICK_TAKEOFF, "\u2191", 0x4AB8E8),
                        leaf(ID_QUICK_LANDING, "\u2193", 0x4AB8E8),
                        leaf(ID_QUICK_LAND_HELIPAD, "\u25A3", 0x4AB8E8),
                        leaf(ID_QUICK_EMERGENCY_LAND, "\u26A0", 0x4AB8E8),
                        leaf(ID_RAPPEL_CREW, "\u21CA", 0x4AB8E8),
                        leaf(ID_RAPPEL_SELF, "\u21F3", 0x4AB8E8),
                        leaf(ID_PARATROOP, "\u2602", 0x4AB8E8),
                        leaf(ID_PLANE_ROLE, "\u2316", 0x4AB8E8))),
                category("procedures", "\u2699", 0x5AB8A0, List.of(
                        leaf(ID_QUICK_EVAC, "\u21E7", 0x5AB8A0),
                        leaf(ID_QUICK_SEARCH, "\u25CE", 0x5AB8A0),
                        leaf(ID_QUICK_PATROL, "\u27F3", 0x5AB8A0),
                        leaf(ID_QUICK_COVER_HOLD, "\u26E8", 0x5AB8A0),
                        leaf(ID_DISMISS, "\u2715", 0x5AB8A0))),
                category("formation", "\u25C8", 0xA06BD4, List.of(
                        leaf(ID_FORM_WEDGE, "\u25B2", 0xA06BD4),
                        leaf(ID_FORM_COLUMN, "\u25B3", 0xA06BD4),
                        leaf(ID_FORM_LINE, "\u2501", 0xA06BD4),
                        leaf(ID_FORM_ECHELON_LEFT, "\u25E2", 0xA06BD4),
                        leaf(ID_FORM_ECHELON_RIGHT, "\u25E3", 0xA06BD4))),
                category("platoon", "\u2691", 0xE07AA6, List.of(
                        leaf(ID_PLATOON_JOIN, "\u2295", 0xE07AA6),
                        leaf(ID_PLATOON_EXIT, "\u2296", 0xE07AA6),
                        leaf(ID_PLATOON_AUTO_ORDERS, "\u21C4", 0xE07AA6))),
                SUPPORT_CATEGORY,
                leaf(ID_QUICK_CANCEL, "\u2715", 0xE85A4A));
    }

    /** {@code gui.tacz_sewv.wheel.<id>} — the English string is the lang file, not this class. */
    public static String labelKey(String id) {
        return LABEL_PREFIX + id;
    }

    public static String signalId(RadarContact.Cls cls) {
        return SIGNAL_PREFIX + cls.key();
    }

    public static boolean isSignalLeaf(String pipelineId) {
        return pipelineId.startsWith(SIGNAL_PREFIX);
    }

    private static List<WedgeEntry> signalLeaves() {
        List<WedgeEntry> out = new ArrayList<>();
        for (RadarContact.Cls cls : RadarContact.Cls.values()) {
            String glyph = switch (cls) {
                case GROUND -> "▣";
                case ROTOR -> "❁";
                case PLANE -> AIR_GLYPH;
                case SHIP -> "⚓";
                case INFANTRY -> "♟";
            };
            out.add(leaf(signalId(cls), glyph, SUPPORT_SIGNAL_ACCENT));
        }
        return List.copyOf(out);
    }

    private static List<WedgeEntry> concat(List<WedgeEntry> a, List<WedgeEntry> b) {
        List<WedgeEntry> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private static WedgeEntry.PipelineEntry leaf(String id, String icon, int accent) {
        return new WedgeEntry.PipelineEntry(labelKey(id), icon, id, accent);
    }

    private static WedgeEntry.CategoryEntry category(String id, String icon, int accent, List<WedgeEntry> children) {
        return new WedgeEntry.CategoryEntry(labelKey("cat." + id), icon, accent, children);
    }

    private static void register(String id, QuickCommandPipeline pipeline) {
        BY_ID.put(id, pipeline);
    }

    @Nullable
    public static QuickCommandPipeline get(String id) {
        QuickCommandPipeline p = BY_ID.get(id);
        return p != null ? p : QuickMinefieldPipeline.parse(id);
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

    /** True for a Select-category leaf (client-only selection toggle, never an order). */
    public static boolean isSelectLeaf(String pipelineId) {
        return pipelineId.startsWith(SELECT_KIND_PREFIX) || pipelineId.startsWith(SELECT_PLATOON_PREFIX);
    }

    public static String searchGearId(RoleKit.Kit kit) {
        return ID_QUICK_SEARCH_GEAR + "_" + kit.key();
    }

    public static String swapRoleId(RoleKit.Kit kit) {
        return ID_QUICK_SWAP_ROLE + "_" + kit.key();
    }

    /** The registered id for a Cover and Hold of {@code seconds} (0 = until the next order). */
    public static String coverHoldId(int seconds) {
        return ID_QUICK_COVER_HOLD + "_" + seconds;
    }

    /** The registered id for Deploy Equipment, crewed by the placing unit or just set down. */
    public static String deployEquipmentId(boolean man) {
        return ID_QUICK_DEPLOY_EQUIPMENT + (man ? "_man" : "_place");
    }

    public static boolean isDeployEquipment(String pipelineId) {
        return pipelineId.startsWith(ID_QUICK_DEPLOY_EQUIPMENT);
    }

    public static String planeRoleId(PlaneRole role) {
        return ID_PLANE_ROLE + "_" + role.key();
    }

    public static boolean isPlaneRole(String pipelineId) {
        return pipelineId.startsWith(ID_PLANE_ROLE);
    }

    public static boolean isMinefield(String pipelineId) {
        return pipelineId.startsWith(ID_QUICK_MINEFIELD);
    }

    public static boolean isSearchGear(String pipelineId) {
        return pipelineId.startsWith(ID_QUICK_SEARCH_GEAR);
    }

    public static boolean isSwapRole(String pipelineId) {
        return pipelineId.startsWith(ID_QUICK_SWAP_ROLE);
    }

    /** True when the pipeline needs on-foot PMCs only (board / mortar / refill walks). */
    public static boolean requiresOnFoot(String pipelineId) {
        return isSearchGear(pipelineId)
                || isSwapRole(pipelineId)
                || isDeployEquipment(pipelineId)
                || isMinefield(pipelineId)
                || ID_QUICK_BOARD.equals(pipelineId)
                || ID_QUICK_ENTRENCH.equals(pipelineId)
                || ID_QUICK_SCAVENGE.equals(pipelineId)
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
