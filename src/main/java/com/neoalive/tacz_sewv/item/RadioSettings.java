package com.neoalive.tacz_sewv.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import com.neoalive.tacz_sewv.platform.StackTag;

/**
 * Handheld-radio GUI state persisted on the item stack. Entity vs grid designation is not a
 * setting: the call's raycast decides it by what it hits ({@code RadioRaycast}).
 */
public final class RadioSettings {

    private static final String TAG = "sewv:radio";

    private static final int DELAY_MIN = 20;
    private static final int DELAY_MAX = 120;
    private static final int DELAY_STEP = 10;

    private RadioSettings() {}

    public record State(RadioFrequency frequency, int delaySeconds, PlaneAttackMode planeMode) {

        public static State defaults() {
            return new State(RadioFrequency.MORTAR, 0, PlaneAttackMode.AUTO);
        }

        public State withFrequency(RadioFrequency frequency) {
            int delay = frequency.supportsDelay() ? this.delaySeconds : 0;
            return new State(frequency, delay, this.planeMode);
        }
    }

    public static State read(ItemStack stack) {
        CompoundTag root = StackTag.get(stack);
        if (root == null || !root.contains(TAG)) {
            return State.defaults();
        }
        CompoundTag tag = root.getCompound(TAG);
        RadioFrequency frequency = RadioFrequency.values()[
                Math.max(0, Math.min(tag.getInt("frequency"), RadioFrequency.values().length - 1))];
        int delay = Math.max(0, tag.getInt("delay"));
        PlaneAttackMode planeMode = PlaneAttackMode.byOrdinal(tag.getInt("plane"));
        return new State(frequency, delay, planeMode).withFrequency(frequency);
    }

    public static void write(ItemStack stack, State state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("frequency", state.frequency().ordinal());
        tag.putInt("delay", state.delaySeconds());
        tag.putInt("plane", state.planeMode().ordinal());
        StackTag.update(stack, root -> root.put(TAG, tag));
    }

    /** Delay presets cycled by the panel button: OFF then 20–120 s in 10 s steps, wrapping. */
    public static int cycleDelay(int current) {
        return current >= DELAY_MAX ? 0 : stepDelay(current, 1);
    }

    /** One step along OFF, 20, 30 … 120 s, clamped at both ends (the wheel's Ctrl+scroll). */
    public static int stepDelay(int current, int direction) {
        if (direction > 0) {
            return current <= 0 ? DELAY_MIN : Math.min(DELAY_MAX, current + DELAY_STEP);
        }
        return current <= DELAY_MIN ? 0 : current - DELAY_STEP;
    }
}
