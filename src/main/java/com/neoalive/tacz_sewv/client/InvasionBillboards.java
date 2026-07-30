package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.invasion.InvasionBillboard;

import java.util.List;

/**
 * Client store for invasion world-space billboards. Full replacement per packet (empty clears).
 * No wall-clock expiry — same lesson as {@link MapMarkers}.
 */
public final class InvasionBillboards {

    private static List<InvasionBillboard> billboards = List.of();

    private InvasionBillboards() {}

    public static void accept(List<InvasionBillboard> incoming) {
        billboards = List.copyOf(incoming);
    }

    public static void clear() {
        billboards = List.of();
    }

    public static List<InvasionBillboard> billboards() {
        return billboards;
    }
}
