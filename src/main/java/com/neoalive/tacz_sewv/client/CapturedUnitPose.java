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
 * Client-side Bedrock pose for a captured RU/US medic — same shape as {@link DownedUnitPose},
 * pointed at {@code captured.animation.json} instead of {@code downed.animation.json}. Kept as a
 * separate class rather than a shared parameter on {@code DownedUnitPose} to match this codebase's
 * existing precedent (one class per posed asset — {@code SandbagSeatPose} and {@code DownedUnitPose}
 * already coexist independently) rather than a premature shared abstraction. See that class's doc
 * for the full Molang-subset/bone-mapping rationale; it applies unchanged here since RU/US units
 * share PMC's exact rig ({@code fakeRoot} → {@code unit} → head/body/arms/legs — verified against
 * {@code RUunitModel}/{@code USunitModel}, both built from the same {@code UnitModelDefinitions}).
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

    private static volatile Map<String, Bone> bones = Map.of();
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    private CapturedUnitPose() {}

    public static boolean isLoaded() {
        return !bones.isEmpty();
    }

    /** Poses every bone of the rig at continuous time {@code ageInTicks} — see {@code DownedUnitPose.applyToUnit}. */
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
            LOGGER.warn("Captured pose: model has no bone '{}'", name);
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
                LOGGER.warn("Missing captured pose {}", RESOURCE);
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject animations = root.getAsJsonObject("animations");
                if (animations == null || animations.entrySet().isEmpty()) {
                    LOGGER.warn("Captured pose {} declares no animations", RESOURCE);
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
                        LOGGER.warn("Captured pose {}: unmapped bone '{}'", RESOURCE, bedrockName);
                        continue;
                    }
                    Bone bone = Bone.fromJson(e.getValue().getAsJsonObject(), bedrockName);
                    if (bone != null) out.put(javaName, bone);
                }
                return out;
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load captured pose {}", RESOURCE, ex);
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
            // Model space grows DOWNWARD, Blockbench upward — same flip as DownedUnitPose.Bone.
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

    /** One vector component — see {@code DownedUnitPose.Component} for the supported Molang subset. */
    static final class Component {
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
