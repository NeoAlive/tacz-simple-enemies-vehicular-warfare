package com.neoalive.tacz_sewv.invasion.force;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;

/**
 * A team_base's on-foot force. {@link Mode#RANDOM} is rolled once per match (by
 * {@link com.neoalive.tacz_sewv.invasion.InvasionSession}) and the base then tops up to that
 * roll, exactly as it does for vehicles.
 * <p>
 * {@link #canCapture} off does not mean passive: those riflemen still walk to the points and
 * contest them, freezing an enemy capture — they just never take a point themselves.
 */
public final class InfantrySpec {

    public static final int MAX = 64;

    public enum Mode {
        NONE, RANDOM, FIXED;

        public static Mode fromOrdinal(int ordinal) {
            Mode[] vals = values();
            return vals[Math.floorMod(ordinal, vals.length)];
        }

        public static Mode parse(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException e) {
                return NONE;
            }
        }
    }

    public Mode mode = Mode.NONE;
    public int fixed = 8;
    public int min = 4;
    public int max = 8;
    public boolean canCapture = true;

    public InfantrySpec copy() {
        InfantrySpec out = new InfantrySpec();
        out.mode = mode;
        out.fixed = fixed;
        out.min = min;
        out.max = max;
        out.canCapture = canCapture;
        return out;
    }

    /** Clamp into range. {@code min > max} is left alone — validation reports it rather than guessing. */
    public void clamp() {
        fixed = clampCount(fixed);
        min = clampCount(min);
        max = clampCount(max);
    }

    public boolean isEnabled() {
        return mode != Mode.NONE;
    }

    /** Headcount the base keeps topped up for one match; 0 when disabled. */
    public int resolveCount(RandomSource random) {
        return switch (mode) {
            case NONE -> 0;
            case FIXED -> fixed;
            case RANDOM -> {
                int lo = Math.min(min, max);
                int hi = Math.max(min, max);
                yield lo + random.nextInt(hi - lo + 1);
            }
        };
    }

    /** Smallest headcount this spec can field (for "can this base field anything?" checks). */
    public int minimumCount() {
        return switch (mode) {
            case NONE -> 0;
            case FIXED -> fixed;
            case RANDOM -> Math.min(min, max);
        };
    }

    void save(CompoundTag tag) {
        tag.putString("Mode", mode.name());
        tag.putInt("Fixed", fixed);
        tag.putInt("Min", min);
        tag.putInt("Max", max);
        tag.putBoolean("Captures", canCapture);
    }

    void load(CompoundTag tag) {
        mode = Mode.parse(tag.getString("Mode"));
        fixed = tag.getInt("Fixed");
        min = tag.getInt("Min");
        max = tag.getInt("Max");
        canCapture = !tag.contains("Captures") || tag.getBoolean("Captures");
        clamp();
    }

    void write(FriendlyByteBuf buf) {
        buf.writeVarInt(mode.ordinal());
        buf.writeVarInt(fixed);
        buf.writeVarInt(min);
        buf.writeVarInt(max);
        buf.writeBoolean(canCapture);
    }

    void read(FriendlyByteBuf buf) {
        mode = Mode.fromOrdinal(buf.readVarInt());
        fixed = buf.readVarInt();
        min = buf.readVarInt();
        max = buf.readVarInt();
        canCapture = buf.readBoolean();
        clamp();
    }

    private static int clampCount(int n) {
        return Math.max(0, Math.min(MAX, n));
    }
}
