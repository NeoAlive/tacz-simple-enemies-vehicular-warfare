package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Search equipment: units already carrying the kit just swap it in, the rest walk to the nearest
 * unclaimed one on the ground or in a container and equip it ({@link GearSearchTracker}).
 */
public final class QuickSearchGearPipeline implements QuickCommandPipeline {

    private final RoleKit.Kit kit;

    public QuickSearchGearPipeline(RoleKit.Kit kit) {
        this.kit = kit;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        GearSearchTracker.cancelPlayer(issuer.getUUID(), level);

        List<PmcUnitEntity> units = QuickCommandUnits.onFootOwned(issuer, level, context.unitIds(),
                context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get()), true);
        GearSearchTracker.Run run = GearSearchTracker.newRun(issuer, this.kit);
        for (PmcUnitEntity pmc : units) {
            if (RoleKit.holds(pmc, this.kit)) {
                run.equipped++;
                continue;
            }
            if (RoleKit.carries(pmc, this.kit)) {
                RoleKit.Result r = RoleKit.swapTo(pmc, this.kit);
                if (r == RoleKit.Result.DONE || r == RoleKit.Result.ALREADY) {
                    run.equipped++;
                } else {
                    run.missing++;
                }
                continue;
            }
            GearSearchTracker.Target target = GearSearchTracker.findTarget(level, issuer.getUUID(), pmc,
                    this.kit, run);
            if (target == null) {
                run.missing++;
                continue;
            }
            OrderStandDown.clearForNewOrder(pmc, "SearchGear");
            GearSearchTracker.addLeg(run, pmc, target);
        }

        if (run.legs.isEmpty()) {
            GearSearchTracker.report(issuer, run);
            return;
        }
        GearSearchTracker.start(run, level.getGameTime());
        NetworkHandler.orderFeedback(issuer, "search_gear", "message.tacz_sewv.search_gear.started",
                run.legs.size(), ChatFormatting.GREEN, Component.translatable(this.kit.labelKey()));
    }
}
