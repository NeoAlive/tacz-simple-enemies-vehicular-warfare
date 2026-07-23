package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.PatrolSupport;
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
 * Two things that both hang off a <b>player-given</b> order, which is exactly what SEM's order
 * packet is -- RU/US units are autonomous and never receive one, so this is naturally PMC-only:
 * the "orders acknowledged" radio line, and standing the crew down off any area task.
 *
 * <p><b>The stand-down is what makes an order the player just gave actually happen.</b> A patrol or
 * search area task outranks the SEM order queue in {@code VehicleTargeting.resolveDestination} --
 * while one is set the hull works its area and a MOVE_TO_POSITION or HOLD_POSITION underneath it is
 * simply never read. Clearing the task here means the last order the player issued is the one that
 * takes effect, whichever direction they issue them in (the reverse case, an area task cancelling a
 * standing order, is handled in {@code PacketPatrolVehicle}). It applies to every route a player
 * order can arrive by -- SEM's own commander menu as much as the world map's order menu -- because
 * they all come through this one packet.
 *
 * <p>Hooks SEM's handler lambda; {@code require = 0} keeps a missing/renamed lambda from crashing on
 * a future SEM build. Note what that costs if it ever does break: the radio goes quiet AND area
 * tasks stop yielding, so the silent no-op comes back. The entity is re-resolved from the packet
 * rather than captured, so the injection does not depend on SEM's internal ordering.
 */
@Mixin(PacketIssueOrder.class)
public abstract class MixinPacketIssueOrder {

    @Inject(method = "lambda$handle$0", at = @At("HEAD"), remap = false, require = 0)
    private static void tacz_sewv$orderVoice(Supplier<NetworkEvent.Context> ctx, PacketIssueOrder packet, CallbackInfo ci) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null) return;
        Entity ordered = sender.level().getEntity(((AccessorPacketIssueOrder) packet).tacz_sewv$entityId());
        if (!(ordered instanceof PmcUnitEntity pmc) || !sender.getUUID().equals(pmc.getOwnerUUID())) return;

        // Cleared for any ordered unit, mounted or not: an area task only means anything to a
        // driver, but clearing one that was never set costs nothing and never has to ask.
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null) {
            PatrolSupport.clear(pmc);
        }

        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
            CrewRadio.play(hull, CrewRadio.Line.ORDERS);
        }
    }
}
