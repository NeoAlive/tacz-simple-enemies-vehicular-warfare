package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.container.ContainerBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.airport.AirportRegistry;
import com.neoalive.tacz_sewv.airport.RunwaySlots;
import com.neoalive.tacz_sewv.airport.RunwayTraffic;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneNav;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/** Client → server: Check Clearance or Deploy Plane from the runway editor. */
public class PacketAirportAction {

    public static final int ACTION_CHECK = 0;
    public static final int ACTION_DEPLOY = 1;

    private final BlockPos pos;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final int action;
    private final float slotFactor;
    private final float bufferFactor;
    private final float extraFactor;

    public PacketAirportAction(BlockPos pos, int x1, int z1, int x2, int z2, int action,
                               float slotFactor, float bufferFactor, float extraFactor) {
        this.pos = pos;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.action = action;
        this.slotFactor = slotFactor;
        this.bufferFactor = bufferFactor;
        this.extraFactor = extraFactor;
    }

    public PacketAirportAction(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.x1 = buf.readVarInt();
        this.z1 = buf.readVarInt();
        this.x2 = buf.readVarInt();
        this.z2 = buf.readVarInt();
        this.action = buf.readVarInt();
        this.slotFactor = buf.readFloat();
        this.bufferFactor = buf.readFloat();
        this.extraFactor = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.x1);
        buf.writeVarInt(this.z1);
        buf.writeVarInt(this.x2);
        buf.writeVarInt(this.z2);
        buf.writeVarInt(this.action);
        buf.writeFloat(this.slotFactor);
        buf.writeFloat(this.bufferFactor);
        buf.writeFloat(this.extraFactor);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof RunwayBlockEntity be)) return;

            if (this.action == ACTION_CHECK) {
                check(player, level, be);
            } else if (this.action == ACTION_DEPLOY) {
                deploy(player, level, be);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void check(ServerPlayer player, ServerLevel level, RunwayBlockEntity be) {
        be.setCorners(this.x1, this.z1, this.x2, this.z2);
        // Clamped server-side: the sliders cannot send a bad value, but a packet can.
        be.setFactors(Mth.clamp(this.slotFactor, 0.02, 0.5),
                Mth.clamp(this.bufferFactor, 0.0, 0.2),
                Mth.clamp(this.extraFactor, 0.0, 0.5));
        be.clearClearance();
        AirportClearance.Result result = AirportClearance.check(
                level, be.getBlockPos(), be.corner1(), be.corner2(),
                AirportClearance.Rules.forRunway(this.slotFactor, this.bufferFactor,
                        this.extraFactor));
        if (result.status() == AirportClearance.Status.OK && result.touchdown() != null) {
            be.applyClearance(result);
            AirportRegistry.Airport airport = be.airport();
            int parked = airport == null ? 0 : RunwayTraffic.marshal(level, airport.slots());
            if (parked > 0) {
                player.displayClientMessage(Component.translatable(
                        "message.tacz_sewv.airport.marshalled", parked), true);
            }
        }
        reply(player, be, result.status(), result.blocker());
    }

    private void deploy(ServerPlayer player, ServerLevel level, RunwayBlockEntity be) {
        AirportRegistry.Airport airport = be.airport();
        if (!be.isCleared() || airport == null) {
            reply(player, be, AirportClearance.Status.NONE, null);
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ContainerBlockItem)) {
            reply(player, be, AirportClearance.Status.NONE, null);
            return;
        }
        CompoundTag tag = BlockItem.getBlockEntityData(stack);
        if (tag == null || !tag.contains("EntityType")) {
            reply(player, be, AirportClearance.Status.NONE, null);
            return;
        }
        String entityId = tag.getString("EntityType");
        VehicleEntity plane = TankSpawner.spawnPlaneWithCrew(
                level, airport.threshold(), TankSpawner.TankFaction.PMC, player.getUUID(), entityId);
        if (plane == null) {
            reply(player, be, AirportClearance.Status.NOT_POOLED, null);
            return;
        }
        // Park it in a slot rather than on the threshold: the threshold is slot 0, so a second
        // deploy would otherwise drop a plane on top of the first one.
        int slot = RunwayTraffic.claim(level, airport.slots(), plane);
        RunwaySlots.Slot parking = airport.slots().slot(slot);
        // The strip's heading is a compass bearing; an entity's yaw is its negation.
        float yaw = PlaneNav.yawFromBearingDeg(be.getHeadingDeg());
        if (parking != null) {
            plane.moveTo(parking.center().getX() + 0.5, parking.center().getY(),
                    parking.center().getZ() + 0.5, yaw, 0.0F);
            plane.setOldPosAndRot();
        }
        plane.setYRot(yaw);
        plane.yRotO = yaw;
        // Held on its slot until it is ordered off it, exactly like an aircraft that landed here.
        DrivePlaneGoal.parkAt(plane, plane.getX(), plane.getY(), plane.getZ(), yaw);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        reply(player, be, AirportClearance.Status.OK, null);
    }

    private static void reply(ServerPlayer player, RunwayBlockEntity be,
                              AirportClearance.Status status, @Nullable BlockPos blocker) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                PacketOpenAirportGui.result(be, status, blocker));
    }
}
