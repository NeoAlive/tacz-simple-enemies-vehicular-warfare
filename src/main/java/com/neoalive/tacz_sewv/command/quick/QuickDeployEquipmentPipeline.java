package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.sensor.ThreatBearing;
import com.neoalive.tacz_sewv.entity.ai.support.EquipmentDeploy;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MinefieldPlanner;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Deploy Equipment: every selected on-foot unit that carries an emplacement sets it down where it
 * stands, facing the nearest known enemy (else where the player looks). With {@code man} on, the
 * unit then crews what it placed. One item per unit per order; the item is only spent once the
 * emplacement is in the world.
 */
public final class QuickDeployEquipmentPipeline implements QuickCommandPipeline {

    private final boolean man;

    public QuickDeployEquipmentPipeline(boolean man) {
        this.man = man;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        ServerLevel level = issuer.serverLevel();
        double radius = context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get());
        Vec3 look = issuer.getLookAngle();
        float lookYaw = MinefieldPlanner.yaw(look.x, look.z);
        int placed = 0;
        int noKit = 0;
        int noGround = 0;
        for (PmcUnitEntity pmc : QuickCommandUnits.onFootOwned(issuer, level, context.unitIds(), radius)) {
            IItemHandlerModifiable inv = RoleKit.inventory(pmc);
            EquipmentDeploy.Kit kit = inv == null ? null : EquipmentDeploy.find(level, inv);
            if (kit == null) {
                noKit++;
                continue;
            }
            Vec3 threat = ThreatBearing.nearest(level, pmc, pmc.position(), SewvConfig.MINEFIELD_THREAT_RADIUS.get());
            float yaw = threat == null ? lookYaw : MinefieldPlanner.yaw(threat.x - pmc.getX(), threat.z - pmc.getZ());
            Vec3 at = EquipmentDeploy.spot(level, pmc, kit.type(), yaw);
            if (at == null) {
                noGround++;
                continue;
            }
            Entity e = EquipmentDeploy.place(level, kit, at, yaw);
            if (e == null) {
                noGround++;
                continue;
            }
            inv.extractItem(kit.slot(), 1, false);
            pmc.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            placed++;
            if (this.man) {
                OrderStandDown.clearForNewOrder(pmc, "DeployEquipment");
                EquipmentDeploy.man(pmc, e);
            }
        }
        NetworkHandler.orderFeedback(issuer, "deploy_equipment", this.man
                        ? "message.tacz_sewv.deploy_equipment.manned" : "message.tacz_sewv.deploy_equipment.placed",
                placed, ChatFormatting.GREEN, placed);
        if (noKit > 0 && placed > 0) {
            OrderReport.ok(issuer, NetworkHandler.orderLine("deploy_equipment", noKit,
                    Component.translatable("message.tacz_sewv.deploy_equipment.no_kit").withStyle(ChatFormatting.GRAY)));
        }
        if (noGround > 0) {
            OrderReport.ok(issuer, NetworkHandler.orderLine("deploy_equipment", noGround,
                    Component.translatable("message.tacz_sewv.deploy_equipment.no_ground").withStyle(ChatFormatting.GRAY)));
        }
    }
}
