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
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Player radio GUI → server fire mission. {@link PlaneAttackMode} is carried on the wire and
 * stored on the item for a future CAS pass; it does not reach {@code PlaneWeapons} yet.
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
            if (radio.isEmpty()) return;

            RadioFrequency[] frequencies = RadioFrequency.values();
            RadioFrequency frequency = frequencies[
                    Math.min(Math.max(this.frequencyOrdinal, 0), frequencies.length - 1)];

            PlaneAttackMode[] planeModes = PlaneAttackMode.values();
            PlaneAttackMode planeMode = planeModes[
                    Math.min(Math.max(this.planeModeOrdinal, 0), planeModes.length - 1)];

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
                    hint(player, "message.tacz_sewv.radio.no_position", ChatFormatting.GRAY);
                    return;
                }
            } else {
                Entity entity = player.level().getEntity(this.targetEntityId);
                if (!(entity instanceof LivingEntity living) || !HandheldRadioItem.isDesignatable(entity)) {
                    hint(player, "message.tacz_sewv.radio.no_target", ChatFormatting.GRAY);
                    return;
                }
                if (entity instanceof PmcUnitEntity) {
                    hint(player, "message.tacz_sewv.radio.friendly", ChatFormatting.RED);
                    return;
                }
                entityTarget = living;
            }

            FireMissionSupport.Call call = FireMissionSupport.callRadioMission(
                    player.level(), player.getUUID(), player.position(),
                    SewvConfig.MORTAR_RADIO_RANGE.get(), frequency, entityTarget, posTarget,
                    settings.delaySeconds());

            if (call.empty()) {
                hint(player, "message.tacz_sewv.radio.no_crews", ChatFormatting.GRAY);
                return;
            }

            var ack = FireMissionSupport.ackFor(call.kinds());
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
