package com.neoalive.tacz_sewv.fob;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ITerritoryPost;

/**
 * Keeps FOB rosters and the {@code sewv:fob_cmd} stamp in step. A roster entry is dropped only
 * when its entity leaves the world for good (killed or discarded), never because it is unloaded.
 * A stamped entity that joins and is not on its FOB's roster is listed again when it still belongs
 * there, and loses the stamp otherwise, so it can never refuse orders for a FOB that forgot it.
 */
public final class FobLifecycleEvents {

    private FobLifecycleEvents() {}

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!FobSupport.isStamped(e)) return;
        Entity.RemovalReason reason = e.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;
        boolean listed = false;
        for (ServerLevel dim : level.getServer().getAllLevels()) {
            listed |= FobManager.get(dim).forget(e.getUUID());
        }
        if (listed) FobDebug.logEntity(e, "unassigned from FOB: {}", reason);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        boolean vehicle = e instanceof VehicleEntity;
        if (!vehicle && !(e instanceof PmcUnitEntity)) return;
        BlockPos cmd = FobSupport.stampPos(e);
        if (cmd == null) return;
        FobManager mgr = FobManager.get(level);
        FobInstance fob = mgr.getFob(cmd);
        // No FOB here: either another dimension's or a removed one. Both leave the stamp inert.
        if (fob == null) return;
        if ((vehicle ? fob.assignedVehicles : fob.assignedLiving).contains(e.getUUID())) return;
        if (belongs(fob, e)) {
            mgr.relist(fob, e, vehicle);
            FobDebug.logEntity(e, "relisted on FOB at {}", cmd);
            return;
        }
        FobSupport.clearStamp(e);
        FobSupport.clearRoutePending(e);
        FobDebug.logEntity(e, "cleared orphan stamp for FOB at {}", cmd);
    }

    private static boolean belongs(FobInstance fob, Entity e) {
        if (e instanceof PmcUnitEntity pmc) {
            if (((ITerritoryPost) pmc).sewv$hasTerritoryPost()) return false;
            return pmc.isAlive() && fob.owner.equals(pmc.getOwnerUUID());
        }
        return e instanceof VehicleEntity hull && hull.isAlive() && !hull.isWreck();
    }
}
