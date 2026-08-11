package com.neoalive.tacz_sewv.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Handheld-radio GUI state persisted on the item stack. */
public final class RadioSettings {

    private static final String TAG = "sewv:radio";

    private RadioSettings() {}

    public record State(
            RadioFrequency frequency,
            boolean positionTarget,
            int delaySeconds,
            PlaneAttackMode planeMode) {

        public static State defaults() {
            return new State(RadioFrequency.MORTAR, false, 0, PlaneAttackMode.AUTO);
        }

        public State withFrequency(RadioFrequency frequency) {
            boolean position = frequency.supportsPositionTarget() && this.positionTarget;
            int delay = frequency.supportsDelay() ? this.delaySeconds : 0;
            return new State(frequency, position, delay, this.planeMode);
        }
    }

    public static State read(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) {
            return State.defaults();
        }
        CompoundTag tag = stack.getTag().getCompound(TAG);
        RadioFrequency frequency = RadioFrequency.values()[
                MthClamp(tag.getInt("frequency"), 0, RadioFrequency.values().length - 1)];
        boolean position = tag.getBoolean("position");
        int delay = Math.max(0, tag.getInt("delay"));
        PlaneAttackMode planeMode = PlaneAttackMode.byOrdinal(tag.getInt("plane"));
        return new State(frequency, position, delay, planeMode).withFrequency(frequency);
    }

    public static void write(ItemStack stack, State state) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag tag = new CompoundTag();
        tag.putInt("frequency", state.frequency().ordinal());
        tag.putBoolean("position", state.positionTarget());
        tag.putInt("delay", state.delaySeconds());
        tag.putInt("plane", state.planeMode().ordinal());
        root.put(TAG, tag);
    }

    /** Delay presets cycled by the GUI: OFF then 20–120 s in 10 s steps. */
    public static int cycleDelay(int current) {
        if (current <= 0) return 20;
        if (current >= 120) return 0;
        return current + 10;
    }

    private static int MthClamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
