package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.entity.ai.WaterSupport;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

import java.util.EnumMap;
import java.util.Map;

/**
 * Vanilla's swim pathfinder, sized to the hull, flattened to a surface search, and cost-weighted
 * by depth so routes favour open water and only cut through shallows when there is no alternative.
 */
public class ShipVehicleNodeEvaluator extends SwimNodeEvaluator {

    /** Depth (in water blocks) at or above which a node costs nothing extra. */
    private static final int COMFORTABLE_DEPTH = 3;
    /** Added cost per block of draft short of {@link #COMFORTABLE_DEPTH}. Steep, but finite. */
    private static final float SHALLOW_PENALTY = 6.0F;

    public ShipVehicleNodeEvaluator() {
        super(false);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        if (mob.getVehicle() instanceof VehicleEntity vehicle) {
            this.entityWidth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            this.entityHeight = Mth.floor(vehicle.getBbHeight() + 1.0F);
            this.entityDepth = Mth.floor(vehicle.getBbWidth() + 1.0F);
        }
    }

    /** Deeper water is cheaper; a one-block puddle is expensive but still reachable. */
    @Override
    protected Node findAcceptedNode(int x, int y, int z) {
        Node node = super.findAcceptedNode(x, y, z);
        if (node == null) return null;
        int depth = WaterSupport.depthAt(this.level, x, y, z, COMFORTABLE_DEPTH);
        if (depth < COMFORTABLE_DEPTH) {
            node.costMalus += SHALLOW_PENALTY * (COMFORTABLE_DEPTH - depth);
        }
        return node;
    }

    /** Horizontal only — a hull rides the surface and never wants a vertical waypoint. */
    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int found = 0;
        Map<Direction, Node> sides = new EnumMap<>(Direction.class);

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Node neighbor = findAcceptedNode(node.x + dir.getStepX(), node.y, node.z + dir.getStepZ());
            sides.put(dir, neighbor);
            if (isNodeValid(neighbor)) nodes[found++] = neighbor;
        }

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Direction cw = dir.getClockWise();
            Node diagonal = findAcceptedNode(
                    node.x + dir.getStepX() + cw.getStepX(), node.y,
                    node.z + dir.getStepZ() + cw.getStepZ());
            if (isDiagonalNodeValid(diagonal, sides.get(dir), sides.get(cw))) nodes[found++] = diagonal;
        }
        return found;
    }
}
