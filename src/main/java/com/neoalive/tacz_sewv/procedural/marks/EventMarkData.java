package com.neoalive.tacz_sewv.procedural.marks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Event marks waiting out in the world, on the overworld (SEM's events are overworld only). Times
 * are absolute game times so a mark means the same moment after a save and load.
 */
public class EventMarkData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_event_marks";

    /** One waiting event: a spot, what fires there, until when, and for whom it was dealt. */
    public static final class Mark {
        public final String eventId;
        public double x;
        public double z;
        public final long expiresAt;
        public final UUID owner;
        /** Seen inside a player's simulated area at least once; a mark only fires after that crossing. */
        public boolean crossed;

        Mark(String eventId, double x, double z, long expiresAt, UUID owner, boolean crossed) {
            this.eventId = eventId;
            this.x = x;
            this.z = z;
            this.expiresAt = expiresAt;
            this.owner = owner;
            this.crossed = crossed;
        }
    }

    private final List<Mark> marks = new ArrayList<>();

    public static EventMarkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(EventMarkData::new, (tag, registries) -> EventMarkData.load(tag)), DATA_NAME);
    }

    public static EventMarkData load(CompoundTag tag) {
        EventMarkData data = new EventMarkData();
        ListTag list = tag.getList("Marks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag m = list.getCompound(i);
            if (!m.hasUUID("Owner")) continue;
            data.marks.add(new Mark(m.getString("Event"), m.getDouble("X"), m.getDouble("Z"),
                    m.getLong("Expires"), m.getUUID("Owner"), m.getBoolean("Crossed")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Mark mark : this.marks) {
            CompoundTag m = new CompoundTag();
            m.putString("Event", mark.eventId);
            m.putDouble("X", mark.x);
            m.putDouble("Z", mark.z);
            m.putLong("Expires", mark.expiresAt);
            m.putUUID("Owner", mark.owner);
            m.putBoolean("Crossed", mark.crossed);
            list.add(m);
        }
        tag.put("Marks", list);
        return tag;
    }

    public List<Mark> marks() {
        return this.marks;
    }

    public Mark add(String eventId, double x, double z, long expiresAt, UUID owner) {
        Mark mark = new Mark(eventId, x, z, expiresAt, owner, false);
        this.marks.add(mark);
        setDirty();
        return mark;
    }
}
