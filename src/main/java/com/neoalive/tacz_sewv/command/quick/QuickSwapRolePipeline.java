package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/** Swap to: rearranges each unit's own inventory so the kit's item is in the main hand. */
public final class QuickSwapRolePipeline implements QuickCommandPipeline {

    private final RoleKit.Kit kit;

    public QuickSwapRolePipeline(RoleKit.Kit kit) {
        this.kit = kit;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        List<PmcUnitEntity> units = QuickCommandUnits.onFootOwned(issuer, level, context.unitIds(),
                context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get()), true);
        Component role = Component.translatable(this.kit.labelKey());

        int swapped = 0;
        int missing = 0;
        for (PmcUnitEntity pmc : units) {
            switch (RoleKit.swapTo(pmc, this.kit)) {
                case DONE, ALREADY -> swapped++;
                case NO_ITEM, BUSY -> missing++;
            }
        }
        NetworkHandler.orderFeedback(issuer, "swap_role", "message.tacz_sewv.swap_role", swapped,
                ChatFormatting.GREEN, role);
        if (missing > 0) {
            OrderReport.ok(issuer, NetworkHandler.orderLine("swap_role", missing,
                    Component.translatable("message.tacz_sewv.swap_role.missing", role)
                            .withStyle(ChatFormatting.GRAY)));
        }
    }
}
