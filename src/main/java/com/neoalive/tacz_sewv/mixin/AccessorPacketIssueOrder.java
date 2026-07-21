package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the ordered entity's network id out of SEM's order packet. */
@Mixin(PacketIssueOrder.class)
public interface AccessorPacketIssueOrder {
    @Accessor(value = "entityId", remap = false)
    int tacz_sewv$entityId();
}
