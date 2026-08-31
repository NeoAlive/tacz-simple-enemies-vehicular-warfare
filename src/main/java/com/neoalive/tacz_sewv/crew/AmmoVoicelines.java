package com.neoalive.tacz_sewv.crew;

import java.util.Locale;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;

/**
 * Maps a chambered SBW ammo id to a faction ammo voiceline pool, with semantic sibling fallback
 * before {@code *_ammo_general_*}.
 */
public final class AmmoVoicelines {

    enum Category {
        HEAT, SABOT, GENERAL
    }

    private AmmoVoicelines() {}

    /** Play a line when {@link com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons} actually switched ammo. */
    public static void play(VehicleEntity hull, AbstractUnit speaker, String ammoItemId) {
        if (ammoItemId == null || ammoItemId.isEmpty()) return;
        SoundPool pool = poolFor(speaker, ammoItemId);
        if (pool == null) return;
        CrewRadio.playAmmo(hull, speaker, pool);
    }

    @Nullable
    static SoundPool poolFor(AbstractUnit speaker, String ammoItemId) {
        Category primary = classify(ammoItemId);
        if (primary == null) return null;
        if (speaker instanceof RUunitEntity) {
            return resolveRu(primary);
        }
        if (speaker instanceof USunitEntity) {
            return resolveUs(primary);
        }
        return null;
    }

    @Nullable
    private static Category classify(String ammoItemId) {
        String id = ammoItemId.toLowerCase(Locale.ROOT);
        if (containsHeat(id)) return Category.HEAT;
        if (containsSabotFamily(id)) return Category.SABOT;
        return Category.GENERAL;
    }

    @Nullable
    private static SoundPool resolveRu(Category primary) {
        if (primary == Category.HEAT) return ModSounds.RU_AMMO_HEAT;
        if (primary == Category.SABOT) return ModSounds.RU_AMMO_SABOT;
        return ModSounds.RU_AMMO_GENERAL;
    }

    @Nullable
    private static SoundPool resolveUs(Category primary) {
        if (primary == Category.HEAT) return ModSounds.US_AMMO_HEAT;
        if (primary == Category.SABOT) return ModSounds.US_AMMO_SABOT;
        return ModSounds.US_AMMO_GENERAL;
    }

    /** Package-visible for self-check. */
    static Category classifyCategory(String ammoItemId) {
        return classify(ammoItemId);
    }

    private static boolean containsHeat(String id) {
        return id.contains("heat") || id.contains("heatfs");
    }

    private static boolean containsSabotFamily(String id) {
        return id.endsWith("_ap") || id.contains("apfsds") || id.contains("sabot");
    }
}
