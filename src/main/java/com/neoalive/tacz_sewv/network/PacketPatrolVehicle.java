package com.neoalive.tacz_sewv.network;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.PatrolSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Player → server patrol order for owned ground-vehicle crews. The origin is the sender's own
 * position (never trusted from the client), and the radius is clamped to the valid range before it
 * reaches {@link PatrolSupport}. Only the DRIVER of a non-helicopter hull takes it — that is the
 * unit whose drive goal reads the destination.
 */
public class PacketPatrolVehicle {

    /** The floor the TDT enforces (and the server re-checks) and the ceiling the server clamps to. */
    public static final int MIN_RADIUS = 48;
    public static final int MAX_RADIUS = 1024;

    private final List<Integer> unitIds;
    private final int radius;

    public PacketPatrolVehicle(List<Integer> unitIds, int radius) {
        this.unitIds = unitIds;
        this.radius = radius;
    }

    public PacketPatrolVehicle(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readVarInt());
        this.radius = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeVarInt(id);
        buf.writeVarInt(this.radius);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            int radius = Mth.clamp(this.radius, MIN_RADIUS, MAX_RADIUS);
            BlockPos origin = player.blockPosition();

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                // Ownership-checked per unit (a spoofed id can't task another player's crews), and
                // only the driver of a ground hull — a gunner/passenger doesn't drive, and a
                // helicopter is flown by DriveHelicopterGoal, which doesn't read patrol.
                if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)
                        && pmc.getVehicle() instanceof VehicleEntity v
                        && v.getFirstPassenger() == pmc
                        && !(v.getEngineInfo() instanceof EngineInfo.Helicopter)) {
                    PatrolSupport.begin(pmc, origin, radius);
                    ordered++;
                }
            }

            if (SewvConfig.SHOW_ORDER_FEEDBACK.get()) {
                Component msg;
                if (ordered == 0) {
                    msg = Component.translatable("message.tacz_sewv.patrol.ordered.none");
                } else if (ordered == 1) {
                    msg = Component.translatable("message.tacz_sewv.patrol.ordered.single", ordered, radius);
                } else {
                    msg = Component.translatable("message.tacz_sewv.patrol.ordered.multiple", ordered, radius);
                }
                player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GREEN), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
