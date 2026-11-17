package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.item.PlaneRole;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Airplane Mode: a standing target filter on each fixed-wing pilot in scope. Not an order, so it
 * leaves the units' orders alone. A selected gunner or passenger stands for its pilot.
 */
public final class QuickPlaneRolePipeline implements QuickCommandPipeline {

    private final PlaneRole role;

    public QuickPlaneRolePipeline(PlaneRole role) {
        this.role = role;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        Set<PmcUnitEntity> pilots = new LinkedHashSet<>();
        List<PmcUnitEntity> scope = context.unitIds().isEmpty() && !context.explicit()
                ? allOwned(issuer, level)
                : QuickCommandUnits.owned(issuer, level, context.unitIds(),
                        context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get()));
        for (PmcUnitEntity pmc : scope) {
            if (!(pmc.getVehicle() instanceof VehicleEntity hull) || !HullFacts.isPlaneHull(hull)) continue;
            if (!(hull.getFirstPassenger() instanceof PmcUnitEntity pilot)) continue;
            if (pilot != pmc && !OrderAuth.check(issuer, pilot, "PlaneRole")) continue;
            pilots.add(pilot);
        }
        for (PmcUnitEntity pilot : pilots) {
            if (pilot instanceof IHelicopterPilot p) p.sewv$setPlaneRole(this.role);
        }
        NetworkHandler.orderFeedback(issuer, "plane_role", "message.tacz_sewv.plane_role", pilots.size(),
                ChatFormatting.AQUA, Component.translatable(roleKey(this.role)));
    }

    /** Every loaded owned unit riding a plane, wherever it is. */
    private static List<PmcUnitEntity> allOwned(ServerPlayer issuer, ServerLevel level) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (PmcUnitEntity pmc : level.getEntities(EntityTypeTest.forClass(PmcUnitEntity.class),
                u -> u.isAlive() && u.getVehicle() instanceof VehicleEntity hull && HullFacts.isPlaneHull(hull))) {
            if (PmcOwnerSupport.isOwner(issuer, pmc)) out.add(pmc);
        }
        return out;
    }

    public static String roleKey(PlaneRole role) {
        return "gui.tacz_sewv.wheel.scale.plane_role." + role.key();
    }
}
