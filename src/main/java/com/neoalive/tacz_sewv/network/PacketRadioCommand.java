package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.order.RadioRaycast;
import com.neoalive.tacz_sewv.order.TargetReachability;
import com.neoalive.tacz_sewv.util.ImpactTickets;

/**
 * Radio call (panel CALL or a wheel Support leaf) → server fire mission. The client sends only
 * its VIEW; {@link RadioRaycast} resolves it here, and what it hits picks the mission: an entity
 * becomes an entity mission, a block a grid mission. The {@link PlaneAttackMode} rides along, is
 * stored back on the radio, and is stamped onto every CAS pilot the call reaches.
 */
public class PacketRadioCommand {

    /** How long a far impact chunk stays loaded after the call, on top of any mortar delay. */
    private static final long IMPACT_HOLD_TICKS = 90L * 20L;

    private final int frequencyOrdinal;
    private final int delaySeconds;
    private final int planeModeOrdinal;
    private final float yaw;
    private final float pitch;

    public PacketRadioCommand(RadioSettings.State settings, float yaw, float pitch) {
        this.frequencyOrdinal = settings.frequency().ordinal();
        this.delaySeconds = settings.delaySeconds();
        this.planeModeOrdinal = settings.planeMode().ordinal();
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public PacketRadioCommand(FriendlyByteBuf buf) {
        this.frequencyOrdinal = buf.readVarInt();
        this.delaySeconds = buf.readVarInt();
        this.planeModeOrdinal = buf.readVarInt();
        this.yaw = buf.readFloat();
        this.pitch = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.frequencyOrdinal);
        buf.writeVarInt(this.delaySeconds);
        buf.writeVarInt(this.planeModeOrdinal);
        buf.writeFloat(this.yaw);
        buf.writeFloat(this.pitch);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!HandheldRadioItem.canCall(player)) {
                OrderReport.fail(player, OrderFailure.NO_RADIO);
                return;
            }

            RadioFrequency[] frequencies = RadioFrequency.values();
            RadioFrequency frequency = frequencies[
                    Math.min(Math.max(this.frequencyOrdinal, 0), frequencies.length - 1)];
            RadioSettings.State settings = new RadioSettings.State(
                    frequency,
                    frequency.supportsDelay() ? Math.max(0, this.delaySeconds) : 0,
                    PlaneAttackMode.byOrdinal(this.planeModeOrdinal));
            ItemStack radio = HandheldRadioItem.findCarried(player);
            if (!radio.isEmpty()) {
                RadioSettings.write(radio, settings);
            }

            double range = EasyMode.mortarRadioRange();
            RadioRaycast.Hit hit = RadioRaycast.resolve(player, this.yaw, this.pitch, range);
            if (hit.friendly()) {
                OrderReport.fail(player, OrderFailure.TARGET_FRIENDLY);
                return;
            }
            LivingEntity entityTarget = hit.entity();
            BlockPos posTarget = entityTarget == null ? hit.pos() : null;
            if (entityTarget == null && posTarget == null) {
                hint(player, "message.tacz_sewv.radio.no_target");
                return;
            }
            if (posTarget != null && !frequency.supportsPositionTarget()) {
                hint(player, "message.tacz_sewv.radio.needs_entity");
                return;
            }

            // Designation-time validation, which is the only place it can live: by the time a mortar
            // crew has the mission it has no way to answer the player, and a shell that cannot reach
            // is indistinguishable from one still in flight. An estimated point is on the generator
            // surface by construction — and testing it would load the chunk.
            BlockPos aimpoint = entityTarget != null ? entityTarget.blockPosition() : posTarget;
            if (!hit.estimated() && TargetReachability.underground(player.level(), aimpoint)) {
                OrderReport.fail(player, OrderFailure.TARGET_UNDERGROUND);
                return;
            }
            if (frequency.directFire() && entityTarget != null
                    && FireMissionSupport.noCrewCanSee(player.level(), CrewFacts.Faction.PMC,
                            player.getUUID(), player.position(), range,
                            frequency.kinds(), entityTarget)) {
                OrderReport.fail(player, OrderFailure.TARGET_OBSTRUCTED);
                return;
            }

            FireMissionSupport.Call call = FireMissionSupport.callRadioMission(
                    player.level(), player.getUUID(), player.position(),
                    range, frequency, entityTarget, posTarget,
                    settings.delaySeconds(), settings.planeMode());

            if (call.empty()) {
                OrderReport.fail(player, OrderFailure.OUT_OF_RANGE);
                return;
            }

            if (SewvConfig.MORTAR_CHUNK_LOADING.get()) {
                ServerLevel level = player.serverLevel();
                ImpactTickets.hold(level, new ChunkPos(
                                SectionPos.blockToSectionCoord(aimpoint.getX()),
                                SectionPos.blockToSectionCoord(aimpoint.getZ())),
                        level.getGameTime() + IMPACT_HOLD_TICKS + settings.delaySeconds() * 20L);
            }

            var ack = FireMissionSupport.ackFor(call.kinds(), settings.planeMode());
            if (ack != null) {
                player.level().playSound(null, player, ack, net.minecraft.sounds.SoundSource.VOICE, 1.8F, 1.0F);
            }

            Component targetLabel = entityTarget != null
                    ? entityTarget.getDisplayName()
                    : Component.translatable("message.tacz_sewv.radio.position_label",
                            posTarget.getX(), posTarget.getY(), posTarget.getZ());
            NetworkHandler.orderFeedback(player, "fire_mission", "message.tacz_sewv.radio.fire_mission",
                    call.ordered(), ChatFormatting.GREEN, call.ordered(), targetLabel);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
