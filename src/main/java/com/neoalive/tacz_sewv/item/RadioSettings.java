package com.neoalive.tacz_sewv.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

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
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) {
            return State.defaults();
        }
        CompoundTag tag = stack.getTag().getCompound(TAG);
        RadioFrequency frequency = RadioFrequency.values()[
                Math.max(0, Math.min(tag.getInt("frequency"), RadioFrequency.values().length - 1))];
        int delay = clampDelay(tag.getInt("delay"));
        PlaneAttackMode planeMode = PlaneAttackMode.byOrdinal(tag.getInt("plane"));
        return new State(frequency, delay, planeMode).withFrequency(frequency);
    }

    public static void write(ItemStack stack, State state) {
        CompoundTag tag = stack.getOrCreateTag().getCompound(TAG);
        tag.putInt("frequency", state.frequency().ordinal());
        tag.putInt("delay", state.delaySeconds());
        tag.putInt("plane", state.planeMode().ordinal());
        stack.getOrCreateTag().put(TAG, tag);
    }

    public static final int NET_MIN = 1;
    public static final int NET_MAX = 9;

    /** Comms half of the radio: what it receives and which voice net it is tuned to. */
    public record Comms(boolean alerts, boolean chatter, boolean voice, int net) {

        public static Comms defaults() {
            return new Comms(true, true, true, NET_MIN);
        }

        public Comms withAlerts(boolean v) {
            return new Comms(v, chatter, voice, net);
        }

        public Comms withChatter(boolean v) {
            return new Comms(alerts, v, voice, net);
        }

        public Comms withVoice(boolean v) {
            return new Comms(alerts, chatter, v, net);
        }

        public Comms withNet(int n) {
            return new Comms(alerts, chatter, voice, clampNet(n));
        }

        /** Next net, wrapping 9 back to 1. */
        public Comms stepNet(int direction) {
            int span = NET_MAX - NET_MIN + 1;
            return withNet(NET_MIN + Math.floorMod(net - NET_MIN + direction, span));
        }
    }

    public static int clampNet(int n) {
        return Math.max(NET_MIN, Math.min(NET_MAX, n));
    }

    public static Comms readComms(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) return Comms.defaults();
        CompoundTag tag = stack.getTag().getCompound(TAG);
        return new Comms(
                !tag.contains("notify") || tag.getBoolean("notify"),
                !tag.contains("chatter") || tag.getBoolean("chatter"),
                !tag.contains("voice") || tag.getBoolean("voice"),
                tag.contains("net") ? clampNet(tag.getInt("net")) : NET_MIN);
    }

    public static void writeComms(ItemStack stack, Comms comms) {
        CompoundTag tag = stack.getOrCreateTag().getCompound(TAG);
        tag.putBoolean("notify", comms.alerts());
        tag.putBoolean("chatter", comms.chatter());
        tag.putBoolean("voice", comms.voice());
        tag.putInt("net", comms.net());
        stack.getOrCreateTag().put(TAG, tag);
    }

    /** OFF, or a delay inside the panel's range. */
    public static int clampDelay(int seconds) {
        return seconds <= 0 ? 0 : Math.max(DELAY_MIN, Math.min(DELAY_MAX, seconds));
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
