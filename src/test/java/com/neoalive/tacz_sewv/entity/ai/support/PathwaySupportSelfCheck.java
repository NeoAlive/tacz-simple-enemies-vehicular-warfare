package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Headless self-check for preferred pathway entry (mouth + detour) matching and id validation.
 */
public final class PathwaySupportSelfCheck {

    private static final double ENTRY = 16.0;
    private static final double COAX = 24.0;

    public static void main(String[] args) {
        assert PathwaySupport.isValidPathId("path_1");
        assert PathwaySupport.isValidPathId("alpha-beta_2");
        assert !PathwaySupport.isValidPathId("Bad Path");
        assert !PathwaySupport.isValidPathId("");

        var a = new BlockPos(0, 64, 0);
        var b = new BlockPos(10, 64, 0);
        // On segment centreline (block centres + 0.5)
        double mid = PathwaySupport.distanceToSegmentSq(5.5, 64, 0.5, a, b);
        assert mid < 0.01 : mid;
        // Perpendicular 3 blocks off centreline
        double off = PathwaySupport.distanceToSegmentSq(5.5, 64, 3.5, a, b);
        assert Math.abs(off - 9.0) < 0.01 : off;

        // A bridge: entry (0,0), mid (20,0), exit (40,0) along +X.
        List<BlockPos> route = List.of(new BlockPos(0, 64, 0), new BlockPos(20, 64, 0), new BlockPos(40, 64, 0));
        List<BlockPos> reversed = PathwaySupport.reversed(route);
        Vec3 still = Vec3.ZERO;

        // Unit at the entry, destination on the far bank along the axis: full route, forward.
        assert route.equals(match(-5, 0, dest(100, 0), still, route));
        // Same spot, destination off to the side: not coaxial.
        assert match(-5, 0, dest(0, 80), still, route) == null;
        // Destination behind the entry: not coaxial.
        assert match(-5, 0, dest(-60, 0), still, route) == null;
        // Unit at the exit heading back over: the reversed route.
        assert reversed.equals(match(45, 0, dest(-100, 0), still, route));
        // Unit mid-path: no entry, whatever the destination.
        assert match(20, 5, dest(100, 0), still, route) == null;
        // Destination on the path itself: walk stops at the nearest node.
        assert List.of(route.get(0), route.get(1)).equals(match(-5, 0, dest(22, 3), still, route));
        // Far destination slightly off-axis: outside the cylinder, inside the 30° cone.
        assert route.equals(match(-5, 0, dest(1000, 100), still, route));
        // Far destination well off-axis: neither.
        assert match(-5, 0, dest(200, 200), still, route) == null;
        // FREE_FIRE: walking along the axis joins, walking against it does not.
        assert route.equals(match(-5, 0, null, new Vec3(0.2, 0, 0), route));
        assert match(-5, 0, null, new Vec3(-0.2, 0, 0), route) == null;

        // The reported river case: path (20,30)->(20,-30), river along z=0, unit at (60,80) bound for
        // (60,-60). Far from either end, so only the detour rule can send it over: ~1.24x the line.
        List<BlockPos> bridge = List.of(new BlockPos(20, 64, 30), new BlockPos(20, 64, -30));
        PathwaySupport.Match river = PathwaySupport.matchEntry(60.5, 80.5, dest(60, -60), still, bridge, ENTRY, COAX, 1.5);
        assert river != null && bridge.equals(river.route()) : river;
        assert Math.abs(river.cost() - 1.243) < 0.01 : river.cost();
        // Tolerance below the detour, or the rule off: straight line.
        assert PathwaySupport.matchEntry(60.5, 80.5, dest(60, -60), still, bridge, ENTRY, COAX, 1.2) == null;
        assert PathwaySupport.matchEntry(60.5, 80.5, dest(60, -60), still, bridge, ENTRY, COAX, 1.0) == null;
        // Destination on the unit's own bank: never worth the path.
        assert PathwaySupport.matchEntry(60.5, 80.5, dest(60, 40), still, bridge, ENTRY, COAX, 1.5) == null;
        // From the far bank the path is taken the other way round.
        PathwaySupport.Match back = PathwaySupport.matchEntry(60.5, -80.5, dest(60, 60), still, bridge, ENTRY, COAX, 1.5);
        assert back != null && PathwaySupport.reversed(bridge).equals(back.route()) : back;

        // Mouth rule.
        assert PathwaySupport.coaxialExit(route, 100, 0, COAX) == 2;
        assert PathwaySupport.coaxialExit(route, 0, 80, COAX) < 0;

        assert PathwaySupport.isPrefix(List.of(route.get(0), route.get(1)), route);
        assert !PathwaySupport.isPrefix(List.of(route.get(1)), route);

        System.out.println("PathwaySupportSelfCheck passed.");
    }

    private static Vec3 dest(double x, double z) {
        return new Vec3(x + 0.5, 64, z + 0.5);
    }

    private static List<BlockPos> match(double x, double z, Vec3 dest, Vec3 velocity, List<BlockPos> route) {
        // Detour rule off (1.0): these cases pin the mouth/coaxial rule on its own.
        PathwaySupport.Match m = PathwaySupport.matchEntry(x + 0.5, z + 0.5, dest, velocity, route, ENTRY, COAX, 1.0);
        return m == null ? null : m.route();
    }
}
