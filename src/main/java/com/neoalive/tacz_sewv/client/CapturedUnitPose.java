package com.neoalive.tacz_sewv.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Client-side pose for a captured RU/US medic. Kept as a separate class rather than a shared
 * parameter on {@link DownedUnitPose} to match this codebase's precedent (one class per posed
 * asset — {@code SandbagSeatPose} and {@code DownedUnitPose} already coexist independently).
 *
 * <p><b>The canonical kneel is CODED, not authored.</b> The JSON round-trip through the modelling
 * tool mangled this asset repeatedly — deltas vs absolute semantics, an unknown reference rig's
 * bind rotations leaking into exports, Y-flips applied twice — and every attempt to fix it by
 * re-animating (against SEM's idle included) failed for the same root reason: SEM's bind pose is a
 * rifle stance baked into {@code UnitModelDefinitions}, so an export cannot be trusted to mean
 * what it shows. {@code captured.animation.json} is therefore an OPTIONAL overlay: if it parses,
 * its bones replace the coded pose; if missing or broken, nothing logs above debug and the coded
 * kneel below runs. Tuning is a one-place Java edit plus F3+T.
 *
 * <p><b>Rotations are absolute</b>, replacing the bind rotation rather than adding to it — that is
 * what makes the numbers readable: they are measured off a straight mannequin, not off SEM's
 * canted rifle stance (arms ≈ −85°, body +12.5° yaw). Absolute also makes the coded table immune
 * to whatever any future reference rig does. Positions stay additive, with the Bedrock-up /
 * model-down Y flip.
 *
 * <p>Authoring conventions for this rig (model space grows down; a limb's distal end hangs along
 * +Y): limb negative {@code xRot} swings the limb forward (zombie arms are −90°); positive
 * {@code zRot} abducts the RIGHT arm outward and adducts the LEFT; head positive {@code xRot}
 * bows the face down.
 *
 * <p><b>Overlay authoring.</b> {@code docs/unit_zero_rest.geo.json} is the canonical posing
 * reference — this rig's real cubes/pivots with every bind rotation stripped, so Blockbench
 * previews mean exactly what renders. Both static-vector bones AND keyframed channels (what
 * Blockbench exports, including legacy {@code post} wrappers) are accepted; keyframes sample
 * wrapped to {@code animation_length}, Molang components stay continuous as always. Position
 * channels are applied additively with a Y flip — leave them untouched unless nudging on purpose.
 */
public final class CapturedUnitPose {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation RESOURCE =
            new ResourceLocation(TaczSewv.MODID, "animations/captured.animation.json");

    private static final Map<String, String> BONE_NAMES = Map.of(
            "fakeroot", "fakeRoot",
            "unit", "unit",
            "head", "head",
            "body", "body",
            "rightarm", "rightArm",
            "leftarm", "leftArm",
            "rightleg", "rightLeg",
            "leftleg", "leftLeg");

    private static volatile Map<String, Bone> bones = codedPose();
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    /**
     * Diagnostic, deliberately temporary: when true a captured medic renders as a rigid zeroed
     * mannequin instead of the coded kneel. The 13:28 session proved the whole chain works —
     * captured medics reach {@code applyToUnit} with a synced flag. Flip back to false (done) and
     * delete once the kneel is confirmed visually.
     */
    public static volatile boolean DEBUG_TPOSE = false;

    private CapturedUnitPose() {}

    public static boolean isLoaded() {
        return !bones.isEmpty();
    }

    /** Poses every bone of the rig at continuous time {@code ageInTicks}. */
    public static void applyToUnit(ModelPart fakeRoot, float ageInTicks) {
        Map<String, Bone> snapshot = bones;
        if (snapshot.isEmpty()) return;
        float t = ageInTicks / 20.0F;
        ModelPart unit = fakeRoot.hasChild("unit") ? fakeRoot.getChild("unit") : null;

        if (DEBUG_TPOSE) {
            fakeRoot.getAllParts().forEach(ModelPart::resetPose);
            return;
        }

        for (Map.Entry<String, Bone> entry : snapshot.entrySet()) {
            String javaName = entry.getKey();
            Bone bone = entry.getValue();
            ModelPart target = resolve(fakeRoot, unit, javaName);
            if (target != null) {
                pose(target, bone, t);
            } else {
                warnUnknown(javaName);
            }
        }
        // Bones the overlay omits rest at absolute ZERO, not at bind — the rig's bind is the
        // canted rifle stance, and an author posing against docs/unit_zero_rest.geo.json expects
        // an unposed bone to render unposed. Without this, every bone left out of a clip silently
        // re-arms the rifle stance around the authored pose.
        for (String name : BONE_NAMES.values()) {
            if (snapshot.containsKey(name)) continue;
            ModelPart rest = resolve(fakeRoot, unit, name);
            if (rest != null) {
                rest.resetPose();
                rest.xRot = 0.0F;
                rest.yRot = 0.0F;
                rest.zRot = 0.0F;
            }
        }
    }

    @Nullable
    private static ModelPart resolve(ModelPart fakeRoot, @Nullable ModelPart unit, String javaName) {
        if ("fakeRoot".equals(javaName)) return fakeRoot;
        if ("unit".equals(javaName)) return unit;
        return unit != null && unit.hasChild(javaName) ? unit.getChild(javaName) : null;
    }

    private static void pose(ModelPart part, Bone bone, float t) {
        part.resetPose();
        // Absolute: replaces the bind rotation rather than adding to it. This bind pose is a rifle
        // stance (arms ≈ -85°), so additive deltas double onto it — see the class doc.
        float[] rot = bone.sampleRotation(t);
        if (rot != null) {
            part.xRot = rot[0];
            part.yRot = rot[1];
            part.zRot = rot[2];
        }
        float[] pos = bone.samplePosition(t);
        if (pos != null) {
            part.x += pos[0];
            part.y += pos[1];
            part.z += pos[2];
        }
    }

    private static void warnUnknown(String name) {
        if (WARNED.add(name)) {
            LOGGER.warn("Captured pose: model has no bone '{}'", name);
        }
    }

    static void replace(Map<String, Bone> next) {
        bones = Map.copyOf(next);
        WARNED.clear();
    }

    /**
     * The coded kneel-sit — the source of truth when the JSON overlay is absent or broken.
     *
     * <p>Conventions here are EMPIRICAL, taken from poses already proven in this rig — the
     * vehicle-riding mixin (both legs {@code xRot = -1.4137} renders as the classic sit) and the
     * zombie reference ({@code xRot = -90} swings a hanging limb forward). A leg is ONE cube, so
     * a true kneel is unreachable: both legs fold forward at riding angle instead, the body drops
     * onto the ground via the {@code fakeRoot} offset, the torso slumps forward (positive body
     * {@code xRot}), the head bows (positive head {@code xRot}), and the arms hang slack at the
     * sides (absolute ≈0 is a dead hang — the −85° bind was the rifle stance). Hands-behind-back
     * is impossible without an elbow joint and clips through the torso; don't retry it. A slow
     * sine tremor rides head-z and both arm-z so the captive reads alive.
     */
    private static Map<String, Bone> codedPose() {
        Bone fakeRoot = new Bone(null,
                new Component[]{num(0), num(-7), num(0)});
        Bone head = new Bone(new Component[]{
                num(24),
                num(0),
                new Component(0.0F, 1.2F, 1000.0F)}, null);
        Bone body = new Bone(new Component[]{num(14.0), num(0), num(0)}, null);
        Bone rightArm = new Bone(new Component[]{
                num(0),
                num(0),
                num(0)}, null);
        Bone leftArm = new Bone(new Component[]{
                num(0),
                num(0),
                num(0)}, null);
        // Both legs fold the SAME direction (forward) — mirroring the fold puts the left leg
        // through the body. Only zRot mirrors.
        Bone rightLeg = new Bone(new Component[]{num(-78.0), num(0), num(8.0)}, null);
        Bone leftLeg = new Bone(new Component[]{num(-78.0), num(0), num(-8.0)}, null);

        Map<String, Bone> out = new HashMap<>();
        out.put("fakeRoot", fakeRoot);
        out.put("head", head);
        out.put("body", body);
        out.put("rightArm", rightArm);
        out.put("leftArm", leftArm);
        out.put("rightLeg", rightLeg);
        out.put("leftLeg", leftLeg);
        return out;
    }

    private static Component num(double deg) {
        return new Component((float) deg, 0.0F, 0.0F);
    }

    /** Client reload listener — parses the optional Bedrock animation overlay. */
    public static final class Loader extends SimplePreparableReloadListener<Map<String, Bone>> {
        @Override
        protected Map<String, Bone> prepare(ResourceManager manager, ProfilerFiller profiler) {
            return parse(manager);
        }

        @Override
        protected void apply(Map<String, Bone> parsed, ResourceManager manager, ProfilerFiller profiler) {
            replace(parsed);
        }
    }

    /**
     * Parses the optional JSON overlay. Returns the coded pose unchanged (never null, never
     * throws) when the file is missing, structurally wrong, or unmapped — the overlay can only
     * ever swap in a whole alternative pose, never half-apply one.
     */
    static Map<String, Bone> parse(ResourceManager manager) {
        try {
            Resource resource = manager.getResource(RESOURCE).orElse(null);
            if (resource == null) {
                LOGGER.debug("Captured pose overlay {} absent — using coded pose", RESOURCE);
                return codedPose();
            }
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject animations = root.getAsJsonObject("animations");
                if (animations == null || animations.entrySet().isEmpty()) {
                    LOGGER.warn("Captured pose {} declares no animations — using coded pose", RESOURCE);
                    return codedPose();
                }
                JsonObject clipObj = animations.entrySet().iterator().next().getValue().getAsJsonObject();
                float loopLength = clipObj.has("animation_length")
                        ? clipObj.get("animation_length").getAsFloat() : 0.0F;
                JsonObject boneObj = clipObj.getAsJsonObject("bones");
                if (boneObj == null) {
                    LOGGER.warn("Captured pose {} declares no bones — using coded pose", RESOURCE);
                    return codedPose();
                }

                Map<String, Bone> out = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : boneObj.entrySet()) {
                    String bedrockName = e.getKey();
                    String javaName = BONE_NAMES.get(bedrockName.toLowerCase(Locale.ROOT));
                    if (javaName == null) {
                        LOGGER.warn("Captured pose {}: unmapped bone '{}'", RESOURCE, bedrockName);
                        continue;
                    }
                    JsonObject b = e.getValue().getAsJsonObject();
                    Channel rot = Bone.readChannel(b.get("rotation"), bedrockName + ".rotation", loopLength);
                    Channel pos = Bone.readChannel(b.get("position"), bedrockName + ".position", loopLength);
                    if (rot != null || pos != null) out.put(javaName, new Bone(rot, pos));
                }
                if (out.isEmpty()) {
                    LOGGER.warn("Captured pose {} produced no usable bones — using coded pose", RESOURCE);
                    return codedPose();
                }
                return out;
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load captured pose {} — using coded pose", RESOURCE, ex);
            return codedPose();
        }
    }

    static final class Bone {
        @Nullable final Channel rotation;
        @Nullable final Channel position;

        Bone(@Nullable Component[] rotation, @Nullable Component[] position) {
            this(Channel.constant(rotation), Channel.constant(position));
        }

        Bone(@Nullable Channel rotation, @Nullable Channel position) {
            this.rotation = rotation;
            this.position = position;
        }

        @Nullable
        float[] sampleRotation(float t) {
            if (rotation == null) return null;
            float[] s = rotation.sample(t);
            return new float[]{
                    s[0] * Mth.DEG_TO_RAD,
                    s[1] * Mth.DEG_TO_RAD,
                    s[2] * Mth.DEG_TO_RAD};
        }

        @Nullable
        float[] samplePosition(float t) {
            if (position == null) return null;
            float[] s = position.sample(t);
            // Model space grows DOWNWARD, Blockbench upward — same flip as DownedUnitPose.Bone.
            return new float[]{s[0], -s[1], s[2]};
        }

        /**
         * Accepts every shape the authoring tools produce: a static vector
         * ({@code [x, y, z]} / {@code {"vector": [...]}}), a Molang triple, a keyframe map
         * ({@code {"0.0": [..], "1.25": [..]}} — Blockbench's export form), or Blockbench's
         * legacy {@code post}/{@code pre} wrapper (data taken from {@code post}).
         */
        @Nullable
        static Channel readChannel(@Nullable JsonElement el, String context, float loopLength) {
            if (el == null || el.isJsonNull()) return null;
            if (el.isJsonArray()) {
                return Channel.constant(readVec3(el, context));
            }
            if (!el.isJsonObject()) {
                LOGGER.warn("Captured pose: {} is neither a vector nor an object", context);
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("vector")) {
                return Channel.constant(readVec3(obj.get("vector"), context));
            }
            JsonElement post = obj.get("post");
            if (post != null && post.isJsonArray()) {
                return Channel.constant(readVec3(post, context + ".post"));
            }
            JsonElement pre = obj.get("pre");
            if (pre != null && pre.isJsonArray()) {
                return Channel.constant(readVec3(pre, context + ".pre"));
            }
            TreeMap<Float, Component[]> keys = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                float time;
                try {
                    time = Float.parseFloat(e.getKey());
                } catch (NumberFormatException ex) {
                    LOGGER.warn("Captured pose: {} has non-numeric key '{}' — skipped", context, e.getKey());
                    continue;
                }
                if (!e.getValue().isJsonArray()) {
                    LOGGER.warn("Captured pose: {}.{} is not a vector — skipped", context, e.getKey());
                    continue;
                }
                Component[] v = readVec3(e.getValue(), context + "." + e.getKey());
                if (v != null) keys.put(time, v);
            }
            if (keys.isEmpty()) {
                LOGGER.warn("Captured pose: {} declared no readable keyframes", context);
                return null;
            }
            return new Channel(null, keys, Math.max(loopLength, 0.0F));
        }

        private static Component[] readVec3(JsonElement el, String context) {
            JsonArray arr;
            if (el.isJsonObject() && el.getAsJsonObject().has("vector")) {
                arr = el.getAsJsonObject().getAsJsonArray("vector");
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            } else {
                LOGGER.warn("Captured pose: {} is neither a vector object nor an array", context);
                return null;
            }
            Component[] out = new Component[3];
            for (int i = 0; i < 3; i++) {
                out[i] = Component.fromJson(arr.get(i), context + "[" + i + "]");
            }
            return out;
        }
    }

    /**
     * One bone channel: either a constant Molang triple (the historical shape) or a keyframed
     * track sampled with linear interpolation and wrapped to the clip length — an authored pose
     * loops, so {@code t} past the end folds back to the start rather than clamping to the last
     * frame.
     */
    static final class Channel {
        @Nullable private final Component[] constant;
        @Nullable private final TreeMap<Float, Component[]> keys;
        private final float loopLength;

        private Channel(@Nullable Component[] constant,
                        @Nullable TreeMap<Float, Component[]> keys, float loopLength) {
            this.constant = constant;
            this.keys = keys;
            this.loopLength = loopLength;
        }

        static Channel constant(@Nullable Component[] v) {
            return new Channel(v, null, 0.0F);
        }

        float[] sample(float t) {
            if (constant != null) {
                return new float[]{constant[0].sample(t), constant[1].sample(t), constant[2].sample(t)};
            }
            TreeMap<Float, Component[]> k = this.keys;
            float local = t;
            if (loopLength > 0.0F && local > loopLength) {
                local = local - (float) Math.floor(local / loopLength) * loopLength;
            }
            Float lo = k.floorKey(local);
            Float hi = k.ceilingKey(local);
            if (lo == null) return sampleOf(k.get(k.firstKey()), local);
            if (hi == null || lo.equals(hi)) return sampleOf(k.get(lo), local);
            Component[] a = k.get(lo);
            Component[] b = k.get(hi);
            float span = hi - lo;
            float f = span <= 0.0F ? 0.0F : (local - lo) / span;
            return new float[]{
                    Mth.lerp(f, a[0].sample(local), b[0].sample(local)),
                    Mth.lerp(f, a[1].sample(local), b[1].sample(local)),
                    Mth.lerp(f, a[2].sample(local), b[2].sample(local))};
        }

        private static float[] sampleOf(Component[] v, float t) {
            return new float[]{v[0].sample(t), v[1].sample(t), v[2].sample(t)};
        }
    }

    /** One vector component — see {@code DownedUnitPose.Component} for the supported Molang subset. */
    static final class Component {
        // The optional leading base is the "-32.3301" in "-32.3301+math.sin(...)"; the bare form
        // ("math.sin(...)" with no base) parses as base 0.
        private static final Pattern SIN_EXPR = Pattern.compile(
                "^\\s*(-?[\\d.]+)?\\s*([+-])?\\s*math\\.sin\\(\\s*query\\.anim_time\\s*\\*\\s*(-?[\\d.]+)\\s*\\)\\s*\\*\\s*(-?[\\d.]+)\\s*$");

        final float base;
        final float amplitude;
        final float freqDegPerSec;

        private Component(float base, float amplitude, float freqDegPerSec) {
            this.base = base;
            this.amplitude = amplitude;
            this.freqDegPerSec = freqDegPerSec;
        }

        float sample(float t) {
            if (amplitude == 0.0F) return base;
            return base + amplitude * (float) Math.sin(Math.toRadians(freqDegPerSec * t));
        }

        static Component fromJson(JsonElement el, String context) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                return new Component(el.getAsFloat(), 0.0F, 0.0F);
            }
            String expr = el.getAsString();
            Matcher m = SIN_EXPR.matcher(expr);
            if (!m.matches()) {
                LOGGER.warn("Captured pose: unrecognized expression at {}: '{}' — treating as 0", context, expr);
                return new Component(0.0F, 0.0F, 0.0F);
            }
            float base = m.group(1) != null ? Float.parseFloat(m.group(1)) : 0.0F;
            float sign = "-".equals(m.group(2)) ? -1.0F : 1.0F;
            float freq = Float.parseFloat(m.group(3));
            float amp = Float.parseFloat(m.group(4));
            return new Component(base, sign * amp, freq);
        }
    }
}
