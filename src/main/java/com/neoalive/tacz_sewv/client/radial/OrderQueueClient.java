package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOrderQueue;

/**
 * Client half of the Quick Wheel order queue: the Tab toggle, the server-synced list, and the
 * BEGIN/END brackets around a queued order's packets (server side: {@code OrderQueue}).
 */
public final class OrderQueueClient {

    private static boolean mode;
    private static long toggledAtMs = Long.MIN_VALUE;
    private static boolean pickPending;
    private static List<String> labels = List.of();

    private OrderQueueClient() {}

    public static boolean isOn() {
        return mode;
    }

    /** Wall-clock time of the last toggle, for the ON/OFF flash. */
    public static long toggledAtMs() {
        return toggledAtMs;
    }

    public static List<String> labels() {
        return labels;
    }

    public static void setLabels(List<String> fromServer) {
        labels = fromServer;
    }

    /** Tab. Turning queue mode off drops everything queued. */
    public static void toggle() {
        mode = !mode;
        toggledAtMs = System.currentTimeMillis();
        if (!mode) {
            pickPending = false;
            labels = List.of();
            NetworkHandler.CHANNEL.sendToServer(new PacketOrderQueue(PacketOrderQueue.CLEAR));
        }
    }

    public static void skip() {
        NetworkHandler.CHANNEL.sendToServer(new PacketOrderQueue(PacketOrderQueue.SKIP));
    }

    /**
     * Orders that act on the player or toggle state rather than direct units fire immediately even
     * in queue mode.
     */
    public static boolean queueable(String pipelineId) {
        return switch (pipelineId) {
            case QuickCommandRegistry.ID_QUICK_CANCEL,
                 QuickCommandRegistry.ID_BOARD_QUEUED,
                 QuickCommandRegistry.ID_PLATOON_JOIN,
                 QuickCommandRegistry.ID_PLATOON_EXIT,
                 QuickCommandRegistry.ID_PLATOON_AUTO_ORDERS,
                 QuickCommandRegistry.ID_REVIVE_CALL,
                 QuickCommandRegistry.ID_RAPPEL_SELF,
                 QuickCommandRegistry.ID_RAPPEL_CREW -> false;
            default -> mode;
        };
    }

    public static void begin(String pipelineId, String label, List<Integer> units) {
        NetworkHandler.CHANNEL.sendToServer(
                new PacketOrderQueue(PacketOrderQueue.BEGIN, pipelineId, label, units));
    }

    /**
     * Close the bracket — unless the order armed a SEM pick (Move To / Attack That), whose packets
     * are sent on the pick click; then the bracket stays open until the pick resolves.
     */
    public static void endOrAwaitPick() {
        if (CommanderOverlayRenderer.isSelectingPosition || CommanderOverlayRenderer.isSelectingTarget) {
            pickPending = true;
        } else {
            end();
        }
    }

    private static void end() {
        NetworkHandler.CHANNEL.sendToServer(new PacketOrderQueue(PacketOrderQueue.END));
    }

    /** Client tick: SEM sends the pick's orders and clears its flag in the same click. */
    public static void tick() {
        if (pickPending && !CommanderOverlayRenderer.isSelectingPosition
                && !CommanderOverlayRenderer.isSelectingTarget) {
            pickPending = false;
            end();
        }
    }
}
