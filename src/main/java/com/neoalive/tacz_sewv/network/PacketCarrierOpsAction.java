package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.container.ContainerBlockItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.airport.AirportRegistry;
import com.neoalive.tacz_sewv.airport.RunwaySlots;
import com.neoalive.tacz_sewv.airport.RunwayTraffic;
import com.neoalive.tacz_sewv.airport.StaticCarrierAirports;
import com.neoalive.tacz_sewv.client.CarrierOpsClient;
import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneNav;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/** Carrier plane-ops: deploy from the seated STATIC carrier GUI. */
public class PacketCarrierOpsAction {

    public static final int ACTION_DEPLOY = 1;
    public static final int ACTION_RESULT = 2;

    private final int carrierEntityId;
    private final int action;
    private final boolean ok;
    private final String messageKey;

    public PacketCarrierOpsAction(int carrierEntityId, int action) {
        this(carrierEntityId, action, false, "");
    }

    public PacketCarrierOpsAction(int carrierEntityId, int action, boolean ok, String messageKey) {
        this.carrierEntityId = carrierEntityId;
        this.action = action;
        this.ok = ok;
        this.messageKey = messageKey == null ? "" : messageKey;
    }

    public PacketCarrierOpsAction(FriendlyByteBuf buf) {
        this.carrierEntityId = buf.readVarInt();
        this.action = buf.readVarInt();
        this.ok = buf.readBoolean();
        this.messageKey = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.carrierEntityId);
        buf.writeVarInt(this.action);
        buf.writeBoolean(this.ok);
        buf.writeUtf(this.messageKey, 256);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (this.action == ACTION_RESULT) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        CarrierOpsClient.applyDeployResult(this.ok, this.messageKey));
                return;
            }
            ServerPlayer player = ctx.get().getSender();
            if (player == null || this.action != ACTION_DEPLOY) return;
            ServerLevel level = player.serverLevel();
            Entity raw = level.getEntity(this.carrierEntityId);
            NeoArmsCarrierAccess.Strip strip = NeoArmsCarrierAccess.deckStrip(raw);
            if (strip == null || !NeoArmsCarrierAccess.isStaticMode(raw)) {
                reply(player, false, "gui.tacz_sewv.carrier_ops.need_static");
                return;
            }
            if (player.getVehicle() != raw && player.distanceToSqr(raw) > 64.0 * 64.0) {
                reply(player, false, "gui.tacz_sewv.carrier_ops.too_far");
                return;
            }

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof ContainerBlockItem)) {
                reply(player, false, "gui.tacz_sewv.carrier_ops.need_container");
                return;
            }
            CompoundTag tag = BlockItem.getBlockEntityData(stack);
            if (tag == null || !tag.contains("EntityType")) {
                reply(player, false, "gui.tacz_sewv.carrier_ops.need_container");
                return;
            }
            String entityId = tag.getString("EntityType");
            AirportRegistry.Airport airport = StaticCarrierAirports.toAirport(strip);
            VehicleEntity plane = SewvConfig.DEBUG_AUTO_PLANE_DEPLOY.get()
                    ? TankSpawner.spawnPlaneWithCrew(level, airport.threshold(),
                    TankSpawner.TankFaction.PMC, player.getUUID(), entityId)
                    : TankSpawner.unpackPlane(level, airport.threshold(),
                    TankSpawner.TankFaction.PMC, entityId,
                    tag.contains("Entity") ? tag.getCompound("Entity") : null);
            if (plane == null) {
                reply(player, false, "gui.tacz_sewv.carrier_ops.not_plane");
                return;
            }
            int slot = RunwayTraffic.claim(level, airport.slots(), plane);
            RunwaySlots.Slot parking = airport.slots().slot(slot);
            float yaw = PlaneNav.yawFromBearingDeg(airport.headingDeg());
            double x = parking != null ? parking.center().getX() + 0.5 : strip.threshold().x;
            double z = parking != null ? parking.center().getZ() + 0.5 : strip.threshold().z;
            // Deck pin owns Y — parkAt would pin the plane origin to deck height and fight
            // Neo Arms' bounding-box floor snap (fall → teleport loop).
            if (!NeoArmsCarrierAccess.placeOnDeck(raw, plane, x, z, yaw)) {
                plane.moveTo(x, strip.threshold().y, z, yaw, 0.0F);
                plane.setOldPosAndRot();
                plane.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
            plane.setYRot(yaw);
            plane.yRotO = yaw;
            NeoArmsCarrierAccess.markCarrierParked(plane, true);
            DrivePlaneGoal.clearPark(plane);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            reply(player, true, "gui.tacz_sewv.carrier_ops.deploy_ok");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void reply(ServerPlayer player, boolean ok, String key) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketCarrierOpsAction(0, ACTION_RESULT, ok, key));
    }
}
