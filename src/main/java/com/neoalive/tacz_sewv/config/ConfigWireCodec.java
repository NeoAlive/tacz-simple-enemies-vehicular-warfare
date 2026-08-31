package com.neoalive.tacz_sewv.config;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;

public final class ConfigWireCodec {

    private static final byte TAG_BOOL = 0;
    private static final byte TAG_INT = 1;
    private static final byte TAG_DOUBLE = 2;
    private static final byte TAG_STRING = 3;
    private static final byte TAG_MULTILINE = 4;

    private ConfigWireCodec() {}

    public static void writeSnapshot(FriendlyByteBuf buf, Map<Integer, Object> snapshot) {
        buf.writeVarInt(snapshot.size());
        for (Map.Entry<Integer, Object> e : snapshot.entrySet()) {
            buf.writeVarInt(e.getKey());
            writeValue(buf, e.getValue());
        }
    }

    public static Map<Integer, Object> readSnapshot(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<Integer, Object> out = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            int index = buf.readVarInt();
            out.put(index, readValue(buf));
        }
        return out;
    }

    public static void writeDraftChanges(FriendlyByteBuf buf, Map<Integer, String> changes) {
        buf.writeVarInt(changes.size());
        for (Map.Entry<Integer, String> e : changes.entrySet()) {
            buf.writeVarInt(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static Map<Integer, String> readDraftChanges(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<Integer, String> out = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            out.put(buf.readVarInt(), buf.readUtf());
        }
        return out;
    }

    private static void writeValue(FriendlyByteBuf buf, Object value) {
        if (value instanceof Boolean b) {
            buf.writeByte(TAG_BOOL);
            buf.writeBoolean(b);
        } else if (value instanceof Integer i) {
            buf.writeByte(TAG_INT);
            buf.writeVarInt(i);
        } else if (value instanceof Double d) {
            buf.writeByte(TAG_DOUBLE);
            buf.writeDouble(d);
        } else if (value instanceof java.util.List<?> list) {
            buf.writeByte(TAG_MULTILINE);
            buf.writeVarInt(list.size());
            for (Object line : list) buf.writeUtf(String.valueOf(line));
        } else {
            buf.writeByte(TAG_STRING);
            buf.writeUtf(String.valueOf(value));
        }
    }

    private static Object readValue(FriendlyByteBuf buf) {
        return switch (buf.readByte()) {
            case TAG_BOOL -> buf.readBoolean();
            case TAG_INT -> buf.readVarInt();
            case TAG_DOUBLE -> buf.readDouble();
            case TAG_MULTILINE -> {
                int n = buf.readVarInt();
                java.util.List<String> lines = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) lines.add(buf.readUtf());
                yield lines;
            }
            default -> buf.readUtf();
        };
    }

    public static String snapshotToDraft(Object value) {
        return ConfigValidator.formatDraft(value);
    }
}
