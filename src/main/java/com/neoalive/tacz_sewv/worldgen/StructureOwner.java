package com.neoalive.tacz_sewv.worldgen;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Which faction a generated structure belongs to. Pure and seeded, so every chunk of a structure
 * that spans several reaches the same answer without sharing any state.
 */
public final class StructureOwner {

    private StructureOwner() {}

    /**
     * The owner a template's path declares: a whole {@code ru} or {@code us} token
     * ({@code forest_ru_comms}), or {@code pmc} when neither is present. Null when the path says
     * nothing, or names both RU and US.
     */
    @Nullable
    public static TankFaction tokenOf(String path) {
        boolean ru = false;
        boolean us = false;
        boolean pmc = false;
        for (String token : path.toLowerCase(Locale.ROOT).split("[/_]")) {
            switch (token) {
                case "ru" -> ru = true;
                case "us" -> us = true;
                case "pmc" -> pmc = true;
                default -> { }
            }
        }
        if (ru != us) return ru ? TankFaction.RU : TankFaction.US;
        return !ru && pmc ? TankFaction.PMC : null;
    }

    /**
     * An explicit owner wins. Otherwise the RU/US factions present: one is that faction with no
     * roll, both is a coin flip on {@code seed}, neither is null (PMC-only structures need no owner).
     */
    @Nullable
    public static TankFaction resolve(@Nullable TankFaction explicit, Set<TankFaction> present, long seed) {
        if (explicit != null) return explicit;
        boolean ru = present.contains(TankFaction.RU);
        boolean us = present.contains(TankFaction.US);
        if (ru && us) return new Random(seed).nextBoolean() ? TankFaction.RU : TankFaction.US;
        return ru ? TankFaction.RU : us ? TankFaction.US : null;
    }

    /** World seed, structure start chunk and structure id folded into one well-mixed seed. */
    public static long seed(long worldSeed, long startChunk, int structureHash) {
        long h = worldSeed ^ (startChunk * 0x9E3779B97F4A7C15L) ^ ((long) structureHash * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
