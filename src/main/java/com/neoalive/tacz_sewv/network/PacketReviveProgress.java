package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.client.RevivalRingOverlay;

/**
 * Server→client: drives {@link RevivalRingOverlay}'s progress ring on one specific player's
 * screen — the downed player during {@code PlayerReviveGoal}'s channel, the owning player during
 * {@code PmcReviveGoal}'s (their downed PMC being worked on), and the reviving player during
 * {@code PmcDownedSupport}'s own channel. {@code active=false} clears the ring (goal/channel
 * stopped, revived, or cancelled) regardless of whatever {@code progress} carries.
 */
public class PacketReviveProgress {

    private final float progress;
    private final boolean active;

    public PacketReviveProgress(float progress, boolean active) {
        this.progress = progress;
        this.active = active;
    }

    public PacketReviveProgress(FriendlyByteBuf buf) {
        this.progress = buf.readFloat();
        this.active = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(this.progress);
        buf.writeBoolean(this.active);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> RevivalRingOverlay.accept(this.progress, this.active)));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, float progress, boolean active) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketReviveProgress(progress, active));
    }
}
