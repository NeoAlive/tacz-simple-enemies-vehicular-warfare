package com.neoalive.tacz_sewv.client.gui;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.fpas.RadarContact;

/** The last MTI contact list the server streamed, for the open FPAS panel only. */
public final class RadarContactsClient {

    @Nullable
    private static BlockPos pos;
    private static List<RadarContact> contacts = List.of();

    private RadarContactsClient() {}

    public static void accept(BlockPos at, List<RadarContact> list) {
        pos = at;
        contacts = List.copyOf(list);
    }

    public static List<RadarContact> forPos(BlockPos at) {
        return at.equals(pos) ? contacts : List.of();
    }
}
