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
 * Client-side Bedrock pose for a downed PMC, reloaded with assets so pose edits land on
 * {@code /reload}. Unlike {@link SandbagSeatPose} (a single static pose, head/arms deliberately
 * skipped so aim/look stay live) this poses <b>every</b> bone, head and arms included — a downed
 * unit has nothing left to aim or look with.
 *
 * <p>{@code downed.animation.json} is authored as continuous <b>Molang</b> expressions per bone —
 * not baked keyframes — e.g. {@code "-115-math.sin(query.anim_time * 2000) * 0.2"}: a constant base
 * with a small sine tremor riding on top, evaluated fresh every frame rather than interpolated
 * between discrete times. Each of a vector's 3 components is independently either a plain number or
 * one such expression ({@link Component#fromJson}); this deliberately supports only the one Molang
 * shape actually produced by the export tool ({@code [base]±math.sin(query.anim_time * freq) * amp}),
 * not general Molang — a full expression language is not needed for what this file contains, and
 * {@code query.anim_time} is fed raw, continuous {@code ageInTicks} seconds (never wrapped to a loop
 * length): {@code sin} is already periodic on its own, so wrapping the input would only introduce a
 * seam at the loop boundary for no benefit. <b>Molang's {@code math.sin} takes degrees, not
 * radians</b> — a well-known Molang quirk, easy to get wrong silently.
 *
 * <p>Bedrock bone names in the authored file are mapped case-insensitively to {@code PmcUnitModel}'s
 * actual field names via {@link #BONE_NAMES} — once the animation started being authored directly
 * against {@code docs/pmc_unit_reference.geo.json} (this rig's real geometry, converted from
 * {@code UnitModelDefinitions.java}), bone names are literally the Java field names
 * ({@code fakeRoot}/{@code unit}/{@code head}/...), not Bedrock's PascalCase convention — matched
 * case-insensitively regardless, since exports have not been consistent about casing either way. A
 * bare {@code root} entry (no such bone in this rig — SEM's own root is {@code fakeRoot}, one level
 * further up than {@code unit}) is deliberately left unmapped rather than guessed onto either: it
 * carried stale values unchanged from an earlier export round, not a real edit.
 */
public final class DownedUnitPose {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation RESOURCE =
            new ResourceLocation(TaczSewv.MODID, "animations/downed.animation.json");

    private static final Map<String, String> BONE_NAMES = Map.of(
            "fakeroot", "fakeRoot",
            "unit", "unit",
            "head", "head",
            "body", "body",
            "rightarm", "rightArm",
            "leftarm", "leftArm",
            "rightleg", "rightLeg",
            "leftleg", "leftLeg");

    private static volatile Map<String, Bone> bones = Map.of();
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    private DownedUnitPose() {}

    public static boolean isLoaded() {
        return !bones.isEmpty();
    }

    /**
     * Poses every bone of the rig at continuous time {@code ageInTicks}, starting from
     * {@code fakeRoot} — SEM's actual outermost bone ({@code PmcUnitModel.root()} /
     * {@code PmcCommanderModel.root()}), one level above {@code unit}. Both {@code fakeRoot} and
     * {@code unit} are independently posable, since the authored clip may — and currently does —
     * target either or both.
     */
    public static void applyToUnit(ModelPart fakeRoot, float ageInTicks) {
        Map<String, Bone> snapshot = bones;
        if (snapshot.isEmpty()) return;
        float t = ageInTicks / 20.0F;
        ModelPart unit = fakeRoot.hasChild("unit") ? fakeRoot.getChild("unit") : null;

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
    }

    @Nullable
    private static ModelPart resolve(ModelPart fakeRoot, @Nullable ModelPart unit, String javaName) {
        if ("fakeRoot".equals(javaName)) return fakeRoot;
        if ("unit".equals(javaName)) return unit;
        return unit != null && unit.hasChild(javaName) ? unit.getChild(javaName) : null;
    }

    /**
     * {@code ModelPart#resetPose()} restores {@code initialPose} — this rig's own bind rotation
     * (e.g. rightArm rests at roughly -85°, not 0°; every bone here has a non-trivial bind pose,
     * unlike a plain vanilla humanoid) — not an identity pose. Bedrock rotation values are deltas
     * from that bind pose, so they are <b>added</b> on top of what {@code resetPose()} just
     * restored, never overwritten. Position has no such baseline to preserve, so it stays additive
     * as it already was.
     */
    private static void pose(ModelPart part, Bone bone, float t) {
        part.resetPose();
        float[] rot = bone.sampleRotation(t);
        if (rot != null) {
            part.xRot += rot[0];
            part.yRot += rot[1];
            part.zRot += rot[2];
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
            LOGGER.warn("Downed pose: model has no bone '{}'", name);
        }
    }

    static void replace(Map<String, Bone> next) {
        bones = Map.copyOf(next);
        WARNED.clear();
    }

    /** Client reload listener — parses the Bedrock animation asset. */
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

    static Map<String, Bone> parse(ResourceManager manager) {
        try {
            Resource resource = manager.getResource(RESOURCE).orElse(null);
            if (resource == null) {
                LOGGER.warn("Missing downed pose {}", RESOURCE);
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject animations = root.getAsJsonObject("animations");
                if (animations == null || animations.entrySet().isEmpty()) {
                    LOGGER.warn("Downed pose {} declares no animations", RESOURCE);
                    return Map.of();
                }
                JsonObject clipObj = animations.entrySet().iterator().next().getValue().getAsJsonObject();
                JsonObject boneObj = clipObj.getAsJsonObject("bones");
                if (boneObj == null) return Map.of();

                Map<String, Bone> out = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : boneObj.entrySet()) {
                    String bedrockName = e.getKey();
                    String javaName = BONE_NAMES.get(bedrockName.toLowerCase(Locale.ROOT));
                    if (javaName == null) {
                        LOGGER.warn("Downed pose {}: unmapped bone '{}'", RESOURCE, bedrockName);
                        continue;
                    }
                    Bone bone = Bone.fromJson(e.getValue().getAsJsonObject(), bedrockName);
                    if (bone != null) out.put(javaName, bone);
                }
                return out;
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load downed pose {}", RESOURCE, ex);
            return Map.of();
        }
    }

    static final class Bone {
        @Nullable final Component[] rotation;
        @Nullable final Component[] position;

        Bone(@Nullable Component[] rotation, @Nullable Component[] position) {
            this.rotation = rotation;
            this.position = position;
        }

        @Nullable
        float[] sampleRotation(float t) {
            if (rotation == null) return null;
            return new float[]{
                    rotation[0].sample(t) * Mth.DEG_TO_RAD,
                    rotation[1].sample(t) * Mth.DEG_TO_RAD,
                    rotation[2].sample(t) * Mth.DEG_TO_RAD};
        }

        @Nullable
        float[] samplePosition(float t) {
            if (position == null) return null;
            float x = position[0].sample(t);
            float y = position[1].sample(t);
            float z = position[2].sample(t);
            // Model space grows DOWNWARD, Blockbench upward — same flip as SandbagSeatPose.Bone.
            return new float[]{x, -y, z};
        }

        @Nullable
        static Bone fromJson(JsonObject b, String boneName) {
            Component[] rot = b.has("rotation")
                    ? readVec3(b.get("rotation"), boneName + ".rotation") : null;
            Component[] pos = b.has("position")
                    ? readVec3(b.get("position"), boneName + ".position") : null;
            if (rot == null && pos == null) return null;
            return new Bone(rot, pos);
        }

        private static Component[] readVec3(JsonElement el, String context) {
            JsonArray arr;
            if (el.isJsonObject() && el.getAsJsonObject().has("vector")) {
                arr = el.getAsJsonObject().getAsJsonArray("vector");
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            } else {
                LOGGER.warn("Downed pose: {} is neither a vector object nor an array", context);
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
     * One vector component: either a plain constant, or {@code base ± amplitude *
     * sin(freq * t)} (degrees) sampled fresh every call — see the class doc for the exact Molang
     * shape this supports and why {@code t} is never wrapped.
     */
    static final class Component {
        /** Matches {@code [base][+-]math.sin(query.anim_time * freq) * amp}; base and sign both optional. */
        private static final Pattern SIN_EXPR = Pattern.compile(
                "^\\s*(-?[\\d.]+)?\\s*([+-])?\\s*math\\.sin\\(\\s*query\\.anim_time\\s*\\*\\s*(-?[\\d.]+)\\s*\\)\\s*\\*\\s*(-?[\\d.]+)\\s*$");

        final float base;
        /** Signed amplitude (the operator sign already folded in) — 0 for a plain constant. */
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
                LOGGER.warn("Downed pose: unrecognized expression at {}: '{}' — treating as 0", context, expr);
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
