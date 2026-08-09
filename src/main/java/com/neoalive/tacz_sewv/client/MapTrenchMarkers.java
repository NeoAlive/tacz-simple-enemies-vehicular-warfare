package com.neoalive.tacz_sewv.client;

import java.util.List;

import com.neoalive.tacz_sewv.map.TrenchMarker;

/**
 * Client store for trench-network markers. Free of Xaero types so the packet handler is safe
 * without the map mod.
 */
public final class MapTrenchMarkers {

    private static List<TrenchMarker> markers = List.of();

    private MapTrenchMarkers() {}

    public static void accept(List<TrenchMarker> incoming) {
        markers = List.copyOf(incoming);
    }

    public static void clear() {
        markers = List.of();
    }

    public static List<TrenchMarker> markers() {
        return markers;
    }
}
