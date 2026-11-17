package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

/** Sends loopback frames back after their delay, in order, off Voice Chat's network thread. */
final class RadioVoiceEcho {

    private static volatile ScheduledExecutorService executor;

    private RadioVoiceEcho() {}

    static void schedule(VoicechatServerApi api, VoicechatConnection to, StaticSoundPacket packet, int delayMs) {
        executor().schedule(() -> {
            if (to.isConnected() && !to.isDisabled()) api.sendStaticSoundPacketTo(to, packet);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService executor() {
        ScheduledExecutorService e = executor;
        if (e != null) return e;
        synchronized (RadioVoiceEcho.class) {
            if (executor == null) {
                ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r, "sewv-radio-echo");
                    t.setDaemon(true);
                    return t;
                });
                pool.setRemoveOnCancelPolicy(true);
                executor = pool;
            }
            return executor;
        }
    }
}
