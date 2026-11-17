package com.neoalive.tacz_sewv.map;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

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
 *
 * <p>{@link #batch} groups the units of one order so a map drag moves them together;
 * {@link #reached} marks a MOVE its unit has parked on, which hides the waypoint.
 */
public record MarkerOrder(Type type, BlockPos target, int radius, List<BlockPos> route, long batch, boolean reached) {

    /** Guards a hostile/garbled packet from allocating an absurd route. No real cruise is this long. */
    private static final int MAX_ROUTE = 256;

    public enum Type {
        NONE, MOVE, PATROL, SEARCH, CRUISE;

        private static final Type[] VALUES = values();

        public static Type byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }
    }

    public static final MarkerOrder NONE = new MarkerOrder(Type.NONE, BlockPos.ZERO, 0, List.of(), 0L, false);

    public static MarkerOrder move(BlockPos target, long batch, boolean reached) {
        return new MarkerOrder(Type.MOVE, target, 0, List.of(), batch, reached);
    }

    /** {@code mode} must be PATROL or SEARCH. */
    public static MarkerOrder area(Type mode, BlockPos centre, int radius, long batch) {
        return new MarkerOrder(mode, centre, radius, List.of(), batch, false);
    }

    public static MarkerOrder cruise(List<BlockPos> route, long batch) {
        return route.isEmpty() ? NONE
                : new MarkerOrder(Type.CRUISE, route.get(0), 0, List.copyOf(route), batch, false);
    }

    /** The same order with its point moved: target or centre, or route node {@code node} for a cruise. */
    public MarkerOrder shifted(int node, BlockPos to) {
        return switch (this.type) {
            case MOVE -> new MarkerOrder(Type.MOVE, to, 0, List.of(), this.batch, false);
            case PATROL, SEARCH -> new MarkerOrder(this.type, to, this.radius, List.of(), this.batch, false);
            case CRUISE -> {
                if (node < 0 || node >= this.route.size()) yield this;
                List<BlockPos> r = new ArrayList<>(this.route);
                r.set(node, to);
                yield cruise(r, this.batch);
            }
            case NONE -> this;
        };
    }

    /** The point a drag of this order moves: the target, or route node {@code node} for a cruise. */
    public BlockPos point(int node) {
        if (this.type == Type.CRUISE) {
            return node >= 0 && node < this.route.size() ? this.route.get(node) : this.target;
        }
        return this.target;
    }

    /** Whether two orders put their points within {@code tol} blocks of each other (horizontally). */
    public boolean near(MarkerOrder other, double tol) {
        if (other.type != this.type || other.route.size() != this.route.size()) return false;
        if (!close(this.target, other.target, tol)) return false;
        for (int i = 0; i < this.route.size(); i++) {
            if (!close(this.route.get(i), other.route.get(i), tol)) return false;
        }
        return true;
    }

    private static boolean close(BlockPos a, BlockPos b, double tol) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= tol * tol;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.type.ordinal());
        if (this.type != Type.NONE) {
            buf.writeLong(this.batch);
            buf.writeBoolean(this.reached);
        }
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
        long batch = 0L;
        boolean reached = false;
        if (type != Type.NONE) {
            batch = buf.readLong();
            reached = buf.readBoolean();
        }
        return switch (type) {
            case MOVE -> move(BlockPos.of(buf.readLong()), batch, reached);
            case PATROL, SEARCH -> area(type, BlockPos.of(buf.readLong()), buf.readVarInt(), batch);
            case CRUISE -> {
                int n = Math.min(buf.readVarInt(), MAX_ROUTE);
                List<BlockPos> route = new ArrayList<>(n);
                for (int i = 0; i < n; i++) route.add(BlockPos.of(buf.readLong()));
                yield cruise(route, batch);
            }
            case NONE -> NONE;
        };
    }
}
