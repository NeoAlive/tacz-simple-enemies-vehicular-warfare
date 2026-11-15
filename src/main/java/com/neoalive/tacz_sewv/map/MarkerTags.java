package com.neoalive.tacz_sewv.map;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What groups an own unit with others on the map ribbon: its formation, its platoon, the order it
 * stands under and the batch that order came from. All zero for a unit in none, and for every
 * marker that is not OWN.
 */
public record MarkerTags(long formationGroup, int platoonId, Stance stance, long orderBatch) {

    public static final MarkerTags NONE = new MarkerTags(0L, 0, Stance.NONE, 0L);

    /** Mod-owned so SEM reordering its own order enum cannot shift the wire. */
    public enum Stance {
        NONE, FOLLOW, HOLD, MOVE, FORM, CEASE_FIRE, FREE_FIRE, ATTACK, TASK;

        private static final Stance[] VALUES = values();

        public static Stance byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }

        /** Idle: free to act on its own, no player order standing. */
        public boolean idle() {
            return this == NONE || this == FREE_FIRE;
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(this.formationGroup);
        buf.writeVarInt(this.platoonId);
        buf.writeByte(this.stance.ordinal());
        buf.writeLong(this.orderBatch);
    }

    public static MarkerTags decode(FriendlyByteBuf buf) {
        return new MarkerTags(buf.readLong(), buf.readVarInt(), Stance.byId(buf.readByte()), buf.readLong());
    }
}
