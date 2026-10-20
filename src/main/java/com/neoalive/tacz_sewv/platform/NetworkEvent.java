package com.neoalive.tacz_sewv.platform;

import javax.annotation.Nullable;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Stand-in for Forge's {@code NetworkEvent}: only {@link Context}, with the four calls this mod's
 * packet handlers make ({@code enqueueWork}, {@code getSender}, {@code getDirection},
 * {@code setPacketHandled}), backed by NeoForge's {@link IPayloadContext}.
 */
public final class NetworkEvent {

    private NetworkEvent() {}

    public static final class Context {
        private final IPayloadContext payload;

        Context(IPayloadContext payload) {
            this.payload = payload;
        }

        /** Runs {@code work} on the main thread. Handlers run on the network thread, as on Forge. */
        public void enqueueWork(Runnable work) {
            payload.enqueueWork(work);
        }

        /** The sending player for a client-to-server packet; null on the client. */
        @Nullable
        public ServerPlayer getSender() {
            return payload.player() instanceof ServerPlayer sp ? sp : null;
        }

        public NetworkDirection getDirection() {
            return payload.flow() == PacketFlow.SERVERBOUND ? NetworkDirection.PLAY_TO_SERVER : NetworkDirection.PLAY_TO_CLIENT;
        }

        /** No-op: NeoForge has no "unhandled" state to report. Kept so handlers read as on main. */
        public void setPacketHandled(boolean handled) {
        }

        /** The underlying NeoForge context, for code written against this branch only. */
        public IPayloadContext payload() {
            return payload;
        }
    }

    public enum NetworkDirection {
        PLAY_TO_SERVER(LogicalSide.SERVER),
        PLAY_TO_CLIENT(LogicalSide.CLIENT);

        private final LogicalSide receptionSide;

        NetworkDirection(LogicalSide receptionSide) {
            this.receptionSide = receptionSide;
        }

        public LogicalSide getReceptionSide() {
            return receptionSide;
        }
    }
}
