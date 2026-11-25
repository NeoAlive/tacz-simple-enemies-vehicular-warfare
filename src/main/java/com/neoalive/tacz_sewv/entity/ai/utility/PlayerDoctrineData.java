package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Stores player-configured doctrines and whether a player has received their initial doctrine book.
 * Server authoritative, keyed by player UUID.
 */
public class PlayerDoctrineData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_player_doctrines";

    private final Map<UUID, Doctrine> doctrines = new HashMap<>();
    private final Set<UUID> receivedBook = new HashSet<>();
    /** Command rating: the summed veterancy XP the player's units ever earned. Kept when units die. */
    private final Map<UUID, Integer> ratings = new HashMap<>();

    public PlayerDoctrineData() {}

    public static PlayerDoctrineData load(CompoundTag nbt) {
        PlayerDoctrineData data = new PlayerDoctrineData();

        if (nbt.contains("doctrines", Tag.TAG_LIST)) {
            ListTag doctrinesList = nbt.getList("doctrines", Tag.TAG_COMPOUND);
            for (int i = 0; i < doctrinesList.size(); i++) {
                CompoundTag tag = doctrinesList.getCompound(i);
                UUID uuid = tag.getUUID("uuid");
                int[] axes = tag.getIntArray("axes");
                if (axes.length == Doctrine.Axis.VALUES.length) {
                    data.doctrines.put(uuid, Doctrine.ofAxes(axes));
                }
            }
        }

        if (nbt.contains("receivedBook", Tag.TAG_LIST)) {
            ListTag booksList = nbt.getList("receivedBook", Tag.TAG_COMPOUND);
            for (int i = 0; i < booksList.size(); i++) {
                CompoundTag tag = booksList.getCompound(i);
                data.receivedBook.add(tag.getUUID("uuid"));
            }
        }

        if (nbt.contains("ratings", Tag.TAG_LIST)) {
            ListTag list = nbt.getList("ratings", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                data.ratings.put(tag.getUUID("uuid"), Math.max(0, tag.getInt("rating")));
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag doctrinesList = new ListTag();
        for (Map.Entry<UUID, Doctrine> entry : doctrines.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", entry.getKey());
            int[] axes = new int[Doctrine.Axis.VALUES.length];
            for (int i = 0; i < axes.length; i++) {
                axes[i] = entry.getValue().raw(Doctrine.Axis.VALUES[i]);
            }
            tag.putIntArray("axes", axes);
            doctrinesList.add(tag);
        }
        nbt.put("doctrines", doctrinesList);

        ListTag booksList = new ListTag();
        for (UUID uuid : receivedBook) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            booksList.add(tag);
        }
        nbt.put("receivedBook", booksList);

        ListTag ratingList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : ratings.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", entry.getKey());
            tag.putInt("rating", entry.getValue());
            ratingList.add(tag);
        }
        nbt.put("ratings", ratingList);

        return nbt;
    }

    public static PlayerDoctrineData get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        PlayerDoctrineData::load,
                        PlayerDoctrineData::new,
                        DATA_NAME
                );
            }
        }
        return new PlayerDoctrineData(); // Dummy fallback for client/missing overworld
    }

    @Nullable
    public Doctrine getDoctrine(UUID playerUuid) {
        return doctrines.get(playerUuid);
    }

    public void setDoctrine(UUID playerUuid, Doctrine doctrine) {
        doctrines.put(playerUuid, doctrine);
        setDirty();
    }

    /** Points every ledger must spend, before any progression. */
    public static final int BASE_BUDGET = 20;

    /**
     * A ledger is valid when it spends at least the base budget and no more than the player's current budget. Earned
     * points are optional to spend, so a doctrine saved before the budget grew (and every preset) stays confirmable.
     * Shared by the editor's Confirm button and the server's save check.
     */
    public static boolean validTotal(int total, int budget) {
        return total >= Math.min(BASE_BUDGET, budget) && total <= budget;
    }

    /** The most points this player's ledger may spend: the base plus what their command rating earned. */
    public int budget(UUID playerUuid) {
        return DoctrineKnobs.budget(BASE_BUDGET, rating(playerUuid));
    }

    public int rating(UUID playerUuid) {
        return ratings.getOrDefault(playerUuid, 0);
    }

    /** Adds to the player's rating; returns true when it earned a new ledger point. */
    public boolean addRating(UUID playerUuid, int amount) {
        if (amount <= 0) return false;
        int before = budget(playerUuid);
        ratings.merge(playerUuid, amount, Integer::sum);
        setDirty();
        return budget(playerUuid) > before;
    }

    public boolean hasReceivedBook(UUID playerUuid) {
        return receivedBook.contains(playerUuid);
    }

    public void setReceivedBook(UUID playerUuid) {
        if (receivedBook.add(playerUuid)) {
            setDirty();
        }
    }
}
