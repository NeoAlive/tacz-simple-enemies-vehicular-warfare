package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/** Quick vehicle formation — same shapes as the TDT FORM tab, axis = issuer facing. */
public final class QuickFormationPipeline implements QuickCommandPipeline {

    private final FormationShape shape;

    public QuickFormationPipeline(FormationShape shape) {
        this.shape = shape;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        Direction axis = Direction.fromYRot(issuer.getYRot());
        List<PmcUnitEntity> units = new ArrayList<>();
        for (int id : context.unitIds()) {
            Entity e = issuer.level().getEntity(id);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isOwnedBy(issuer) || !pmc.isAlive()) continue;
            units.add(pmc);
        }
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.formation.formed", 0,
                    ChatFormatting.RED);
            return;
        }
        int hulls = VehicleFormation.assign(issuer, units, this.shape, axis,
                PacketVehicleFormation.DEFAULT_ROW_SIZE);
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.formation.formed", hulls,
                ChatFormatting.GREEN, hulls, Component.translatable(PacketVehicleFormation.axisKey(axis)));
    }
}
