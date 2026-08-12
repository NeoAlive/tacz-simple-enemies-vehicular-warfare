package com.neoalive.tacz_sewv.network;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.neoalive.tacz_sewv.TaczSewv;

public class NetworkHandler {

    /**
     * Action-bar result of an order: {@code base + ".none"/".single"/".multiple"} by count —
     * GRAY when nothing took the order, {@code color} otherwise. Every caller passes the count
     * of units the SERVER actually accepted, never the client's optimistic guess.
     */
    public static void orderFeedback(Player player, String base, int count, ChatFormatting color, Object... args) {
        String key = base + (count == 0 ? ".none" : count == 1 ? ".single" : ".multiple");
        sendOrderFeedback(player, Component.translatable(key, args)
                .withStyle(count == 0 ? ChatFormatting.GRAY : color));
    }

    public static void sendOrderFeedback(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new PacketOrderFeedback(message));
        } else {
            player.displayClientMessage(message, true);
        }
    }

    // Bumped when the wire format changes (2: list sizes/ids became VarInts; 3: added the mortar
    // order; 4: added the vehicle formation order; 5: added the patrol order; 6: formation carries
    // a shape id + row size, and the heli command carries a cruise altitude; 7: board carries a
    // passenger-only flag and the area-task order carries a patrol/search mode; 8: added the escort
    // order; 9: added the owned-vehicle map sync, this channel's first server->client packet;
    // 10: map markers carry an allegiance so other factions can be shown; 11: the area task carries
    // an optional origin, so it can be centred on a map click instead of on the sender; 12: the
    // area task also carries a cruise route) so a mismatched client/server pair is rejected at
    // handshake instead of misparsing.
    // 13, 14, 15: Various previous changes.
    // 16: added the player doctrine save packet.
    // 17: world vehicle pool editor (open + update).
    // 18: map markers carry PMC health/energy fractions.
    // 19: order feedback became a client-gated S->C packet.
    // 20–22: prior bumps.
    // 23: heli firing-run phase sync for hover overlay.
    // 24: PMC rappel order packet.
    // 25: map markers carry optional OpenPAC tint RGB.
    // 26: removed PacketToggleAdvancing (autonomous KotH scrapped).
    // 27: PacketSweepAndAdvance (player-triggered sweep + claim).
    // 28: PacketOwnedVehicles optional SweepOverlayState.
    // 29: capture_point / team_base config GUIs (open + save).
    // 30: PacketInvasionBillboards (world-space capture UI) — removed in 32.
    // 31: team_base aiVehicleCount on open/save packets.
    // 32: PacketInvasionHud replaces billboards; capture_point open/save drop billboard fields.
    // 33: invisible toggle on capture_point / team_base open+save packets.
    // 34: PacketInvasionHud carries team names + per-vehicle side colours for overlay.
    // 35: team_base endInvasionOnCapture on open+save packets.
    // 36: GUARD_POSITION / REACH_GUARD packets; VehicleMarker.hasGuard.
    // 37: vehicle faction skin sticky tag sync + reloadSkins.
    // 38: C→S PacketSetVehicleSkin (sneak-right-click repair tool cycle).
    // 39: Category.HELI in vehicle pool editor packets.
    // 40: misc cue/armor editor open + update packets.
    // 41: PacketVehicleSkin carries sticky RNG salt for numbered skin pools.
    // 42: PacketTrenchNetworks (trench / emplacement map markers).
    // 43: PacketEntrench (ENTRENCHED area task).
    // 44: PacketRadioCommand (handheld radio GUI fire mission).
    // 45: Category.TOW in vehicle pool editor packets.
    // 47: PacketReloadVehicleSkins carries a reset-to-jar-defaults flag.
    private static final String PROTOCOL_VERSION = "47";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TaczSewv.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    public static void register() {
        CHANNEL.registerMessage(
                nextId(),
                PacketBoardVehicle.class,
                PacketBoardVehicle::encode,
                PacketBoardVehicle::new,
                PacketBoardVehicle::handle
        );

        CHANNEL.registerMessage(
        nextId(),
        PacketDismountVehicle.class,
        PacketDismountVehicle::encode,
        PacketDismountVehicle::new,
        PacketDismountVehicle::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketHelicopterCommand.class,
                PacketHelicopterCommand::encode,
                PacketHelicopterCommand::new,
                PacketHelicopterCommand::handle
        );

        // nextId() is a plain counter, so new packets go on the end — inserting one
        // above would renumber every packet after it.
        CHANNEL.registerMessage(
                nextId(),
                PacketManMortar.class,
                PacketManMortar::encode,
                PacketManMortar::new,
                PacketManMortar::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketVehicleFormation.class,
                PacketVehicleFormation::encode,
                PacketVehicleFormation::new,
                PacketVehicleFormation::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketPatrolVehicle.class,
                PacketPatrolVehicle::encode,
                PacketPatrolVehicle::new,
                PacketPatrolVehicle::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketEscort.class,
                PacketEscort::encode,
                PacketEscort::new,
                PacketEscort::handle
        );

        // The one server->client packet here (PacketDistributor.PLAYER, see OwnedVehicleTracker).
        CHANNEL.registerMessage(
                nextId(),
                PacketOwnedVehicles.class,
                PacketOwnedVehicles::encode,
                PacketOwnedVehicles::new,
                PacketOwnedVehicles::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketOrderFeedback.class,
                PacketOrderFeedback::encode,
                PacketOrderFeedback::new,
                PacketOrderFeedback::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketSaveDoctrine.class,
                PacketSaveDoctrine::encode,
                PacketSaveDoctrine::new,
                PacketSaveDoctrine::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketOpenPoolEditor.class,
                PacketOpenPoolEditor::encode,
                PacketOpenPoolEditor::new,
                PacketOpenPoolEditor::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketUpdateVehiclePools.class,
                PacketUpdateVehiclePools::encode,
                PacketUpdateVehiclePools::new,
                PacketUpdateVehiclePools::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketHeliRunPhase.class,
                PacketHeliRunPhase::encode,
                PacketHeliRunPhase::new,
                PacketHeliRunPhase::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketRappelHelicopter.class,
                PacketRappelHelicopter::encode,
                PacketRappelHelicopter::new,
                PacketRappelHelicopter::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketSweepAndAdvance.class,
                PacketSweepAndAdvance::encode,
                PacketSweepAndAdvance::new,
                PacketSweepAndAdvance::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketOpenCapturePointGui.class,
                PacketOpenCapturePointGui::encode,
                PacketOpenCapturePointGui::new,
                PacketOpenCapturePointGui::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSaveCapturePoint.class,
                PacketSaveCapturePoint::encode,
                PacketSaveCapturePoint::new,
                PacketSaveCapturePoint::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketOpenTeamBaseGui.class,
                PacketOpenTeamBaseGui::encode,
                PacketOpenTeamBaseGui::new,
                PacketOpenTeamBaseGui::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSaveTeamBase.class,
                PacketSaveTeamBase::encode,
                PacketSaveTeamBase::new,
                PacketSaveTeamBase::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketInvasionHud.class,
                PacketInvasionHud::encode,
                PacketInvasionHud::new,
                PacketInvasionHud::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketSetGuardPosition.class,
                PacketSetGuardPosition::encode,
                PacketSetGuardPosition::new,
                PacketSetGuardPosition::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketReachGuard.class,
                PacketReachGuard::encode,
                PacketReachGuard::new,
                PacketReachGuard::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketVehicleSkin.class,
                PacketVehicleSkin::encode,
                PacketVehicleSkin::new,
                PacketVehicleSkin::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketReloadVehicleSkins.class,
                PacketReloadVehicleSkins::encode,
                PacketReloadVehicleSkins::new,
                PacketReloadVehicleSkins::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSetVehicleSkin.class,
                PacketSetVehicleSkin::encode,
                PacketSetVehicleSkin::new,
                PacketSetVehicleSkin::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketOpenMiscEditor.class,
                PacketOpenMiscEditor::encode,
                PacketOpenMiscEditor::new,
                PacketOpenMiscEditor::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketUpdateVehicleClasses.class,
                PacketUpdateVehicleClasses::encode,
                PacketUpdateVehicleClasses::new,
                PacketUpdateVehicleClasses::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketTrenchNetworks.class,
                PacketTrenchNetworks::encode,
                PacketTrenchNetworks::new,
                PacketTrenchNetworks::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketEntrench.class,
                PacketEntrench::encode,
                PacketEntrench::new,
                PacketEntrench::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketRadioCommand.class,
                PacketRadioCommand::encode,
                PacketRadioCommand::new,
                PacketRadioCommand::handle
        );
    }
}
