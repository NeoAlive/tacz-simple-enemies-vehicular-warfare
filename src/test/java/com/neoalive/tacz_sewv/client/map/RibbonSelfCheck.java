package com.neoalive.tacz_sewv.client.map;

import java.util.List;

/** Headless checks for the map ribbon: visibility, nesting, split segments, idle placement. */
public final class RibbonSelfCheck {

    private static RibbonLayout.Unit u(int id, int platoon, long formation, long order, boolean view) {
        return new RibbonLayout.Unit(id, id, platoon, formation, order, 0, false, view);
    }

    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");

        // A platoon of three, two of them holding together; one idle unit; one platoon member off view.
        List<RibbonLayout.Unit> units = List.of(
                u(1, 5, 0, 0, true),
                u(2, 5, 0, 77, true),
                u(3, 5, 0, 77, false),
                u(4, 0, 0, 0, true),
                u(9, 0, 0, 0, false));
        RibbonLayout.Result r = RibbonLayout.build(units);
        assert r.units().size() == 4 : "off-view platoon member shown, off-view idle dropped: " + r.units();
        assert r.idleStart() == 3 && r.units().get(3).vehicleId() == 4 : "idle unit last";
        RibbonLayout.Span platoon = only(r, RibbonLayout.Lane.PLATOON);
        assert platoon.first() == 0 && platoon.last() == 2 : "platoon spans its three";
        RibbonLayout.Span hold = only(r, RibbonLayout.Lane.ORDER);
        assert hold.first() >= platoon.first() && hold.last() <= platoon.last() && hold.last() - hold.first() == 1
                : "hold nests inside the platoon";

        // A formation drawn from two platoons is split, both segments one index, open on the inner ends.
        List<RibbonLayout.Unit> mixed = List.of(
                u(1, 5, 40, 0, true), u(2, 5, 40, 0, true),
                u(3, 6, 40, 0, true), u(4, 6, 40, 0, true));
        RibbonLayout.Result m = RibbonLayout.build(mixed);
        List<RibbonLayout.Span> form = m.spans().stream().filter(s -> s.lane() == RibbonLayout.Lane.FORMATION).toList();
        assert form.size() == 2 : "formation split across platoons: " + form;
        assert form.get(0).index() == form.get(1).index() : "one index for both segments";
        assert !form.get(0).openLeft() && form.get(0).openRight() && form.get(1).openLeft() && !form.get(1).openRight()
                : "open ends face each other";
        assert RibbonLayout.members(m, form.get(0)).size() == 4 : "members spans every segment";

        // Singletons get no bracket; a selected off-view unit is shown.
        List<RibbonLayout.Unit> single = List.of(
                u(1, 5, 0, 0, true),
                new RibbonLayout.Unit(2, 2, 0, 0, 0, 0, true, false));
        RibbonLayout.Result s = RibbonLayout.build(single);
        assert s.units().size() == 2 : "selected unit pinned";
        assert s.spans().isEmpty() : "no bracket for a group of one";

        System.out.println("RibbonSelfCheck OK");
    }

    private static RibbonLayout.Span only(RibbonLayout.Result r, RibbonLayout.Lane lane) {
        List<RibbonLayout.Span> l = r.spans().stream().filter(s -> s.lane() == lane).toList();
        assert l.size() == 1 : lane + " spans: " + l;
        return l.get(0);
    }
}
