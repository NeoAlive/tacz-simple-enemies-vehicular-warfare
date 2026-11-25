package com.neoalive.tacz_sewv.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.DoctrineClient;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.DoctrineScenarios;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * The ledger's live preview, both directions. Client to server carries drafted axes; the server scores every
 * {@link DoctrineScenarios.Scenario} against the live weights (a datapack the client may not have) and answers with
 * the action each would pick. Read-only: nothing is saved.
 */
public class PacketDoctrinePreview {

    /** Minimum server ticks between two answered requests from one player; the client debounces too. */
    private static final int MIN_INTERVAL_TICKS = 4;
    private static final Map<UUID, Integer> LAST_ANSWERED = new HashMap<>();

    private final boolean reply;
    private final int[] axes;
    private final byte[] actions;
    private final byte[] confidence;

    /** A request for these drafted axes. */
    public PacketDoctrinePreview(int[] axes) {
        this(false, axes, new byte[0], new byte[0]);
    }

    private PacketDoctrinePreview(boolean reply, int[] axes, byte[] actions, byte[] confidence) {
        this.reply = reply;
        this.axes = axes;
        this.actions = actions;
        this.confidence = confidence;
    }

    public PacketDoctrinePreview(FriendlyByteBuf buf) {
        this.reply = buf.readBoolean();
        this.axes = buf.readVarIntArray(Doctrine.Axis.VALUES.length);
        this.actions = buf.readByteArray(DoctrineScenarios.Scenario.VALUES.length);
        this.confidence = buf.readByteArray(DoctrineScenarios.Scenario.VALUES.length);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.reply);
        buf.writeVarIntArray(this.axes);
        buf.writeByteArray(this.actions);
        buf.writeByteArray(this.confidence);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (this.reply) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> DoctrineClient.acceptPreview(this.axes, this.actions, this.confidence)));
        } else {
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> answer(player));
        }
        context.setPacketHandled(true);
    }

    private void answer(ServerPlayer player) {
        int now = player.server.getTickCount();
        Integer last = LAST_ANSWERED.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_TICKS) return;
        LAST_ANSWERED.put(player.getUUID(), now);
        if (LAST_ANSWERED.size() > 512) LAST_ANSWERED.clear();

        Doctrine doctrine = Doctrine.ofAxes(this.axes);
        UtilityWeights weights = UtilityWeights.active();
        DoctrineScenarios.Scenario[] all = DoctrineScenarios.Scenario.VALUES;
        byte[] picked = new byte[all.length];
        byte[] conf = new byte[all.length];
        for (int i = 0; i < all.length; i++) {
            DoctrineScenarios.Verdict v = DoctrineScenarios.decide(weights, doctrine, all[i]);
            picked[i] = (byte) v.action().ordinal();
            conf[i] = (byte) Math.round(v.confidence());
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketDoctrinePreview(true, this.axes.clone(), picked, conf));
    }

    /** Action for an ordinal off the wire, or null when out of range. */
    public static Action action(byte ordinal) {
        int i = ordinal & 0xFF;
        return i < Action.VALUES.length ? Action.VALUES[i] : null;
    }
}
