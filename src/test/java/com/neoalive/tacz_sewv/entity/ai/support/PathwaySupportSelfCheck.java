package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * Headless self-check for preferred pathway proximity math and id validation.
 */
public final class PathwaySupportSelfCheck {

    public static void main(String[] args) {
        assert PathwaySupport.isValidPathId("path_1");
        assert PathwaySupport.isValidPathId("alpha-beta_2");
        assert !PathwaySupport.isValidPathId("Bad Path");
        assert !PathwaySupport.isValidPathId("");

        var a = new net.minecraft.core.BlockPos(0, 64, 0);
        var b = new net.minecraft.core.BlockPos(10, 64, 0);
        // On segment centreline (block centres + 0.5)
        double mid = PathwaySupport.distanceToSegmentSq(5.5, 64, 0.5, a, b);
        assert mid < 0.01 : mid;
        // Perpendicular 3 blocks off centreline
        double off = PathwaySupport.distanceToSegmentSq(5.5, 64, 3.5, a, b);
        assert Math.abs(off - 9.0) < 0.01 : off;

        var route = java.util.List.of(
                new net.minecraft.core.BlockPos(0, 64, 0),
                new net.minecraft.core.BlockPos(20, 64, 0),
                new net.minecraft.core.BlockPos(40, 64, 0));
        // Adjacent MOVE dest on path, unit offset beside corridor
        assert PathwaySupport.pathBboxNear(5, 5, route, 24);
        assert PathwaySupport.distanceToNearestSegmentSq(5, 5, route) <= PathwaySupport.MOVE_CORRIDOR_RADIUS_SQ;

        System.out.println("PathwaySupportSelfCheck passed.");
    }
}
