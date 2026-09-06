package com.neoalive.tacz_sewv.command.quick;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.client.radial.WedgeEntry;

/**
 * Static registry of quick-command pipelines and the root wedge tree the wheel opens on.
 * Adding a pipeline later means a new implementing class + a line here — not input/render edits.
 */
public final class QuickCommandRegistry {

    public static final String ID_QUICK_EVAC = "quick_evac";
    public static final String ID_QUICK_BOARD = "quick_board";
    public static final String ID_QUICK_ENTRENCH = "quick_entrench";
    public static final String ID_QUICK_REFILL = "quick_refill";

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

        ROOT = List.of(
                new WedgeEntry.CategoryEntry("Land", "\u2694", List.of(
                        new WedgeEntry.PipelineEntry("Quick Board", "\u2399", ID_QUICK_BOARD),
                        new WedgeEntry.PipelineEntry("Quick Entrench", "\u26F0", ID_QUICK_ENTRENCH),
                        new WedgeEntry.PipelineEntry("Quick Refill", "\u21BB", ID_QUICK_REFILL))),
                new WedgeEntry.CategoryEntry("Air", "\u2708", List.of(
                        new WedgeEntry.PipelineEntry("Quick Evac", "\u21E7", ID_QUICK_EVAC))),
                new WedgeEntry.CategoryEntry("Sea", "\u2693", List.of()));
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
}
