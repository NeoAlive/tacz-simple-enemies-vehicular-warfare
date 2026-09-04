package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.goal.BoardVehicleGoal;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;
import com.neoalive.tacz_sewv.entity.ai.support.DroneSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalPosture;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Central leave-level / server-stop / logout eviction for static AI and feedback caches that would
 * otherwise retain entity graphs or UUID rows for the whole process life.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class CacheEviction {

    private CacheEviction() {}

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        int id = entity.getId();
        if (entity instanceof VehicleEntity) {
            HullLocalScan.invalidate(id);
            VehicleDarknessAccuracy.invalidate(id);
        }
        if (entity instanceof AbstractUnit unit) {
            Facts.unbindIfPresent(id);
            TacticalPosture.clearUnit(id);
            DroneSupport.forgetOwner(unit.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HullLocalScan.clearAll();
        VehicleDarknessAccuracy.clearAll();
        VehicleTargeting.clearDiagThrottle();
        Facts.clearAll();
        TacticalPosture.clearAll();
        BoardVehicleGoal.clearCancelFeedback();
        DroneSupport.clearOwnedCache();
        PreferredPathwayData.clearCatalogCache();
        WarnOnce.clear();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var uuid = event.getEntity().getUUID();
        BoardVehicleGoal.clearCancelFeedback(uuid);
        PreferredPathwayData.clearCatalogCache(uuid);
        OrderReport.clearVetoFloor(uuid);
        DroneSupport.forgetOwner(uuid);
    }
}
