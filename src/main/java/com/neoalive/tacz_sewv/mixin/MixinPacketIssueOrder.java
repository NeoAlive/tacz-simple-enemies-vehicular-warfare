package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.CrewRadio;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * The "orders acknowledged" radio line. It plays only for a <b>player-given</b> order, which is
 * exactly what SEM's order packet is -- RU/US units are autonomous and never receive one, so this is
 * naturally PMC-only. Fires when the ordered unit is the driver of a hull the sender owns.
 *
 * <p>Hooks SEM's handler lambda; {@code require = 0} keeps a missing/renamed lambda from crashing a
 * cosmetic feature on a future SEM build (it just goes quiet). The entity is re-resolved from the
 * packet rather than captured, so the injection does not depend on SEM's internal ordering.
 */
@Mixin(PacketIssueOrder.class)
public abstract class MixinPacketIssueOrder {

    @Inject(method = "lambda$handle$0", at = @At("HEAD"), remap = false, require = 0)
    private static void tacz_sewv$orderVoice(Supplier<NetworkEvent.Context> ctx, PacketIssueOrder packet, CallbackInfo ci) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null) return;
        Entity ordered = sender.level().getEntity(((AccessorPacketIssueOrder) packet).tacz_sewv$entityId());
        if (ordered instanceof PmcUnitEntity pmc
                && pmc.getVehicle() instanceof VehicleEntity hull
                && hull.getFirstPassenger() == pmc
                && sender.getUUID().equals(pmc.getOwnerUUID())) {
            CrewRadio.play(hull, CrewRadio.Line.ORDERS);
        }
    }
}
