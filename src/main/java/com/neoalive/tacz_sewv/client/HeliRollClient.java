package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/** Client half of {@link com.neoalive.tacz_sewv.network.PacketHeliRoll}. */
public final class HeliRollClient {

    private HeliRollClient() {}

    public static void apply(int entityId, float roll) {
        if (Minecraft.getInstance().level == null) return;
        Entity e = Minecraft.getInstance().level.getEntity(entityId);
        if (e instanceof IFlightDynamics fd) fd.sewv$setClientRoll(roll);
    }
}
