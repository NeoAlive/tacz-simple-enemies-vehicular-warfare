package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Player → server: order owned PMC helicopter pilots to run the rappel sequence
 * ({@link DriveHelicopterGoal#setRappelRequested}). Mechanism is unchanged — this only fires it.
 */
public final class PacketRappelHelicopter {

    private final List<Integer> unitIds;

    public PacketRappelHelicopter(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketRappelHelicopter(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!pmc.isOwnedBy(player)) {
                    OrderReport.fail(player, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (!(pmc.getVehicle() instanceof VehicleEntity v)) {
                    OrderReport.fail(player, OrderFailure.NOT_MOUNTED, pmc);
                    continue;
                }
                if (v.getFirstPassenger() instanceof PmcUnitEntity driver && driver.isOwnedBy(player)) {
                    pmc = driver;
                }
                // The rest of this hull's crew is in the same list; the pilot above speaks for it.
                if (v.getFirstPassenger() != pmc) continue;
                if (!HullFacts.isHelicopterHull(v)) {
                    OrderReport.fail(player, OrderFailure.WRONG_HULL, pmc);
                    continue;
                }
                // Empty cargo still accepts the order — Stage 5 exits promptly after settle.
                DriveHelicopterGoal.setForcedRappel(v);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.heli.rappel",
                    ordered, ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }
}
