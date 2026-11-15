package com.neoalive.tacz_sewv.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.support.FormationAnchorMode;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.map.MarkerOrder;

/**
 * Server half of dragging an order waypoint on the map. The anchor unit's point goes to the
 * dragged position and every other unit of the same batch moves by the same delta, so line slots
 * and formation shapes keep their offsets.
 *
 * <p>Orders are rewritten in place, not reissued: going through SEM's order packet would run the
 * whole stand-down list, the order queue and the radio line several times a second. The batch id is
 * the staleness guard, since any new order restamps it.
 */
public final class WaypointEdits {

    /** Live (non-final) applies per player, at most one per this many game ticks. */
    private static final int LIVE_GAP_TICKS = 4;

    private static final Map<ServerPlayer, Long> LAST_LIVE = new WeakHashMap<>();

    private WaypointEdits() {}

    public static void apply(ServerPlayer sender, MarkerOrder.Type kind, long batch, List<Integer> ids,
                             int node, BlockPos to, boolean fin, boolean detach) {
        if (kind == MarkerOrder.Type.NONE || ids.isEmpty()) return;
        if (kind == MarkerOrder.Type.CRUISE && !fin) return;
        Level level = sender.level();
        long now = level.getGameTime();
        if (!fin) {
            Long last = LAST_LIVE.get(sender);
            if (last != null && now - last < LIVE_GAP_TICKS) return;
            LAST_LIVE.put(sender, now);
        }

        List<PmcUnitEntity> units = new ArrayList<>();
        for (int id : ids) {
            if (!(level.getEntity(id) instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (OrderBatch.of(pmc) != batch || kindOf(pmc) != kind) continue;
            if (!PmcOwnerSupport.isOwner(sender, pmc)) continue;
            units.add(pmc);
        }
        if (units.isEmpty()) return;

        PmcUnitEntity anchor = units.get(0);
        BlockPos at = pointOf(anchor, kind, node);
        if (at == null) return;
        int dx = to.getX() - at.getX();
        int dz = to.getZ() - at.getZ();
        if (dx == 0 && dz == 0 && !fin) return;

        OrderBatch.edit(() -> {
            for (PmcUnitEntity pmc : units) {
                shift(pmc, kind, node, dx, dz, fin, pmc == anchor ? to.getY() : Integer.MIN_VALUE);
            }
        });
        if (detach) OrderBatch.detach(units, now * 31L + anchor.getId());
        if (fin) {
            for (PmcUnitEntity pmc : units) {
                if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
                    CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
                    break;
                }
            }
        }
    }

    /** The order kind a unit stands under, read in the same precedence the map preview uses. */
    public static MarkerOrder.Type kindOf(PmcUnitEntity pmc) {
        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return MarkerOrder.Type.NONE;
        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        if (patrol.sewv$isPatrolling()) {
            return switch (patrol.sewv$getPatrolMode()) {
                case IVehiclePatrol.MODE_CRUISE -> MarkerOrder.Type.CRUISE;
                case IVehiclePatrol.MODE_SEARCH -> MarkerOrder.Type.SEARCH;
                case IVehiclePatrol.MODE_PATROL -> MarkerOrder.Type.PATROL;
                default -> MarkerOrder.Type.NONE;
            };
        }
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            Vec3 t = pmc.getMoveToTarget();
            if (t != null && !t.equals(Vec3.ZERO)) return MarkerOrder.Type.MOVE;
        }
        return MarkerOrder.Type.NONE;
    }

    @Nullable
    private static BlockPos pointOf(PmcUnitEntity pmc, MarkerOrder.Type kind, int node) {
        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        return switch (kind) {
            case MOVE -> BlockPos.containing(pmc.getMoveToTarget());
            case PATROL, SEARCH -> patrol.sewv$getPatrolOrigin();
            case CRUISE -> {
                List<BlockPos> route = patrol.sewv$getCruiseRoute();
                yield node >= 0 && node < route.size() ? route.get(node) : null;
            }
            case NONE -> null;
        };
    }

    private static void shift(PmcUnitEntity pmc, MarkerOrder.Type kind, int node, int dx, int dz,
                              boolean fin, int anchorY) {
        Level level = pmc.level();
        switch (kind) {
            case MOVE -> {
                Vec3 t = pmc.getMoveToTarget();
                double x = t.x + dx;
                double z = t.z + dz;
                double fallback = anchorY != Integer.MIN_VALUE ? anchorY : t.y;
                pmc.setMoveToTarget(new Vec3(x, groundY(level, x, z, fallback), z));
                OrderBatch.clearReached(pmc);
                IFormationMember member = (IFormationMember) pmc;
                if (FormationAnchorMode.byId(member.sewv$getFormationAnchorMode()) == FormationAnchorMode.POSITION) {
                    Vec3 a = member.sewv$getFormationAnchorPos();
                    if (a != null) VehicleFormation.freezeAnchor(pmc, a.add(dx, 0, dz));
                }
                pmc.resetCommanderGoalCooldown();
            }
            case PATROL, SEARCH -> {
                BlockPos o = ((IVehiclePatrol) pmc).sewv$getPatrolOrigin();
                if (o == null) return;
                PatrolSupport.moveOrigin(pmc, moved(level, o, dx, dz, anchorY), fin);
            }
            case CRUISE -> {
                List<BlockPos> route = ((IVehiclePatrol) pmc).sewv$getCruiseRoute();
                if (node < 0 || node >= route.size()) return;
                PatrolSupport.moveCruiseNode(pmc, node, moved(level, route.get(node), dx, dz, anchorY));
            }
            case NONE -> { }
        }
    }

    private static BlockPos moved(Level level, BlockPos p, int dx, int dz, int anchorY) {
        int x = p.getX() + dx;
        int z = p.getZ() + dz;
        double fallback = anchorY != Integer.MIN_VALUE ? anchorY : p.getY();
        return new BlockPos(x, (int) Math.floor(groundY(level, x, z, fallback)), z);
    }

    /** Surface height where the chunk is loaded, never loading one; {@code fallback} otherwise. */
    private static double groundY(Level level, double x, double z, double fallback) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return fallback;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }
}
