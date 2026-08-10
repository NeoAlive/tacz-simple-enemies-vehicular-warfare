package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Bounded unit-id lists on C→S order packets. Cruise routes already cap at 64 nodes; unit lists
 * use the same bound so a buggy or malicious client cannot flood ownership lookups.
 */
public final class PacketLists {

    public static final int MAX_UNIT_IDS = 64;

    private PacketLists() {}

    /**
     * Read a VarInt-prefixed id list, keep at most {@link #MAX_UNIT_IDS}, and skip any overflow
     * ints so trailing packet fields stay aligned.
     */
    public static List<Integer> readUnitIds(FriendlyByteBuf buf) {
        int declared = buf.readVarInt();
        if (declared < 0) declared = 0;
        int take = Math.min(declared, MAX_UNIT_IDS);
        List<Integer> ids = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            ids.add(buf.readVarInt());
        }
        for (int i = take; i < declared; i++) {
            buf.readVarInt();
        }
        return ids;
    }
}
