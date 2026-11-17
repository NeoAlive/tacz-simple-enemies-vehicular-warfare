package com.neoalive.tacz_sewv.client.map;

import java.util.List;

import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.map.MarkerOrder;

/** Headless checks for map waypoint handles: dedupe, arrival hiding, batch drag, send gate, shift. */
public final class WaypointDragSelfCheck {

    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");

        BlockPos p = new BlockPos(10, 64, 10);
        BlockPos q = new BlockPos(30, 64, 10);
        List<WaypointHandles.Entry> line = List.of(
                new WaypointHandles.Entry(1, MarkerOrder.move(p, 7L, false), false),
                new WaypointHandles.Entry(2, MarkerOrder.move(p, 7L, false), false),
                new WaypointHandles.Entry(3, MarkerOrder.move(q, 7L, false), false),
                new WaypointHandles.Entry(4, MarkerOrder.move(p, 9L, false), false));
        List<WaypointHandles.Handle> hs = WaypointHandles.list(line);
        assert hs.size() == 3 : "one handle per batch point, got " + hs.size();
        WaypointHandles.Handle first = hs.get(0);
        assert first.onPoint().equals(List.of(1, 2)) : "shared point keeps both units";

        List<Integer> batch = WaypointHandles.dragUnits(line, first, false);
        assert batch.equals(List.of(1, 2, 3)) : "batch drag takes every unit of the batch, anchor first: " + batch;
        assert WaypointHandles.dragUnits(line, first, true).equals(List.of(1, 2)) : "detach takes only the point";

        List<WaypointHandles.Entry> arrived = List.of(
                new WaypointHandles.Entry(1, MarkerOrder.move(p, 7L, true), false),
                new WaypointHandles.Entry(2, MarkerOrder.move(p, 7L, false), false));
        assert WaypointHandles.list(arrived).isEmpty() : "an arrived point offers no handle";

        MarkerOrder cruise = MarkerOrder.cruise(List.of(p, q, new BlockPos(20, 64, 40)), 5L);
        assert WaypointHandles.list(List.of(new WaypointHandles.Entry(1, cruise, false))).isEmpty()
                : "cruise nodes need a selected crew";
        assert WaypointHandles.list(List.of(new WaypointHandles.Entry(1, cruise, true))).size() == 3
                : "one handle per cruise node";

        assert !WaypointHandles.shouldSend(1000, 900, p, q) : "too soon";
        assert !WaypointHandles.shouldSend(2000, 900, p, p.east()) : "too close";
        assert WaypointHandles.shouldSend(2000, 900, p, q) : "due and far enough";

        MarkerOrder moved = WaypointHandles.shift(MarkerOrder.move(q, 7L, false), -1, 5, -3);
        assert moved.target().equals(new BlockPos(35, 64, 7)) : "shift keeps the offset";
        MarkerOrder node = WaypointHandles.shift(cruise, 1, 1, 1);
        assert node.route().get(1).equals(new BlockPos(31, 64, 11)) && node.route().get(0).equals(p)
                : "cruise shift moves one node only";
        assert node.batch() == 5L : "shift keeps the batch";

        assert MarkerOrder.move(p, 7L, false).near(MarkerOrder.move(p.east(), 7L, false), 1.5);
        assert !MarkerOrder.move(p, 7L, false).near(MarkerOrder.move(q, 7L, false), 1.5);

        System.out.println("WaypointDragSelfCheck OK");
    }
}
