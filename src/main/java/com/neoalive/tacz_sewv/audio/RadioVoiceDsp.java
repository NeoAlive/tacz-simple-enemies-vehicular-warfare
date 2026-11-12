package com.neoalive.tacz_sewv.audio;

/**
 * Streaming port of {@code radioize_intercom.py} (intercom preset, intelligibility 0.5) for live voice.
 * The offline script sees the whole file, so every percentile normalisation there is a running envelope
 * here, the gate holds only forward, and the 4-band drift (about 0.9 dB at this preset) is left out.
 * Everything else keeps the script's stage order and numbers.
 *
 * <p>Per-sample state only, so the output does not depend on how the input is chunked. No Minecraft or
 * Voice Chat types: the self-check and the render tool run it headless.
 */
public final class RadioVoiceDsp {

    public static final int DEFAULT_RATE = 48000;

    private static final double HPF = 330, LPF = 3000;
    private static final double RESONANCE = 0.4, DRIVE = 2.2, CLIP = 0.1, RATIO = 5, THRESHOLD = -24;
    private static final double BROKEN = 0.1, FLUTTER = 0.2, NOISE_DB = -46, AGC_PUMP = 0.2;
    private static final double GATE_RANGE = 30, GATE_FLOOR = undb(-50);
    private static final double NOISE_BASE = undb(NOISE_DB);
    /** Keeps the IIR states out of subnormal range in silence, where they would cost many times more. */
    private static final double DENORMAL_GUARD = 1e-18;
    private static final double CEILING = undb(-0.5);
    private static final double SCRIPT_RATE = 22050;
    private static final double REF_DECAY_DB_PER_S = 6;

    private final double sr;
    private final long seed;
    private final double[][] resonances = new double[4][3];
    private final double combDelay;
    private final double amHz;

    private Rng rng;
    private double dcX, dcY;
    private double guard = DENORMAL_GUARD;
    private final double[] flutterBuf = new double[1024];
    private int flutterPos;
    private Smooth lfoSlow, lfoFast, burstLfo, amLfo, noiseModSlow, noiseModFast;
    private Chain band, speaker, distortFilters, post, noiseBand, limitFilters, crackleBand;
    private final double[] combBuf = new double[512];
    private int combPos;
    private Follower norm1, norm2, norm3;

    private final int compHop, gateHop;
    private double compMsq, compRef, compGr, compGain, compGainStep;
    private int compCount;
    private double gateMsq, gateRef, gateEnv, gateGain, gateGainStep;
    private int gateCount, gateHold;
    private final double compMsqCoef, gateMsqCoef, compAtt, compRel, gateAtt, gateRel, refDecayComp, refDecayGate;
    private final int gateHoldHops;

    private double amSin, amCos;
    private final double amRotSin, amRotCos;
    private int fadeLeft, fadeLen;
    private double fadeDepth;
    private int dropLeft, dropLen;
    private final double noiseGain;

    public RadioVoiceDsp(long seed) {
        this(seed, DEFAULT_RATE);
    }

    public RadioVoiceDsp(long seed, double sampleRate) {
        this.sr = sampleRate;
        this.seed = seed;
        Rng voice = new Rng(seed ^ 0x5EEDL);
        for (double[] r : this.resonances) {
            r[0] = voice.uniform(650, 2500);
            r[1] = voice.uniform(-7, 9) * RESONANCE;
            r[2] = voice.uniform(3, 9);
        }
        this.combDelay = voice.uniform(0.0004, 0.0009) * sampleRate;
        this.amHz = voice.uniform(30, 60);
        this.amRotSin = Math.sin(2 * Math.PI * this.amHz / sampleRate);
        this.amRotCos = Math.cos(2 * Math.PI * this.amHz / sampleRate);
        this.compHop = 16;
        this.gateHop = 32;
        double compFs = sampleRate / compHop, gateFs = sampleRate / gateHop;
        this.compMsqCoef = Math.exp(-1 / (0.002 * sampleRate));
        this.gateMsqCoef = Math.exp(-1 / (0.010 * sampleRate));
        this.compAtt = coef(2, compFs);
        this.compRel = coef(60, compFs);
        this.gateAtt = coef(1.5, gateFs);
        this.gateRel = coef(60, gateFs);
        this.refDecayComp = REF_DECAY_DB_PER_S * compHop / sampleRate;
        this.refDecayGate = REF_DECAY_DB_PER_S * gateHop / sampleRate;
        this.gateHoldHops = (int) Math.ceil(0.045 * gateFs);
        this.noiseGain = Math.sqrt(sampleRate / SCRIPT_RATE);
        reset();
    }

    /** Back to the start of a transmission. The speaker's voice (its resonances) is kept. */
    public void reset() {
        this.rng = new Rng(this.seed);
        this.dcX = this.dcY = 0;
        this.guard = DENORMAL_GUARD;
        java.util.Arrays.fill(this.flutterBuf, 0);
        java.util.Arrays.fill(this.combBuf, 0);
        this.flutterPos = this.combPos = 0;
        this.lfoSlow = new Smooth(0.7);
        this.lfoFast = new Smooth(9.0);
        this.burstLfo = new Smooth(2.5);
        this.amLfo = new Smooth(2.0);
        this.noiseModSlow = new Smooth(1.5);
        this.noiseModFast = new Smooth(12);

        this.band = Chain.of(Chain.butter(false, 6, HPF, sr), Chain.butter(true, 8, LPF, sr),
                new Biquad[] {Biquad.peaking(sr, 440, -9, 0.9), Biquad.peaking(sr, 800, 3, 1.0)});
        Biquad[] spk = new Biquad[6];
        spk[0] = Biquad.peaking(sr, 1000, 5, 0.9);
        spk[1] = Biquad.peaking(sr, 1400, 3, 1.5);
        for (int i = 0; i < 4; i++) spk[2 + i] = Biquad.peaking(sr, resonances[i][0], resonances[i][1], resonances[i][2]);
        this.speaker = Chain.of(spk);
        this.distortFilters = Chain.of(Chain.butter(false, 4, HPF, sr), Chain.butter(true, 6, LPF * 1.15, sr));
        this.post = Chain.of(Biquad.peaking(sr, 420, -7, 0.9), Biquad.peaking(sr, 950, 2.5, 1.2));
        this.noiseBand = Chain.of(Chain.butter(false, 2, Math.max(200, HPF * 0.8), sr),
                Chain.butter(true, 2, LPF * 1.1, sr));
        this.limitFilters = Chain.of(Chain.butter(true, 4, LPF * 1.15, sr), Chain.butter(false, 2, HPF * 0.85, sr));
        this.crackleBand = Chain.of(Biquad.bandpass(sr, Math.sqrt(1000 * Math.min(3600, LPF * 1.2)), 0.73));

        this.norm1 = new Follower(sr, 5, 1500, 0.03);
        this.norm2 = new Follower(sr, 5, 1500, 0.1);
        this.norm3 = new Follower(sr, 1, 1500, 1.0);

        this.compMsq = 0;
        this.compRef = -20;
        this.compGr = 0;
        this.compGain = 1;
        this.compGainStep = 0;
        this.compCount = 0;
        this.gateMsq = 0;
        this.gateRef = -20;
        this.gateEnv = 0;
        this.gateGain = 0;
        this.gateGainStep = 0;
        this.gateCount = 0;
        this.gateHold = 0;
        this.amSin = 0;
        this.amCos = 1;
        this.fadeLeft = this.dropLeft = 0;
    }

    /** One frame in, one frame out, same length. 16-bit mono at the constructor's rate. */
    public short[] process(short[] in) {
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) {
            double y = sample(in[i] / 32768.0);
            out[i] = (short) Math.round(Math.max(-1, Math.min(1, y)) * 32767);
        }
        return out;
    }

    private double sample(double x) {
        // DC
        double dc = x - this.dcX + 0.995 * this.dcY;
        this.dcX = x;
        this.dcY = dc;
        this.guard = -this.guard;
        dc += this.guard;

        // flutter: modulated fractional delay standing in for the script's variable-speed resample
        double delay = (0.001 + FLUTTER * (0.0009 * lfoSlow.next(rng, sr) + 0.000032 * lfoFast.next(rng, sr))) * sr;
        int mask = this.flutterBuf.length - 1;
        this.flutterBuf[this.flutterPos & mask] = dc;
        double rp = this.flutterPos - delay;
        int i0 = (int) rp;
        if (rp < i0) i0--;
        double frac = rp - i0;
        double fl = this.flutterBuf[i0 & mask] * (1 - frac) + this.flutterBuf[(i0 + 1) & mask] * frac;
        this.flutterPos++;

        double detect = this.band.run(fl);

        // speaker: mid push, set resonances, box comb
        double spk = this.speaker.run(detect);
        int cmask = this.combBuf.length - 1;
        this.combBuf[this.combPos & cmask] = spk;
        double cp = this.combPos - this.combDelay;
        int c0 = (int) cp;
        if (cp < c0) c0--;
        double cf = cp - c0;
        spk -= 0.3 * RESONANCE * (this.combBuf[c0 & cmask] * (1 - cf) + this.combBuf[(c0 + 1) & cmask] * cf);
        this.combPos++;

        // distort
        double xn = spk / this.norm1.run(Math.abs(spk));
        double burst = Math.max(0, Math.min(1, 1.6 * burstLfo.next(rng, sr) - 0.6)) * CLIP;
        double g = DRIVE * (1 + 2.5 * burst);
        double d = tanh(g * xn + 0.3) - TANH_BIAS;
        d = Math.max(-0.55 - 0.3 * (1 - CLIP), Math.min(0.8, d));
        d = this.distortFilters.run(d);

        // compress
        this.compMsq = this.compMsqCoef * this.compMsq + (1 - this.compMsqCoef) * d * d;
        if (++this.compCount >= this.compHop) {
            this.compCount = 0;
            double lvl = 10 * Math.log10(Math.max(this.compMsq, 1e-12));
            this.compRef = lvl > this.compRef ? lvl : Math.max(-50, this.compRef - this.refDecayComp);
            double over = lvl - (this.compRef + THRESHOLD);
            double k = 8.0;
            double gr = over <= -k / 2 ? 0 : over >= k / 2 ? over * (1 - 1 / RATIO)
                    : (1 - 1 / RATIO) * (over + k / 2) * (over + k / 2) / (2 * k);
            double c = gr > this.compGr ? this.compAtt : this.compRel;
            this.compGr = c * this.compGr + (1 - c) * gr;
            this.compGainStep = (undb(-this.compGr) - this.compGain) / this.compHop;
        }
        this.compGain += this.compGainStep;
        double y = this.post.run(d * this.compGain);

        // gate, keyed off the band-limited input like the script
        this.gateMsq = this.gateMsqCoef * this.gateMsq + (1 - this.gateMsqCoef) * detect * detect;
        if (++this.gateCount >= this.gateHop) {
            this.gateCount = 0;
            double lvl = 10 * Math.log10(Math.max(this.gateMsq, 1e-12));
            this.gateRef = lvl > this.gateRef ? lvl : Math.max(-50, this.gateRef - this.refDecayGate);
            if (lvl > this.gateRef - GATE_RANGE && lvl > -60) this.gateHold = this.gateHoldHops;
            else if (this.gateHold > 0) this.gateHold--;
            double o = this.gateHold > 0 ? 1 : 0;
            double c = o > this.gateEnv ? this.gateAtt : this.gateRel;
            this.gateEnv = c * this.gateEnv + (1 - c) * o;
            this.gateGainStep = (this.gateEnv - this.gateGain) / this.gateHop;
        }
        this.gateGain += this.gateGainStep;
        double gate = Math.max(0, Math.min(1, this.gateGain));
        y *= GATE_FLOOR + (1 - GATE_FLOOR) * gate;

        // degrade: carrier fade and AM, signal fades and dropouts on speech, crackle
        double sn = this.amSin * this.amRotCos + this.amCos * this.amRotSin;
        this.amCos = this.amCos * this.amRotCos - this.amSin * this.amRotSin;
        this.amSin = sn;
        y *= (1 + 0.22 * BROKEN * amLfo.next(rng, sr)) * (1 + 0.07 * BROKEN * sn);
        double w = gate * gate;
        double fill = 0;
        if (this.fadeLeft <= 0 && rng.next() < 1.4 * BROKEN / sr * w) {
            this.fadeLen = this.fadeLeft = Math.max(8, (int) (rng.uniform(0.04, 0.18) * sr));
            this.fadeDepth = rng.uniform(0.5, 0.85);
        }
        if (this.fadeLeft > 0) {
            double win = tukey(this.fadeLen - this.fadeLeft, this.fadeLen, 0.5);
            y *= 1 - this.fadeDepth * win;
            fill += 0.4 * win;
            this.fadeLeft--;
        }
        if (this.dropLeft <= 0 && rng.next() < 0.8 * BROKEN / sr * w) {
            this.dropLen = this.dropLeft = Math.max(8, (int) (rng.uniform(0.012, 0.06) * sr));
        }
        if (this.dropLeft > 0) {
            double win = tukey(this.dropLen - this.dropLeft, this.dropLen, 0.2);
            y *= 1 - 0.97 * win;
            fill += win;
            this.dropLeft--;
        }
        double imp = rng.next() < 5 * BROKEN / sr ? rng.uniform(-1, 1) * rng.uniform(0.2, 0.8) : 0;
        y += 2.5 * BROKEN * this.crackleBand.run(imp);
        fill = Math.min(1, fill);

        y /= this.norm2.run(Math.abs(y));

        // channel noise, pumped in pauses and rushing into dropouts
        double base = NOISE_BASE;
        double mod = Math.max(0.15, 1 + 0.8 * noiseModSlow.next(rng, sr) + 0.5 * noiseModFast.next(rng, sr));
        double pump = 1 + 2.5 * AGC_PUMP * (1 - gate);
        double lvl = base * mod * pump + 6 * base * fill;
        y += this.noiseBand.run(rng.gauss()) * this.noiseGain * lvl * 3.0;

        // limit
        y = this.limitFilters.run(y);
        y /= this.norm3.run(Math.abs(y));
        return Math.max(-CEILING, Math.min(CEILING, tanh(1.4 * y) / TANH_14 * CEILING));
    }

    private static final double TANH_BIAS = Math.tanh(0.3);
    private static final double TANH_14 = Math.tanh(1.4);

    /** Rational tanh, within 0.3% of the real one and several times cheaper. */
    static double tanh(double x) {
        if (x > 4.97) return 1;
        if (x < -4.97) return -1;
        double x2 = x * x;
        return x * (135135 + x2 * (17325 + x2 * (378 + x2))) / (135135 + x2 * (62370 + x2 * (3150 + 28 * x2)));
    }

    private static double tukey(int n, int len, double alpha) {
        double t = (double) n / Math.max(1, len - 1);
        double edge = alpha / 2;
        if (t < edge) return 0.5 * (1 - Math.cos(Math.PI * t / edge));
        if (t > 1 - edge) return 0.5 * (1 - Math.cos(Math.PI * (1 - t) / edge));
        return 1;
    }

    static double undb(double db) {
        return Math.pow(10, db / 20);
    }

    private static double coef(double ms, double fs) {
        return Math.exp(-1 / (ms * 1e-3 * fs));
    }

    /** RBJ biquad, transposed direct form II. */
    public static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0;
            this.b1 = b1 / a0;
            this.b2 = b2 / a0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        public double run(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        public static Biquad lowpass(double sr, double f, double q) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), a = Math.sin(w) / (2 * q);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a);
        }

        public static Biquad highpass(double sr, double f, double q) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), a = Math.sin(w) / (2 * q);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a);
        }

        public static Biquad peaking(double sr, double f, double gainDb, double q) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr, c = Math.cos(w), a = Math.sin(w) / (2 * q);
            return new Biquad(1 + a * A, -2 * c, 1 - a * A, 1 + a / A, -2 * c, 1 - a / A);
        }

        public static Biquad bandpass(double sr, double f, double q) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), a = Math.sin(w) / (2 * q);
            return new Biquad(a, 0, -a, 1 + a, -2 * c, 1 - a);
        }
    }

    /** A cascade of biquads. */
    public static final class Chain {
        private final Biquad[] stages;

        private Chain(Biquad[] stages) {
            this.stages = stages;
        }

        public static Chain of(Biquad... stages) {
            return new Chain(stages);
        }

        public static Chain of(Biquad[]... groups) {
            int n = 0;
            for (Biquad[] g : groups) n += g.length;
            Biquad[] all = new Biquad[n];
            int i = 0;
            for (Biquad[] g : groups) for (Biquad b : g) all[i++] = b;
            return new Chain(all);
        }

        /** Butterworth of an even order as second-order sections. */
        public static Biquad[] butter(boolean lowpass, int order, double f, double sr) {
            Biquad[] out = new Biquad[order / 2];
            for (int k = 1; k <= order / 2; k++) {
                double q = 1 / (2 * Math.cos(Math.PI * (2 * k - 1) / (2.0 * order)));
                out[k - 1] = lowpass ? Biquad.lowpass(sr, f, q) : Biquad.highpass(sr, f, q);
            }
            return out;
        }

        public double run(double x) {
            for (Biquad b : stages) x = b.run(x);
            return x;
        }
    }

    /** Peak envelope with attack and release, never below its floor. Dividing by it normalises. */
    private static final class Follower {
        private final double att, rel, floor;
        private double env;

        Follower(double sr, double attackMs, double releaseMs, double floor) {
            this.att = Math.exp(-1 / (attackMs * 1e-3 * sr));
            this.rel = Math.exp(-1 / (releaseMs * 1e-3 * sr));
            this.floor = floor;
            this.env = floor;
        }

        double run(double v) {
            double c = v > env ? att : rel;
            env = c * env + (1 - c) * v;
            return Math.max(floor, env);
        }
    }

    /** Smooth random control signal in about [-1, 1], smoothstep-interpolated between uniform knots. */
    private static final class Smooth {
        private final double rate;
        private double a, b;
        private int pos = -1, len;

        Smooth(double rate) {
            this.rate = rate;
        }

        double next(Rng rng, double sr) {
            if (pos < 0) {
                len = Math.max(1, (int) (sr / rate));
                a = rng.uniform(-1, 1);
                b = rng.uniform(-1, 1);
                pos = 0;
            }
            double t = (double) pos / len;
            double v = a + (b - a) * t * t * (3 - 2 * t);
            if (++pos >= len) {
                a = b;
                b = rng.uniform(-1, 1);
                pos = 0;
            }
            return v;
        }
    }

    /** xorshift64*: seeded, allocation-free, identical on every machine. */
    private static final class Rng {
        private long s;

        Rng(long seed) {
            this.s = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        }

        double next() {
            s ^= s >>> 12;
            s ^= s << 25;
            s ^= s >>> 27;
            return ((s * 0x2545F4914F6CDD1DL) >>> 11) * 0x1.0p-53;
        }

        double uniform(double lo, double hi) {
            return lo + (hi - lo) * next();
        }

        /** Irwin-Hall of four uniforms, unit variance. */
        double gauss() {
            return (next() + next() + next() + next() - 2) * 1.7320508075688772;
        }
    }
}
