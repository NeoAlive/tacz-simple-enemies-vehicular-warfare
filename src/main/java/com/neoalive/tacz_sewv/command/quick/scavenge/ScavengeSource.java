package com.neoalive.tacz_sewv.command.quick.scavenge;

import net.minecraft.core.BlockPos;

/**
 * Somewhere a unit can scavenge from. A container or body is shared (several units may draw from a
 * full chest); an item on the ground or a placed emplacement goes to one unit only.
 */
record ScavengeSource(Kind kind, BlockPos pos, int entityId) {

    enum Kind {
        CONTAINER, ITEM, BODY, HULL, MINE;

        boolean exclusive() {
            return this == ITEM || this == HULL || this == MINE;
        }
    }

    static ScavengeSource container(BlockPos pos) {
        return new ScavengeSource(Kind.CONTAINER, pos.immutable(), -1);
    }

    static ScavengeSource entity(Kind kind, BlockPos pos, int id) {
        return new ScavengeSource(kind, pos.immutable(), id);
    }

    Object key() {
        return this.kind == Kind.CONTAINER ? (Object) this.pos : (Object) this.entityId;
    }
}
