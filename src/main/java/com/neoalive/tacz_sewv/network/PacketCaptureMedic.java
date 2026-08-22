package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.invasion.InvasionOrderGate;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * TDT "Capture Medic" button: arms {@link ICaptureMedic} on each selected owned PMC, which
 * {@code PmcCaptureMedicGoal} then works whenever the unit is idle. Refused per-unit up front if no
 * medic — captured or still running — is anywhere in range, so the order does not silently
 * arm-then-immediately-clear.
 */
public class PacketCaptureMedic {

    private final List<Integer> unitIds;

    public PacketCaptureMedic(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketCaptureMedic(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;
            if (InvasionOrderGate.denyIfActive(sp)) return;
            if (!SewvConfig.MEDIC_CAPTURE_ENABLED.get()) return;

            double radius = SewvConfig.PMC_CAPTURE_MEDIC_RADIUS.get();
            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(sp, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!OrderAuth.check(sp, pmc, "PacketCaptureMedic")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (!hasMedicInRange(pmc, radius)) {
                    OrderReport.fail(sp, OrderFailure.NO_MEDIC_IN_RANGE, pmc);
                    continue;
                }
                ((ICaptureMedic) pmc).tacz_sewv$setCaptureMedicOrdered(true);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.capture_medic", ordered,
                    ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }

    private static boolean hasMedicInRange(PmcUnitEntity pmc, double radius) {
        List<AbstractUnit> candidates = pmc.level().getEntitiesOfClass(
                AbstractUnit.class,
                pmc.getBoundingBox().inflate(radius),
                candidate -> VehicleTargeting.isMedic(candidate) && candidate.isAlive());
        return !candidates.isEmpty();
    }
}
