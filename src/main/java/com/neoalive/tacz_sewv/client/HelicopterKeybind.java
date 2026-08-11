package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiConsumer;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketRappelHelicopter;

/** Helicopter takeoff/land/rappel orders sent from the Tactical Data Terminal ({@link TdtScreen}). */
public class HelicopterKeybind {

    // How far out the landing-pad pick reaches. mc.hitResult only covers the player's
    // ~5-block interaction range — useless for designating a pad across the field — so
    // the TDT does its own long-range block pick when it opens.
    public static final double LAND_PICK_RANGE = 128.0;
    private static final double CLIENT_DISCOVERY_RADIUS = 512.0;

    /** Order owned aircraft pilots to climb to (and hold) {@code altitude} as their live cruise trim. */
    public static void orderTakeoff(int altitude) {
        withPilots("message.tacz_sewv.heli.takeoff.none", HelicopterKeybind::isAircraftPilot,
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_TAKEOFF, null, altitude)));
    }

    /**
     * Order owned aircraft pilots to set down on {@code pad}. Prefer resolving the pad
     * from the live crosshair when the TDT button is pressed; a null pad hints to look at a block.
     */
    public static void orderLand(@Nullable BlockPos pad) {
        if (pad == null) {
            Player player = Minecraft.getInstance().player;
            if (player != null) BoardKeybind.hint(player, "message.tacz_sewv.heli.no_pad");
            return;
        }
        withPilots("message.tacz_sewv.heli.land.none", HelicopterKeybind::isAircraftPilot,
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_LANDING, pad, 0)));
    }

    /** Order owned helicopter pilots to rappel weaponless passengers (Stages 1–5 sequence). */
    public static void orderRappel() {
        withPilots("message.tacz_sewv.heli.rappel.none", HelicopterKeybind::isHelicopterPilot,
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketRappelHelicopter(unitIds)));
    }

    /**
     * Flight orders differ from other TDT commands: when nothing is ribbon-selected, fall
     * back to every owned PMC in scan range (the old TDT behaviour for this category only).
     * Any selected gunner/passenger is remapped to the stick ({@code seat 0}) before
     * {@code filter} runs.
     *
     * <p>Discovery stays at the generous client radius rather than reading
     * {@code planeCommandRadius}: that is a COMMON config, so a dedicated server's value need
     * not match the client's, and the server enforces the real gate in
     * {@link PacketHelicopterCommand} where it is authoritative.
     */
    private static void withPilots(String emptyKey, java.util.function.Predicate<PmcUnitEntity> filter,
                                   BiConsumer<Player, List<Integer>> order) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> seeds = new ArrayList<>(TdtSelection.resolve(CLIENT_DISCOVERY_RADIUS));
        if (seeds.isEmpty()) {
            for (TdtSelection.Entry e : TdtSelection.scanned()) {
                seeds.add(e.id());
            }
        }

        LinkedHashSet<Integer> pilotIds = new LinkedHashSet<>();
        for (int id : seeds) {
            if (!(mc.level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            if (!pmc.isOwnedBy(player)) continue;
            PmcUnitEntity pilot = pilotOf(pmc, player);
            if (pilot != null && filter.test(pilot)) {
                pilotIds.add(pilot.getId());
            }
        }

        if (pilotIds.isEmpty()) {
            if (TdtSelection.selected().isEmpty()
                    && (CommanderOverlayRenderer.selectedUnitsSnapshot == null
                    || CommanderOverlayRenderer.selectedUnitsSnapshot.isEmpty())) {
                BoardKeybind.hint(player, "message.tacz_sewv.tdt.need_selection");
            } else {
                BoardKeybind.hint(player, emptyKey);
            }
            return;
        }
        order.accept(player, new ArrayList<>(pilotIds));
    }

    /** Stick for an owned crew member; null when the unit is on foot or not owned. */
    @Nullable
    private static PmcUnitEntity pilotOf(PmcUnitEntity pmc, Player player) {
        if (!(pmc.getVehicle() instanceof VehicleEntity v)) return null;
        if (!(v.getFirstPassenger() instanceof PmcUnitEntity driver)) return null;
        return driver.isOwnedBy(player) ? driver : null;
    }

    // Seat 0 of any aircraft — rotary OR fixed wing. Planes were excluded here and in
    // PacketHelicopterCommand, which is why the TDT/map Takeoff and Land buttons reported
    // "no helicopters" for a hangar full of them.
    private static boolean isAircraftPilot(PmcUnitEntity pilot) {
        return pilot.getVehicle() instanceof VehicleEntity v
                && v.getFirstPassenger() == pilot
                && (HullFacts.isHelicopterHull(v) || HullFacts.isPlaneHull(v));
    }

    // Rappel is rotary-wing only: a fixed-wing hull has no hover to drop a stick of infantry from.
    private static boolean isHelicopterPilot(PmcUnitEntity pilot) {
        return pilot.getVehicle() instanceof VehicleEntity v
                && v.getFirstPassenger() == pilot
                && HullFacts.isHelicopterHull(v);
    }
}
