package com.neoalive.tacz_sewv.client.fpas;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Run with {@code ./gradlew selfCheckFpasPose}. Clip sampling and the pose machine, no world. */
public final class FpasPoseSelfCheck {

    private static final String[] CLIP_BONES = {"dish", "hydraulicjacks", "gunmetal"};

    private FpasPoseSelfCheck() {}

    public static void main(String[] args) throws Exception {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");

        Map<String, BedrockClip> clips;
        try (Reader r = new InputStreamReader(Objects.requireNonNull(FpasPoseSelfCheck.class.getResourceAsStream(
                "/assets/tacz_sewv/sewv/fpas.animation.json")), StandardCharsets.UTF_8)) {
            clips = BedrockClip.parse(r);
        }
        BedrockClip open = clips.get("transitionopen"), close = clips.get("transitionclose"), spin = clips.get("spin");
        assert open != null && close != null && spin != null : clips.keySet();

        // length is the last key, not animation_length
        assert near(open.length(), 4.6667F, 1e-3F) : open.length();
        assert near(close.length(), 3.9583F, 1e-3F) : close.length();

        // close ends where open starts, for every bone either drives
        for (String bone : CLIP_BONES) {
            for (BedrockClip.Channel ch : BedrockClip.Channel.values()) {
                float[] a = close.sample(bone, ch, close.length());
                float[] b = open.sample(bone, ch, 0.0F);
                assert (a == null) == (b == null) : bone + " " + ch;
                if (a != null) assert vecNear(a, b, 1e-3F) : bone + " " + ch;
                float[] o = open.sample(bone, ch, open.length());
                float[] c = close.sample(bone, ch, 0.0F);
                if (o != null) assert vecNear(o, c, 1e-3F) : bone + " " + ch + " open end";
            }
        }
        // gunmetal holds its first key before it
        assert near(open.sample("gunmetal", BedrockClip.Channel.POSITION, 1.0F)[1], -28.0F, 1e-4F);
        // catmullrom passes through its keys
        assert near(close.sample("dish", BedrockClip.Channel.ROTATION, 3.9583F)[0], -67.5F, 1e-3F);

        float spinRate = -spin.sample("radar", BedrockClip.Channel.ROTATION, spin.length())[1] / (spin.length() * 20.0F);
        assert near(spinRate, 1.7925F, 1e-3F) : spinRate;

        float openTicks = open.length() * 20.0F, closeTicks = close.length() * 20.0F;

        // steady start: no replayed open
        assert new FpasPose(openTicks, closeTicks, spinRate, true).phase() == FpasPose.Phase.ACTIVE;
        assert new FpasPose(openTicks, closeTicks, spinRate, false).phase() == FpasPose.Phase.CLOSED;

        // spin up, track, lose contact, power off, power back mid-stow, power off, close
        FpasPose p = new FpasPose(openTicks, closeTicks, spinRate, true);
        Tracker t = new Tracker(p);
        t.run(true, Float.NaN, 200);
        assert near(p.azVel(), spinRate, 1e-4F) : p.azVel();
        t.run(true, 120.0F, 200);
        assert near(FpasPose.wrap(p.azimuth() - 120.0F), 0.0F, 0.5F) : p.azimuth();
        t.run(true, Float.NaN, 100);
        t.run(false, Float.NaN, 20);
        assert p.phase() == FpasPose.Phase.STOWING;
        t.run(true, Float.NaN, 30);
        assert p.phase() == FpasPose.Phase.ACTIVE;
        t.run(true, Float.NaN, 60);
        boolean sawClosing = false;
        for (int i = 0; i < 600; i++) {
            FpasPose.Phase before = p.phase();
            t.run(false, Float.NaN, 1);
            if (before != FpasPose.Phase.CLOSING && p.phase() == FpasPose.Phase.CLOSING) {
                sawClosing = true;
                assert near(p.azimuth(), 0.0F, 1e-4F) && p.azVel() == 0.0F : p.azimuth();
            }
            if (p.phase() == FpasPose.Phase.STOWING) assert p.clipTime() == 0.0F;
        }
        assert sawClosing && p.phase() == FpasPose.Phase.CLOSED : p.phase();

        // spinning forward never reverses on the way to stow
        FpasPose fwd = new FpasPose(openTicks, closeTicks, spinRate, true);
        fwd.step(true, Float.NaN, 137);
        fwd.step(false, Float.NaN, 1);
        while (fwd.phase() == FpasPose.Phase.STOWING) {
            assert fwd.azVel() > -0.01F : fwd.azVel();
            fwd.step(false, Float.NaN, 1);
        }

        // reversals keep the clip pose continuous
        FpasPose r = new FpasPose(openTicks, closeTicks, spinRate, false);
        float prevDish = Float.NaN;
        for (int i = 0; i < 400; i++) {
            boolean powered = i < 40 || (i >= 120 && i < 150);
            r.step(powered, Float.NaN, 1);
            float dish = dishX(r, open, close);
            if (!Float.isNaN(prevDish)) assert Math.abs(dish - prevDish) < 3.0F : i + " " + prevDish + " " + dish;
            prevDish = dish;
        }
        assert r.phase() == FpasPose.Phase.CLOSED : r.phase();

        FpasPose c = new FpasPose(openTicks, closeTicks, spinRate, true);
        prevDish = 0.0F;
        boolean sawReverseClose = false;
        int closingFor = 0;
        for (int i = 0; i < 1200; i++) {
            if (c.phase() == FpasPose.Phase.CLOSING) closingFor++;
            boolean powered = i < 40 || closingFor >= 30;
            FpasPose.Phase before = c.phase();
            c.step(powered, Float.NaN, 1);
            if (before == FpasPose.Phase.CLOSING && c.phase() == FpasPose.Phase.ACTIVE) sawReverseClose = true;
            float dish = dishX(c, open, close);
            assert Math.abs(dish - prevDish) < 3.0F : i + " " + prevDish + " " + dish;
            prevDish = dish;
        }
        assert sawReverseClose;

        // partial-tick steps add up to whole ones
        FpasPose whole = new FpasPose(openTicks, closeTicks, spinRate, false);
        FpasPose parts = new FpasPose(openTicks, closeTicks, spinRate, false);
        whole.step(true, Float.NaN, 30);
        for (int i = 0; i < 30; i++) parts.step(true, Float.NaN, 1);
        assert near(whole.clipTime(), parts.clipTime(), 1e-3F);

        System.out.println("FpasPoseSelfCheck OK");
    }

    /** Asserts the servo never changes velocity faster than its acceleration limit. */
    private static final class Tracker {
        private final FpasPose p;

        Tracker(FpasPose p) {
            this.p = p;
        }

        void run(boolean powered, float bearing, int ticks) {
            for (int i = 0; i < ticks; i++) {
                float v = this.p.azVel();
                this.p.step(powered, bearing, 1);
                assert Math.abs(this.p.azVel() - v) <= FpasPose.AZ_ACCEL + 1e-4F
                        || this.p.phase() == FpasPose.Phase.CLOSING : v + " -> " + this.p.azVel();
            }
        }
    }

    private static float dishX(FpasPose p, BedrockClip open, BedrockClip close) {
        return switch (p.phase()) {
            case OPENING -> open.sample("dish", BedrockClip.Channel.ROTATION, p.clipTime() / 20.0F)[0];
            case CLOSING -> close.sample("dish", BedrockClip.Channel.ROTATION, p.clipTime() / 20.0F)[0];
            case CLOSED -> close.sample("dish", BedrockClip.Channel.ROTATION, close.length())[0];
            default -> 0.0F;
        };
    }

    private static boolean near(float a, float b, float eps) {
        return Math.abs(a - b) <= eps;
    }

    private static boolean vecNear(float[] a, float[] b, float eps) {
        for (int i = 0; i < 3; i++) if (!near(a[i], b[i], eps)) return false;
        return true;
    }
}
