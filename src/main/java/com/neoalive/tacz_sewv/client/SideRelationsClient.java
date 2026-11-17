package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;

/**
 * The local player's side relations. Unknown until {@link com.neoalive.tacz_sewv.network.PacketSideRelations}
 * arrives; callers treat unknown as not an alliance.
 */
public final class SideRelationsClient {

    private static boolean known;
    private static DiplomacyData.Relation ru = DiplomacyData.Relation.NEUTRAL;
    private static DiplomacyData.Relation us = DiplomacyData.Relation.NEUTRAL;

    private SideRelationsClient() {}

    public static void clear() {
        known = false;
        ru = DiplomacyData.Relation.NEUTRAL;
        us = DiplomacyData.Relation.NEUTRAL;
    }

    public static void accept(DiplomacyData.Relation ru, DiplomacyData.Relation us) {
        known = true;
        SideRelationsClient.ru = ru;
        SideRelationsClient.us = us;
    }

    public static boolean known() {
        return known;
    }

    /** Null until the server has said. A received {@link DiplomacyData.Relation#NEUTRAL} is not null. */
    @Nullable
    public static DiplomacyData.Relation ru() {
        return known ? ru : null;
    }

    @Nullable
    public static DiplomacyData.Relation us() {
        return known ? us : null;
    }
}
