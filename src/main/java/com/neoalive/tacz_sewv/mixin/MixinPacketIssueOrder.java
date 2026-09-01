package com.neoalive.tacz_sewv.mixin;

import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

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

    @Inject(method = "lambda$handle$0", at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private static void tacz_sewv$orderVoice(Supplier<NetworkEvent.Context> ctx, PacketIssueOrder packet, CallbackInfo ci) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null) return;
        Entity ordered = sender.level().getEntity(((AccessorPacketIssueOrder) packet).tacz_sewv$entityId());
        // SEM drops an order it does not like and sends no reply, so these two are the whole reason
        // the client-side "order sent" ack had to go: it was printed before either was checked.
        if (!(ordered instanceof PmcUnitEntity pmc)) {
            OrderReport.fail(sender, OrderFailure.NOT_A_UNIT);
            return;
        }
        if (!OrderAuth.check(sender, pmc, "PacketIssueOrder")) {
            OrderReport.fail(sender, OrderFailure.NOT_OWNED);
            return;
        }
        if (FobSupport.blocksOrders(pmc)) {
            OrderReport.fail(sender, OrderFailure.FOB_COMMAND);
            ci.cancel();
            return;
        }
        // SEM's packet is one unit per send, so a section arrives as several packets in one tick;
        // okEach counts them and the flush prints the total once.
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) {
            OrderReport.fail(sender, OrderFailure.UNIT_DOWNED);
            ci.cancel();
            return;
        }
        OrderReport.okEach(sender, "message.tacz_sewv.tdt.order", ChatFormatting.GREEN);

        // Cleared for any ordered unit, mounted or not: an area task only means anything to a
        // driver, but clearing one that was never set costs nothing and never has to ask.
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                || ((ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
            PatrolSupport.clearSweepMembership(pmc, "PacketIssueOrder");
        }
        EntrenchSupport.clear(pmc);
        // Any player SEM order cancels an in-flight REACH promote (FREE_FIRE included).
        GuardSupport.clearReach(pmc);
        // EscortGoal is priority 1 MOVE and ignores the SEM order — an uncleared VIP steals
        // MOVE_TO_POSITION / FOLLOW / HOLD until the escort id is dropped.
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(pmc);
        // Capture pipeline outranks SEM orders in resolveDestination — drop it so the order sticks.
        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }
        ((IPathwayInfantry) pmc).sewv$clearPathway();

        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
            CrewRadio.play(hull, CrewRadio.Line.ORDERS);
        }
    }
}
