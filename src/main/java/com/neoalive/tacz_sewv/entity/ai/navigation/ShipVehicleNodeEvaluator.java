package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

import java.util.EnumMap;
import java.util.Map;

/**
 * Vanilla's water-swimming pathfinder ({@link SwimNodeEvaluator} — the same evaluator dolphins
 * and guardians path through connected water with), sized for the hull a crewman is driving and
 * trimmed to a 2D surface search instead of a 3D dive. It is the water-side counterpart of
 * {@link GroundVehicleNodeEvaluator}, which adapts vanilla's WALKING evaluator the same way for
 * ground hulls — this adapts vanilla's SWIMMING one instead, since {@code GroundVehicleNodeEvaluator}
 * rejects any node near water outright and gives a ship nothing to path across at all.
 *
 * <ul>
 * <li><b>Footprint.</b> {@link #prepare} swaps the crewman's dimensions for the hull's, exactly
 *     like {@code GroundVehicleNodeEvaluator} does — the inherited footprint scan
 *     ({@code getBlockPathType(BlockGetter,int,int,int,Mob)}, untouched) then requires the WHOLE
 *     hull-sized volume to be water before accepting a node, not just the crewman's own cell.</li>
 * <li><b>No breaching.</b> Constructed with {@code allowBreaching = false}: that flag exists so a
 *     dolphin can surface for air, which a hull that already rides the surface has no use for —
 *     and leaving it off means a footprint straddling the shoreline (partly water, partly air)
 *     is rejected outright rather than accepted as a "come up for air" node.</li>
 * <li><b>2D surface search.</b> Vanilla's {@link #getNeighbors} expands all 6 axis directions
 *     (a real 3D swimmer can dive) plus 4 horizontal diagonals. A hull never wants a vertical
 *     waypoint, so {@link #getNeighbors} here drops the {@code UP}/{@code DOWN} pair entirely —
 *     the diagonal pass was already horizontal-only in vanilla, so it needs no change beyond
 *     reading from a horizontal-only neighbor map.</li>
 * </ul>
 *
 * <p>{@code BlockPathTypes.WATER}'s vanilla malus (8.0) applies unmodified: none of this mod's
 * unit classes override it, so water already prices as a normal, non-negative-malus surface with
 * no changes needed here.
 */
public class ShipVehicleNodeEvaluator extends SwimNodeEvaluator {

    public ShipVehicleNodeEvaluator() {
        super(false);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        if (mob.getVehicle() instanceof VehicleEntity vehicle) {
            // The path is searched for the HULL's footprint, not the crewman's — same reasoning
            // as GroundVehicleNodeEvaluator.prepare.
            this.entityWidth = Mth.floor(vehicle.getBbWidth() + 1.0F);
            this.entityHeight = Mth.floor(vehicle.getBbHeight() + 1.0F);
            this.entityDepth = Mth.floor(vehicle.getBbWidth() + 1.0F);
        }
    }

    // Reimplemented rather than filtered after the fact: vanilla's version interleaves the
    // UP/DOWN axis checks with the ones this keeps, so there's no single call to drop from a
    // super invocation. Structurally identical to vanilla otherwise — same findAcceptedNode/
    // isNodeValid calls, same "both flanking neighbors have non-negative malus" gate on each
    // diagonal — just over Direction.Plane.HORIZONTAL instead of Direction.values().
    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int found = 0;
        Map<Direction, Node> horizontal = new EnumMap<>(Direction.class);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Node neighbor = findAcceptedNode(
                    node.x + direction.getStepX(), node.y, node.z + direction.getStepZ());
            horizontal.put(direction, neighbor);
            if (isNodeValid(neighbor)) {
                nodes[found++] = neighbor;
            }
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Direction clockwise = direction.getClockWise();
            Node flank1 = horizontal.get(direction);
            Node flank2 = horizontal.get(clockwise);
            if (flank1 != null && flank1.costMalus >= 0.0F && flank2 != null && flank2.costMalus >= 0.0F) {
                Node diagonal = findAcceptedNode(
                        node.x + direction.getStepX() + clockwise.getStepX(), node.y,
                        node.z + direction.getStepZ() + clockwise.getStepZ());
                if (isNodeValid(diagonal)) {
                    nodes[found++] = diagonal;
                }
            }
        }
        return found;
    }
}
