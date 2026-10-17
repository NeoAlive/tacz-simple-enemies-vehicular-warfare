package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.map.FobMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketRouteToFob;

/** Client-side eligibility + send for Route Vehicles to FOB (map / TDT). */
public final class FobClientRoute {

    private FobClientRoute() {}

    public static boolean canRoute() {
        FobMarker fob = MapMarkers.fobMarker();
        return fob != null && fob.routeReady();
    }

    public static void sendRoute() {
        FobMarker fob = MapMarkers.fobMarker();
        if (fob == null || !fob.routeReady()) return;
        NetworkHandler.CHANNEL.sendToServer(new PacketRouteToFob(
                fob.commandPos(), fob.commandPos(), FobGuiSnapshot.GuiKind.COMMAND));
    }
}
