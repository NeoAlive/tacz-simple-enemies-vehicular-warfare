package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.order.TargetReachability;

/**
 * Player radio GUI → server fire mission. The {@link PlaneAttackMode} rides along with the target
 * designation: it is stored back on the item so the panel remembers it, and stamped onto every CAS
 * pilot the call reaches so the aircraft flies the profile the button asked for.
 */
public class PacketRadioCommand {

    private final int frequencyOrdinal;
    private final boolean positionTarget;
    private final int targetEntityId;
    @Nullable
    private final BlockPos targetPos;
    private final int delaySeconds;
    private final int planeModeOrdinal;

    public PacketRadioCommand(RadioSettings.State settings, int targetEntityId, @Nullable BlockPos targetPos) {
        this.frequencyOrdinal = settings.frequency().ordinal();
        this.positionTarget = settings.positionTarget();
        this.targetEntityId = targetEntityId;
        this.targetPos = targetPos;
        this.delaySeconds = settings.delaySeconds();
        this.planeModeOrdinal = settings.planeMode().ordinal();
    }

    public PacketRadioCommand(FriendlyByteBuf buf) {
        this.frequencyOrdinal = buf.readVarInt();
        this.positionTarget = buf.readBoolean();
        this.targetEntityId = buf.readVarInt();
        this.targetPos = buf.readBoolean() ? buf.readBlockPos() : null;
        this.delaySeconds = buf.readVarInt();
        this.planeModeOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.frequencyOrdinal);
        buf.writeBoolean(this.positionTarget);
        buf.writeVarInt(this.targetEntityId);
        buf.writeBoolean(this.targetPos != null);
        if (this.targetPos != null) {
            buf.writeBlockPos(this.targetPos);
        }
        buf.writeVarInt(this.delaySeconds);
        buf.writeVarInt(this.planeModeOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            ItemStack radio = findRadio(player);
            if (radio.isEmpty()) {
                OrderReport.fail(player, OrderFailure.NO_RADIO);
                return;
            }

            RadioFrequency[] frequencies = RadioFrequency.values();
            RadioFrequency frequency = frequencies[
                    Math.min(Math.max(this.frequencyOrdinal, 0), frequencies.length - 1)];

            PlaneAttackMode planeMode = PlaneAttackMode.byOrdinal(this.planeModeOrdinal);

            RadioSettings.State settings = new RadioSettings.State(
                    frequency,
                    this.positionTarget && frequency.supportsPositionTarget(),
                    frequency.supportsDelay() ? Math.max(0, this.delaySeconds) : 0,
                    planeMode);
            RadioSettings.write(radio, settings);

            LivingEntity entityTarget = null;
            BlockPos posTarget = null;
            if (settings.positionTarget()) {
                posTarget = this.targetPos;
                if (posTarget == null) {
                    OrderReport.fail(player, OrderFailure.TARGET_GONE);
                    return;
                }
            } else {
                Entity entity = player.level().getEntity(this.targetEntityId);
                if (!(entity instanceof LivingEntity living) || !HandheldRadioItem.isDesignatable(entity)) {
                    OrderReport.fail(player, OrderFailure.TARGET_GONE);
                    return;
                }
                if (entity instanceof PmcUnitEntity) {
                    OrderReport.fail(player, OrderFailure.TARGET_FRIENDLY);
                    return;
                }
                entityTarget = living;
            }

            // Designation-time validation, which is the only place it can live: by the time a mortar
            // crew has the mission it has no way to answer the player, and a shell that cannot reach
            // is indistinguishable from one still in flight.
            BlockPos aimpoint = entityTarget != null ? entityTarget.blockPosition() : posTarget;
            if (TargetReachability.underground(player.level(), aimpoint)) {
                OrderReport.fail(player, OrderFailure.TARGET_UNDERGROUND);
                return;
            }
            if (frequency.directFire() && entityTarget != null
                    && FireMissionSupport.noCrewCanSee(player.level(), CrewFacts.Faction.PMC,
                            player.getUUID(), player.position(), SewvConfig.MORTAR_RADIO_RANGE.get(),
                            frequency.kinds(), entityTarget)) {
                OrderReport.fail(player, OrderFailure.TARGET_OBSTRUCTED);
                return;
            }

            FireMissionSupport.Call call = FireMissionSupport.callRadioMission(
                    player.level(), player.getUUID(), player.position(),
                    SewvConfig.MORTAR_RADIO_RANGE.get(), frequency, entityTarget, posTarget,
                    settings.delaySeconds(), settings.planeMode());

            if (call.empty()) {
                OrderReport.fail(player, OrderFailure.OUT_OF_RANGE);
                return;
            }

            var ack = FireMissionSupport.ackFor(call.kinds(), settings.planeMode());
            if (ack != null) {
                player.level().playSound(null, player, ack, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
            }

            Component targetLabel = entityTarget != null
                    ? entityTarget.getDisplayName()
                    : Component.translatable("message.tacz_sewv.radio.position_label",
                            posTarget.getX(), posTarget.getY(), posTarget.getZ());
            Component msg = Component.translatable(
                    call.ordered() == 1
                            ? "message.tacz_sewv.radio.fire_mission.single"
                            : "message.tacz_sewv.radio.fire_mission.multiple",
                    call.ordered(), targetLabel);
            NetworkHandler.sendOrderFeedback(player, msg.copy().withStyle(ChatFormatting.GREEN));
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack findRadio(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.HANDHELD_RADIO.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(ModItems.HANDHELD_RADIO.get())) return off;
        return ItemStack.EMPTY;
    }

    private static void hint(Player player, String key, ChatFormatting style) {
        player.displayClientMessage(Component.translatable(key).withStyle(style), true);
    }
}
