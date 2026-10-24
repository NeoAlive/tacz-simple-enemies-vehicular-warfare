package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** One pre-packaged order pipeline fired from the quick-command wheel. */
@FunctionalInterface
public interface QuickCommandPipeline {

    void execute(ServerPlayer issuer, QuickCommandContext context);

    /**
     * Client-resolved unit ids (entity network ids); heli is resolved server-side.
     *
     * @param explicit the ids are the player's wheel selection, not a "nearby" pick — the range
     *        re-check is waived (ownership is still checked per unit, as for every TDT-era packet)
     * @param aimBlock block under the crosshair when the wheel opened, or null
     */
    record QuickCommandContext(List<Integer> unitIds, boolean explicit, @Nullable BlockPos aimBlock) {
        public QuickCommandContext {
            unitIds = List.copyOf(unitIds);
        }

        /** The unit range check for this order: {@code radius}, or unlimited for a selection. */
        public double unitRadius(double radius) {
            return this.explicit ? Double.MAX_VALUE : radius;
        }
    }
}
