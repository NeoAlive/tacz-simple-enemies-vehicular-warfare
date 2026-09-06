package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;

/** One pre-packaged order pipeline fired from the quick-command wheel. */
@FunctionalInterface
public interface QuickCommandPipeline {

    void execute(ServerPlayer issuer, QuickCommandContext context);

    /** Client-resolved board candidates (entity network ids); heli is resolved server-side. */
    record QuickCommandContext(List<Integer> unitIds) {
        public QuickCommandContext {
            unitIds = List.copyOf(unitIds);
        }
    }
}
