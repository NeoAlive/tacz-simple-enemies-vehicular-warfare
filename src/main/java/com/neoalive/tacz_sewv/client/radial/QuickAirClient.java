package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.client.PlayerRappelClient;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPlayerCrewRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappel;
import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappelLock;
import com.neoalive.tacz_sewv.network.PacketRappelHelicopter;

/**
 * Client-side Air wheel orders: player rappel + takeoff / landing / emergency land / AI rappel.
 * Eligibility is evaluated live so the wheel can gray wedges that cannot fire.
 *
 * <p>Pilots follow the wheel's scope ({@link WheelScope}): the selection mapped onto each hull's
 * stick (seat 0), else owned pilots within the Evacuation heli radius. A selected gunner or
 * passenger therefore orders the aircraft it rides in.
 */
public final class QuickAirClient {

    private QuickAirClient() {}

    public static boolean isAirClientAction(String pipelineId) {
        return QuickCommandRegistry.ID_RAPPEL_SELF.equals(pipelineId)
                || QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_LAND_HELIPAD.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId);
    }

    public static boolean isEnabled(String pipelineId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (QuickCommandRegistry.ID_RAPPEL_SELF.equals(pipelineId)) {
            return canSelfRappel(mc);
        }
        // Rappel Crew: your own helicopter if you fly one, otherwise owned AI helicopter pilots.
        if (QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)) {
            return flyingHelicopter(mc) || !findPilots(mc, false).isEmpty();
        }
        if (QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId)) {
            return !findPilots(mc, null).isEmpty();
        }
        // Runway landing is fixed-wing only, helipad landing rotary-wing only.
        if (QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)) {
            return !findPilots(mc, true).isEmpty();
        }
        if (QuickCommandRegistry.ID_QUICK_LAND_HELIPAD.equals(pipelineId)) {
            return !findPilots(mc, false).isEmpty();
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
            if (flyingHelicopter(mc)) {
                NetworkHandler.CHANNEL.sendToServer(new PacketPlayerCrewRappel());
            } else {
                NetworkHandler.CHANNEL.sendToServer(new PacketRappelHelicopter(findPilots(mc, false)));
            }
            return true;
        }
        List<Integer> pilots = pilotsFor(pipelineId);
        if (pilots.isEmpty()) return false;
        if (QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_LANDING, mc.player.blockPosition(), 0));
            return true;
        }
        if (QuickCommandRegistry.ID_QUICK_LAND_HELIPAD.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_LAND_HELIPAD, null, 0));
            return true;
        }
        // Takeoff on an aircraft already flying retrims it to the new cruise altitude.
        if (QuickCommandRegistry.ID_QUICK_TAKEOFF.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_TAKEOFF, null, OrderScales.heliAltitude()));
            return true;
        }
        // No pad — server picks flat ground next to each aircraft.
        if (QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(
                    pilots, IHelicopterPilot.HELI_CMD_EMERGENCY_LAND, null, 0));
            return true;
        }
        return false;
    }

    /** The aircraft pilots {@link #fire} would order, for the queue's completion check. */
    public static List<Integer> pilotsFor(String pipelineId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return List.of();
        if (QuickCommandRegistry.ID_QUICK_LANDING.equals(pipelineId)) return findPilots(mc, true);
        if (QuickCommandRegistry.ID_QUICK_LAND_HELIPAD.equals(pipelineId)
                || QuickCommandRegistry.ID_RAPPEL_CREW.equals(pipelineId)) {
            return findPilots(mc, false);
        }
        return findPilots(mc, null);
    }

    private static boolean canSelfRappel(Minecraft mc) {
        if (!(mc.player.getVehicle() instanceof VehicleEntity hull)
                || !HullFacts.isHelicopterHull(hull)) {
            return false;
        }
        return PlayerRappelClient.lockMode() == PacketPlayerSelfRappelLock.MODE_OFF;
    }

    /** The player is a helicopter's stick — Rappel Crew then drops the player's own passengers. */
    private static boolean flyingHelicopter(Minecraft mc) {
        return mc.player.getVehicle() instanceof VehicleEntity hull
                && HullFacts.isHelicopterHull(hull)
                && hull.getFirstPassenger() == mc.player;
    }

    /**
     * Owned aircraft pilots in the wheel's scope.
     *
     * @param planes {@code true} for fixed-wing only, {@code false} for rotary-wing only, null for both
     */
    private static List<Integer> findPilots(Minecraft mc, @javax.annotation.Nullable Boolean planes) {
        Player player = mc.player;
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        // Any crew member stands for its stick; the radius is the horizontal heli search reach.
        for (int id : WheelScope.units(false, SewvConfig.QUICK_EVAC_HELI_SEARCH_RADIUS.get(),
                pmc -> pmc.getVehicle() instanceof VehicleEntity)) {
            if (!(mc.level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            if (!(pmc.getVehicle() instanceof VehicleEntity hull)) continue;
            if (!(hull.getFirstPassenger() instanceof PmcUnitEntity pilot)
                    || !PmcOwnerSupport.isOwner(player, pilot)) {
                continue;
            }
            boolean plane = HullFacts.isPlaneHull(hull);
            if (!plane && !HullFacts.isHelicopterHull(hull)) continue;
            if (planes != null && planes != plane) continue;
            out.add(pilot.getId());
        }
        return new ArrayList<>(out);
    }
}
