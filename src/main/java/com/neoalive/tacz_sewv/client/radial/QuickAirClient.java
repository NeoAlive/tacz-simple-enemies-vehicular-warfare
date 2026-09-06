package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.client.PlayerRappelClient;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPlayerCrewRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappelLock;

/**
 * Client-side Air quick commands: player rappel + radius takeoff/landing/emergency land
 * (TDT reinvoke). Eligibility is evaluated live so the wheel can gray wedges that cannot fire.
 */
public final class QuickAirClient {

    private QuickAirClient() {}

    public static boolean isAirClientAction(String pipelineId) {
        return QuickCommandRegistry.ID_RAPPEL_SELF.equals(pipelineId)
                || QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId);
    }

    public static boolean isEnabled(String pipelineId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (QuickCommandRegistry.ID_RAPPEL_SELF.equals(pipelineId)) {
            return canSelfRappel(mc);
        }
        if (QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)) {
            return canCrewRappel(mc);
        }
        if (QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId)) {
            return !findAircraftPilots(mc).isEmpty();
        }
        return true;
    }

    /** @return {@code true} if an action was sent (caller may close the wheel). */
    public static boolean fire(String pipelineId) {
        if (!isEnabled(pipelineId)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        if (QuickCommandRegistry.ID_RAPPEL_SELF.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketPlayerSelfRappel());
            return true;
        }
        if (QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketPlayerCrewRappel());
            return true;
        }
        List<Integer> pilots = findAircraftPilots(mc);
        if (pilots.isEmpty()) return false;
        if (QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_TAKEOFF, null, TdtScreen.heliAltitude()));
            return true;
        }
        if (QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_LANDING, mc.player.blockPosition(), 0));
            return true;
        }
        // Same as TDT: no pad — server picks flat ground next to each aircraft.
        if (QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_EMERGENCY_LAND, null, 0));
            return true;
        }
        return false;
    }

    private static boolean canSelfRappel(Minecraft mc) {
        if (!(mc.player.getVehicle() instanceof VehicleEntity hull)
                || !HullFacts.isHelicopterHull(hull)) {
            return false;
        }
        return PlayerRappelClient.lockMode() == PacketPlayerSelfRappelLock.MODE_OFF;
    }

    private static boolean canCrewRappel(Minecraft mc) {
        if (!(mc.player.getVehicle() instanceof VehicleEntity hull)
                || !HullFacts.isHelicopterHull(hull)) {
            return false;
        }
        return hull.getFirstPassenger() == mc.player;
    }

    /** Owned aircraft drivers within the Quick Evac heli search radius. */
    private static List<Integer> findAircraftPilots(Minecraft mc) {
        Player player = mc.player;
        double radius = SewvConfig.QUICK_EVAC_HELI_SEARCH_RADIUS.get();
        double r2 = radius * radius;
        AABB box = player.getBoundingBox().inflate(radius);
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (PmcUnitEntity pmc : mc.level.getEntitiesOfClass(PmcUnitEntity.class, box,
                u -> u.isAlive() && PmcOwnerSupport.isOwner(player, u))) {
            if (pmc.distanceToSqr(player) > r2) continue;
            if (!(pmc.getVehicle() instanceof VehicleEntity hull)) continue;
            if (hull.getFirstPassenger() != pmc) continue;
            if (!HullFacts.isHelicopterHull(hull) && !HullFacts.isPlaneHull(hull)) continue;
            out.add(pmc.getId());
        }
        return new ArrayList<>(out);
    }
}
