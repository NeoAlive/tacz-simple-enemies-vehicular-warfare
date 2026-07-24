package com.neoalive.tacz_sewv.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A commandable unit's current standing order, as the MAP needs to draw it — the payload a
 * "previewOrder" overlay renders. Carried on {@link VehicleMarker} for OWN units only ({@link #NONE}
 * for everything else), and derived entirely <b>server-side</b>. That is what makes the overlay
 * self-clearing and impossible to leave stuck: when the order is dismissed or overridden, the next
 * once-a-second sync simply carries {@code NONE}, and the client holds no preview state of its own to
 * go stale. It also inherits {@code MapMarkers}' wholesale-replace + staleness handling for free.
 *
 * <p>One record covers every shape: MOVE uses {@link #target}; PATROL/SEARCH use {@code target} as
 * the area centre plus {@link #radius}; CRUISE uses {@link #route} (a loop of nodes). NONE carries
 * nothing.
 */
public record MarkerOrder(Type type, BlockPos target, int radius, List<BlockPos> route) {

    /** Guards a hostile/garbled packet from allocating an absurd route. No real cruise is this long. */
    private static final int MAX_ROUTE = 256;

    public enum Type {
        NONE, MOVE, PATROL, SEARCH, CRUISE;

        private static final Type[] VALUES = values();

        public static Type byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }
    }

    public static final MarkerOrder NONE = new MarkerOrder(Type.NONE, BlockPos.ZERO, 0, List.of());

    public static MarkerOrder move(BlockPos target) {
        return new MarkerOrder(Type.MOVE, target, 0, List.of());
    }

    /** {@code mode} must be PATROL or SEARCH. */
    public static MarkerOrder area(Type mode, BlockPos centre, int radius) {
        return new MarkerOrder(mode, centre, radius, List.of());
    }

    public static MarkerOrder cruise(List<BlockPos> route) {
        return route.isEmpty() ? NONE : new MarkerOrder(Type.CRUISE, route.get(0), 0, List.copyOf(route));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.type.ordinal());
        switch (this.type) {
            case MOVE -> buf.writeLong(this.target.asLong());
            case PATROL, SEARCH -> {
                buf.writeLong(this.target.asLong());
                buf.writeVarInt(this.radius);
            }
            case CRUISE -> {
                buf.writeVarInt(this.route.size());
                for (BlockPos p : this.route) buf.writeLong(p.asLong());
            }
            case NONE -> { }
        }
    }

    public static MarkerOrder decode(FriendlyByteBuf buf) {
        Type type = Type.byId(buf.readByte());
        return switch (type) {
            case MOVE -> move(BlockPos.of(buf.readLong()));
            case PATROL, SEARCH -> area(type, BlockPos.of(buf.readLong()), buf.readVarInt());
            case CRUISE -> {
                int n = Math.min(buf.readVarInt(), MAX_ROUTE);
                List<BlockPos> route = new ArrayList<>(n);
                for (int i = 0; i < n; i++) route.add(BlockPos.of(buf.readLong()));
                yield cruise(route);
            }
            case NONE -> NONE;
        };
    }
}
