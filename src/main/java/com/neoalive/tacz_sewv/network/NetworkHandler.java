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
import com.neoalive.tacz_sewv.order.OrderReport;

public class NetworkHandler {

    /**
     * Result of an order: {@code base + ".none"/".single"/".multiple"} by count — GRAY when nothing
     * took the order, {@code color} otherwise. Every caller passes the count of units the SERVER
     * actually accepted, never the client's optimistic guess.
     *
     * <p>The {@code .none} variant is the player's <b>only</b> notice that an order did nothing —
     * the specific reason goes to the server console, not to chat — so it is always sent.
     */
    public static void orderFeedback(Player player, String base, int count, ChatFormatting color, Object... args) {
        String key = base + (count == 0 ? ".none" : count == 1 ? ".single" : ".multiple");
        OrderReport.ok(player, Component.translatable(key, args)
                .withStyle(count == 0 ? ChatFormatting.GRAY : color));
    }

    /** Buffered until the end of the tick so it comes out beside any refusals it should be read with. */
    public static void sendOrderFeedback(Player player, Component message) {
        OrderReport.ok(player, message);
    }

    /** Straight down the wire, no buffering — {@link OrderReport}'s own flush. */
    public static void sendRaw(ServerPlayer player, Component message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketOrderFeedback(message));
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
    // 38: (removed) C→S repair-tool skin cycle — spray GUI + engineer repair only.
    // 39: Category.HELI in vehicle pool editor packets.
    // 40: misc cue/armor editor open + update packets.
    // 41: PacketVehicleSkin carries sticky RNG salt for numbered skin pools.
    // 42: PacketTrenchNetworks (trench / emplacement map markers).
    // 43: PacketEntrench (ENTRENCHED area task).
    // 44: PacketRadioCommand (handheld radio GUI fire mission).
    // 45: Category.TOW in vehicle pool editor packets.
    // 47: PacketReloadVehicleSkins carries a reset-to-jar-defaults flag.
    // 48: PacketOpenAirportGui + PacketAirportAction (PMC runway editor).
    // 50: target-priority editor open + update packets.
    // 51: the flight command carries HELI_CMD_EMERGENCY_LAND. The field layout is unchanged, but an
    //     older server reads the new value as an unknown command and parks the order on the pilot,
    //     which is a silent wrong answer rather than a parse error — exactly what this gate is for.
    // 52: PacketOwnedVehicles carries platoon colour + commander flag.
    // 53: PacketExitPlatoon; PacketToggleAutoOrders (TDT Platoon category).
    // 54: PacketJoinPlatoon (TDT Platoon category).
    // 55: PacketClearBoarding ("board my vehicle" keybind).
    // 56: team_base PMC Owner (kind/value) + online player list on open packet.
    // 57: team_base enemyTeams list on open+save packets.
    // 58: team_base open packet carries per-faction GROUND (armor) pools for autofill.
    // 59: PacketPlaneLandingDebug + PacketClearPlaneLandingDebug (sewvPlaneCombatDebug Dubins wireframe).
    // 60: PacketReviveProgress (S->C revival ring, PlayerReviveGoal/PmcReviveGoal/PmcDownedSupport).
    // 61: PacketHoldRevive (C->S hold-left-click-to-revive a downed PMC).
    // 62: PacketCaptureMedic (TDT "Capture Medic" order).
    // 67: PacketSetNameCategory (TDT "Identity" category, "Full Names" preference).
    // 68: spawn_probe open/save GUI packets.
    // 69: ConfigUI open/request/save/shortcut packets.
    // 70: PacketTowRecovery (PMC tow order).
    // 71: PacketFobData + FOB assignment/alarm/route packets.
    private static final String PROTOCOL_VERSION = "72";

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

        CHANNEL.registerMessage(
                nextId(),
                PacketTowRecovery.class,
                PacketTowRecovery::encode,
                PacketTowRecovery::new,
                PacketTowRecovery::handle
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

        CHANNEL.registerMessage(
                nextId(),
                PacketOpenAirportGui.class,
                PacketOpenAirportGui::encode,
                PacketOpenAirportGui::new,
                PacketOpenAirportGui::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketAirportAction.class,
                PacketAirportAction::encode,
                PacketAirportAction::new,
                PacketAirportAction::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketOpenTargetPriority.class,
                PacketOpenTargetPriority::encode,
                PacketOpenTargetPriority::new,
                PacketOpenTargetPriority::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketUpdateTargetPriority.class,
                PacketUpdateTargetPriority::encode,
                PacketUpdateTargetPriority::new,
                PacketUpdateTargetPriority::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketExitPlatoon.class,
                PacketExitPlatoon::encode,
                PacketExitPlatoon::new,
                PacketExitPlatoon::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketToggleAutoOrders.class,
                PacketToggleAutoOrders::encode,
                PacketToggleAutoOrders::new,
                PacketToggleAutoOrders::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketJoinPlatoon.class,
                PacketJoinPlatoon::encode,
                PacketJoinPlatoon::new,
                PacketJoinPlatoon::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketClearBoarding.class,
                PacketClearBoarding::encode,
                PacketClearBoarding::new,
                PacketClearBoarding::handle
        );

        // S->C, sewvPlaneCombatDebug wireframe (see PlaneLandingDebugClient / PlaneLandingDebugRenderer).
        CHANNEL.registerMessage(
                nextId(),
                PacketPlaneLandingDebug.class,
                PacketPlaneLandingDebug::encode,
                PacketPlaneLandingDebug::new,
                PacketPlaneLandingDebug::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketClearPlaneLandingDebug.class,
                PacketClearPlaneLandingDebug::encode,
                PacketClearPlaneLandingDebug::new,
                PacketClearPlaneLandingDebug::handle
        );

        // S->C, revival progress ring (RevivalRingOverlay).
        CHANNEL.registerMessage(
                nextId(),
                PacketReviveProgress.class,
                PacketReviveProgress::encode,
                PacketReviveProgress::new,
                PacketReviveProgress::handle
        );

        // C->S, hold-left-click-to-revive (ReviveHoldInput / PmcDownedSupport.handleHoldRevive).
        CHANNEL.registerMessage(
                nextId(),
                PacketHoldRevive.class,
                PacketHoldRevive::encode,
                PacketHoldRevive::new,
                PacketHoldRevive::handle
        );

        // C->S, TDT "Capture Medic" (PmcCaptureMedicGoal / ICaptureMedic).
        CHANNEL.registerMessage(
                nextId(),
                PacketCaptureMedic.class,
                PacketCaptureMedic::encode,
                PacketCaptureMedic::new,
                PacketCaptureMedic::handle
        );

        // C->S, TDT "Identity" category "Full Names" control (NamePreference / NpcIdentity).
        CHANNEL.registerMessage(
                nextId(),
                PacketSetNameCategory.class,
                PacketSetNameCategory::encode,
                PacketSetNameCategory::new,
                PacketSetNameCategory::handle
        );

        // S->C / C->S, spawn_probe structure-prep editor.
        CHANNEL.registerMessage(
                nextId(),
                PacketOpenSpawnProbeGui.class,
                PacketOpenSpawnProbeGui::encode,
                PacketOpenSpawnProbeGui::new,
                PacketOpenSpawnProbeGui::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSaveSpawnProbe.class,
                PacketSaveSpawnProbe::encode,
                PacketSaveSpawnProbe::new,
                PacketSaveSpawnProbe::handle
        );

        CHANNEL.registerMessage(
                nextId(),
                PacketRequestConfigUI.class,
                PacketRequestConfigUI::encode,
                PacketRequestConfigUI::new,
                PacketRequestConfigUI::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketOpenConfigUI.class,
                PacketOpenConfigUI::encode,
                PacketOpenConfigUI::new,
                PacketOpenConfigUI::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSaveConfigUI.class,
                PacketSaveConfigUI::encode,
                PacketSaveConfigUI::new,
                PacketSaveConfigUI::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketConfigShortcut.class,
                PacketConfigShortcut::encode,
                PacketConfigShortcut::new,
                PacketConfigShortcut::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketBailOutVehicle.class,
                PacketBailOutVehicle::encode,
                PacketBailOutVehicle::new,
                PacketBailOutVehicle::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketSavePreferredPathway.class,
                PacketSavePreferredPathway::encode,
                PacketSavePreferredPathway::new,
                PacketSavePreferredPathway::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketPreferredPathwaysSync.class,
                PacketPreferredPathwaysSync::encode,
                PacketPreferredPathwaysSync::new,
                PacketPreferredPathwaysSync::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketFunnelPreferredPathway.class,
                PacketFunnelPreferredPathway::encode,
                PacketFunnelPreferredPathway::new,
                PacketFunnelPreferredPathway::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketFobData.class,
                PacketFobData::encode,
                PacketFobData::new,
                PacketFobData::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketAssignFobLiving.class,
                PacketAssignFobLiving::encode,
                PacketAssignFobLiving::new,
                PacketAssignFobLiving::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketAssignFobVehicle.class,
                PacketAssignFobVehicle::encode,
                PacketAssignFobVehicle::new,
                PacketAssignFobVehicle::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketToggleFobCommand.class,
                PacketToggleFobCommand::encode,
                PacketToggleFobCommand::new,
                PacketToggleFobCommand::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketPlayFobAlarm.class,
                PacketPlayFobAlarm::encode,
                PacketPlayFobAlarm::new,
                PacketPlayFobAlarm::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                PacketRouteToFob.class,
                PacketRouteToFob::encode,
                PacketRouteToFob::new,
                PacketRouteToFob::handle
        );
    }
}
