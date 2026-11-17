package com.neoalive.tacz_sewv.client.fpas;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One clip of a Bedrock animation file, sampled in Bedrock units (degrees, pixels). Linear and
 * catmullrom only. Length is the last keyframe, not {@code animation_length}, which Blockbench
 * pads. Outside the key range a channel holds its end value.
 */
public final class BedrockClip {

    public enum Channel { ROTATION, POSITION }

    private record Key(float t, float[] v, boolean catmull) {}

    private final Map<String, Map<Channel, Key[]>> bones;
    private final float length;

    private BedrockClip(Map<String, Map<Channel, Key[]>> bones) {
        this.bones = bones;
        float last = 0.0F;
        for (Map<Channel, Key[]> channels : bones.values()) {
            for (Key[] keys : channels.values()) last = Math.max(last, keys[keys.length - 1].t);
        }
        this.length = last;
    }

    /** Seconds. */
    public float length() {
        return this.length;
    }

    public static Map<String, BedrockClip> parse(Reader reader) {
        Map<String, BedrockClip> out = new LinkedHashMap<>();
        JsonObject anims = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> a : anims.entrySet()) {
            JsonObject bonesJson = a.getValue().getAsJsonObject().getAsJsonObject("bones");
            Map<String, Map<Channel, Key[]>> bones = new HashMap<>();
            if (bonesJson != null) {
                for (Map.Entry<String, JsonElement> b : bonesJson.entrySet()) {
                    Map<Channel, Key[]> channels = new HashMap<>();
                    JsonObject bone = b.getValue().getAsJsonObject();
                    putChannel(channels, Channel.ROTATION, bone.get("rotation"));
                    putChannel(channels, Channel.POSITION, bone.get("position"));
                    if (!channels.isEmpty()) bones.put(b.getKey(), channels);
                }
            }
            String name = a.getKey();
            int dot = name.lastIndexOf('.');
            out.put(dot >= 0 ? name.substring(dot + 1) : name, new BedrockClip(bones));
        }
        return out;
    }

    private static void putChannel(Map<Channel, Key[]> channels, Channel channel, @Nullable JsonElement json) {
        if (json == null) return;
        List<Key> keys = new ArrayList<>();
        if (json.isJsonArray()) {
            keys.add(new Key(0.0F, vec(json), false));
        } else {
            for (Map.Entry<String, JsonElement> k : json.getAsJsonObject().entrySet()) {
                float t = Float.parseFloat(k.getKey());
                JsonElement v = k.getValue();
                if (v.isJsonArray()) {
                    keys.add(new Key(t, vec(v), false));
                } else {
                    JsonObject o = v.getAsJsonObject();
                    JsonElement post = o.has("post") ? o.get("post") : o.get("pre");
                    boolean catmull = o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString());
                    keys.add(new Key(t, vec(post), catmull));
                }
            }
        }
        if (keys.isEmpty()) return;
        keys.sort((x, y) -> Float.compare(x.t, y.t));
        channels.put(channel, keys.toArray(new Key[0]));
    }

    private static float[] vec(JsonElement json) {
        JsonArray arr = json.getAsJsonArray();
        float[] v = new float[3];
        for (int i = 0; i < 3 && i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            try {
                v[i] = e.getAsFloat();
            } catch (NumberFormatException | UnsupportedOperationException ex) {
                v[i] = 0.0F;
            }
        }
        return v;
    }

    /** Bedrock-space value at {@code t} seconds, or null when the clip does not drive it. */
    @Nullable
    public float[] sample(String bone, Channel channel, float t) {
        Map<Channel, Key[]> channels = this.bones.get(bone);
        Key[] keys = channels == null ? null : channels.get(channel);
        if (keys == null) return null;
        if (t <= keys[0].t) return keys[0].v.clone();
        int last = keys.length - 1;
        if (t >= keys[last].t) return keys[last].v.clone();
        int i = 0;
        while (keys[i + 1].t < t) i++;
        Key a = keys[i], b = keys[i + 1];
        float span = b.t - a.t;
        float u = span <= 0.0F ? 1.0F : (t - a.t) / span;
        float[] out = new float[3];
        if (a.catmull || b.catmull) {
            float[] p0 = keys[Math.max(i - 1, 0)].v, p3 = keys[Math.min(i + 2, last)].v;
            for (int c = 0; c < 3; c++) out[c] = catmull(p0[c], a.v[c], b.v[c], p3[c], u);
        } else {
            for (int c = 0; c < 3; c++) out[c] = a.v[c] + (b.v[c] - a.v[c]) * u;
        }
        return out;
    }

    private static float catmull(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u, u3 = u2 * u;
        return 0.5F * (2.0F * p1 + (p2 - p0) * u + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * u2
                + (3.0F * p1 - p0 - 3.0F * p2 + p3) * u3);
    }
}
