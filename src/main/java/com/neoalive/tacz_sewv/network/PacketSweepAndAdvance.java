package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.invasion.SweepAdvancement;

/**
 * Map → server Sweep &amp; Advance: chunk AABB from Xaero MapTileSelection + selected unit ids.
 */
public class PacketSweepAndAdvance {

    private final List<Integer> unitIds;
    private final ResourceLocation dim;
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    public PacketSweepAndAdvance(List<Integer> unitIds, ResourceLocation dim,
                                 int left, int top, int right, int bottom) {
        this.unitIds = unitIds;
        this.dim = dim;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public PacketSweepAndAdvance(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.dim = buf.readResourceLocation();
        this.left = buf.readVarInt();
        this.top = buf.readVarInt();
        this.right = buf.readVarInt();
        this.bottom = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeResourceLocation(this.dim);
        buf.writeVarInt(this.left);
        buf.writeVarInt(this.top);
        buf.writeVarInt(this.right);
        buf.writeVarInt(this.bottom);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (com.neoalive.tacz_sewv.invasion.InvasionOrderGate.denyIfActive(player)) return;

            if (!OpenPacCompat.isLoaded()) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.sweep.no_openpac")
                                .withStyle(ChatFormatting.RED));
                return;
            }

            SweepAdvancement.ChunkRect rect = new SweepAdvancement.ChunkRect(
                    left, top, right, bottom).normalized();
            int maxArea = SewvConfig.SWEEP_MAX_CHUNK_AREA.get();
            if (rect.area() > maxArea) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.sweep.too_large", maxArea)
                                .withStyle(ChatFormatting.RED));
                return;
            }

            ResourceKey<Level> dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, this.dim);
            if (!dimKey.equals(player.level().dimension())) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.map.wrong_dimension")
                                .withStyle(ChatFormatting.RED));
                return;
            }

            List<PmcUnitEntity> mounted = new ArrayList<>();
            List<PmcUnitEntity> onFoot = new ArrayList<>();
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) continue;
                if (!OrderAuth.check(player, pmc, "PacketSweepAndAdvance")) continue;

                if (pmc.getVehicle() instanceof VehicleEntity v
                        && v.getFirstPassenger() == pmc
                        && !(v.getEngineInfo() instanceof EngineInfo.Helicopter)) {
                    mounted.add(pmc);
                } else if (!pmc.isPassenger()) {
                    onFoot.add(pmc);
                }
            }

            if (mounted.isEmpty() && onFoot.isEmpty()) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.sweep.none")
                                .withStyle(ChatFormatting.GRAY));
                return;
            }

            SweepAdvancement.begin(player, dimKey, rect);

            for (int i = 0; i < mounted.size(); i++) {
                PmcUnitEntity pmc = mounted.get(i);
                pmc.setOrder(OrderType.FREE_FIRE);
                ((ISweepInfantry) pmc).sewv$clearInfantrySweep();
                PatrolSupport.beginSweep(pmc, rect.left(), rect.top(), rect.right(), rect.bottom(),
                        i, mounted.size());
                SweepAdvancement.addAssignee(player.getUUID(), pmc.getId());
            }
            for (PmcUnitEntity pmc : onFoot) {
                pmc.setOrder(OrderType.FREE_FIRE);
                PatrolSupport.clear(pmc);
                ((ISweepInfantry) pmc).sewv$setInfantrySweep(
                        rect.left(), rect.top(), rect.right(), rect.bottom());
                SweepAdvancement.addAssignee(player.getUUID(), pmc.getId());
            }

            SewvDiag.sweep("assigned mounted={} onFoot={} player={}",
                    mounted.size(), onFoot.size(), player.getGameProfile().getName());
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.sweep.started",
                                    mounted.size(), onFoot.size())
                            .withStyle(ChatFormatting.GREEN));
        });
        ctx.get().setPacketHandled(true);
    }
}
