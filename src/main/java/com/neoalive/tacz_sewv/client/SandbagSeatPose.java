package com.neoalive.tacz_sewv.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * Client-side Bedrock seat pose for sandbag mounts, reloaded with assets so pose tweaks land
 * on {@code /reload}.
 *
 * <p><b>Every write is absolute.</b> A bone is first returned to its bind pose
 * ({@link ModelPart#resetPose()}) and the clip is then written on top of that, never onto
 * whatever the previous frame left behind. Additive writes are not an option here: vanilla
 * {@code HumanoidModel.setupAnim} recomputes rotations every frame but never resets
 * {@code body.x/y/z} (nor the arms' {@code y}), so a {@code +=} offset compounds each frame
 * until the torso is flung outside the frustum — and, because nothing else ever writes those
 * fields, the damage outlives the dismount and only a resource reload clears it. Restoring the
 * bind pose also makes this a true override of SEM's animation stack, which writes the same
 * bones (its walk clip animates {@code unit} and {@code body}) right up to our injection point.
 *
 * <p>{@code head} is deliberately never rotated, so SEM's head-tracking layer and the player's
 * own look stay in charge; it only inherits the rig-wide translation.
 */
public final class SandbagSeatPose {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation RESOURCE =
            new ResourceLocation(TaczSewv.MODID, "animations/sandbag_seat.animation.json");

    /** Bedrock {@code steve}: the whole rig. SEM has it as a real bone; humanoids fake it. */
    private static final String ROOT_BONE = "unit";

    private static volatile Map<String, Bone> bones = Map.of();
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    private SandbagSeatPose() {}

    public static boolean isLoaded() {
        return !bones.isEmpty();
    }

    /**
     * SEM units: {@code unit} carries the whole rig, so the root translation applies to it alone
     * and its children inherit. Every clip bone is looked up by name, so a bone added to the JSON
     * needs no code change.
     */
    public static void applyToUnit(ModelPart unit) {
        Map<String, Bone> snapshot = bones;
        if (snapshot.isEmpty()) return;
        for (Map.Entry<String, Bone> entry : snapshot.entrySet()) {
            String name = entry.getKey();
            if (ROOT_BONE.equals(name)) {
                pose(unit, entry.getValue(), null);
            } else if (unit.hasChild(name)) {
                pose(unit.getChild(name), entry.getValue(), null);
            } else {
                warnUnknown(name);
            }
        }
    }

    /**
     * Vanilla humanoids have no root bone — head, body and limbs are siblings — so the rig-wide
     * translation has to be applied to each of them, head included, or the head detaches from the
     * body. Head keeps its animated rotation.
     */
    public static void applyToHumanoid(ModelPart head, ModelPart hat, ModelPart body,
                                       ModelPart rightArm, ModelPart leftArm,
                                       ModelPart rightLeg, ModelPart leftLeg) {
        Map<String, Bone> snapshot = bones;
        if (snapshot.isEmpty()) return;
        Bone root = snapshot.get(ROOT_BONE);

        pose(head, null, root);
        pose(body, snapshot.get("body"), root);
        pose(rightArm, snapshot.get("rightArm"), root);
        pose(leftArm, snapshot.get("leftArm"), root);
        pose(rightLeg, snapshot.get("rightLeg"), root);
        pose(leftLeg, snapshot.get("leftLeg"), root);
        hat.copyFrom(head);
    }

    /**
     * One-shot cleanup for the frame after a humanoid stops riding: positions back to the bind
     * pose, rotations left exactly as this frame's animation computed them. Needed because
     * vanilla never rewrites {@code body}'s position, so the last seated offset would otherwise
     * stick to the model for the rest of the session.
     */
    public static void restoreHumanoid(ModelPart head, ModelPart hat, ModelPart body,
                                       ModelPart rightArm, ModelPart leftArm,
                                       ModelPart rightLeg, ModelPart leftLeg) {
        pose(head, null, null);
        pose(body, null, null);
        pose(rightArm, null, null);
        pose(leftArm, null, null);
        pose(rightLeg, null, null);
        pose(leftLeg, null, null);
        hat.copyFrom(head);
    }

    /**
     * Bind pose, then the clip on top. A bone the clip does not rotate keeps the rotation the
     * animation stack produced; a bone the clip does not mention at all is merely returned to
     * its bind position and offset by the rig translation.
     */
    private static void pose(ModelPart part, @Nullable Bone bone, @Nullable Bone root) {
        if (part == null) return;

        float xRot = part.xRot;
        float yRot = part.yRot;
        float zRot = part.zRot;
        part.resetPose();

        if (bone != null && bone.hasRotation) {
            part.xRot = bone.xRot;
            part.yRot = bone.yRot;
            part.zRot = bone.zRot;
        } else {
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
        }

        if (root != null && root.hasPosition) {
            part.x += root.x;
            part.y += root.y;
            part.z += root.z;
        }
        if (bone != null && bone.hasPosition) {
            part.x += bone.x;
            part.y += bone.y;
            part.z += bone.z;
        }
    }

    private static void warnUnknown(String name) {
        if (WARNED.add(name)) {
            LOGGER.warn("Sandbag seat pose: model has no bone '{}'", name);
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
                LOGGER.warn("Missing sandbag seat pose {}", RESOURCE);
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject animations = root.getAsJsonObject("animations");
                if (animations == null || animations.entrySet().isEmpty()) {
                    LOGGER.warn("Sandbag seat pose {} declares no animations", RESOURCE);
                    return Map.of();
                }
                JsonObject clip = animations.entrySet().iterator().next().getValue().getAsJsonObject();
                JsonObject boneObj = clip.getAsJsonObject("bones");
                if (boneObj == null) return Map.of();

                Map<String, Bone> out = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : boneObj.entrySet()) {
                    String name = e.getKey();
                    // Head rotation belongs to look tracking; it only follows the rig translation.
                    if ("head".equals(name) || "hat".equals(name)) continue;
                    Bone bone = Bone.fromJson(e.getValue().getAsJsonObject());
                    if (bone != null) out.put(name, bone);
                }
                return out;
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load sandbag seat pose {}", RESOURCE, ex);
            return Map.of();
        }
    }

    static final class Bone {
        final boolean hasRotation;
        final boolean hasPosition;
        final float xRot;
        final float yRot;
        final float zRot;
        final float x;
        final float y;
        final float z;

        Bone(boolean hasRotation, boolean hasPosition,
             float xRot, float yRot, float zRot, float x, float y, float z) {
            this.hasRotation = hasRotation;
            this.hasPosition = hasPosition;
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Nullable
        static Bone fromJson(JsonObject b) {
            boolean hasRot = b.has("rotation");
            boolean hasPos = b.has("position");
            if (!hasRot && !hasPos) return null;

            float xRot = 0.0F;
            float yRot = 0.0F;
            float zRot = 0.0F;
            if (hasRot) {
                float[] r = readVec3(b.get("rotation"));
                xRot = r[0] * Mth.DEG_TO_RAD;
                yRot = r[1] * Mth.DEG_TO_RAD;
                zRot = r[2] * Mth.DEG_TO_RAD;
            }

            float x = 0.0F;
            float y = 0.0F;
            float z = 0.0F;
            if (hasPos) {
                float[] p = readVec3(b.get("position"));
                // Model space grows DOWNWARD, Blockbench upward — the same flip vanilla applies in
                // KeyframeAnimations.posVec. Without it the pose lifts the rig instead of sinking it.
                x = p[0];
                y = -p[1];
                z = p[2];
            }
            return new Bone(hasRot, hasPos, xRot, yRot, zRot, x, y, z);
        }

        private static float[] readVec3(JsonElement el) {
            JsonArray a = el.getAsJsonArray();
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
    }
}
