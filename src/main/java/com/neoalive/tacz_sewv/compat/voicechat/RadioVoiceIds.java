package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.UUID;

/**
 * Radio voice channel ids. The high half is fixed, so a client recognises a radio stream on sight; the low
 * half folds the speaker's UUID, so a client can tell who is talking by hashing the UUIDs it knows.
 */
public final class RadioVoiceIds {

    public static final long MAGIC = 0x7AC5E77A0D10C0DEL;

    private RadioVoiceIds() {}

    public static UUID of(UUID speaker) {
        return new UUID(MAGIC, speaker.getMostSignificantBits() ^ speaker.getLeastSignificantBits());
    }

    public static boolean isRadio(UUID channel) {
        return channel != null && channel.getMostSignificantBits() == MAGIC;
    }
}
