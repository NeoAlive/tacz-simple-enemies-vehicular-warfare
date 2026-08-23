package com.neoalive.tacz_sewv.crew;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Rolls a Name+Surname for a PMC unit once it has an owner, and caches it in the unit's own
 * persistent data — same "guard a boolean flag, only set it once something actually happened"
 * shape as {@link NpcArmor#issue}. PMC only: RU/US units have no owner and no per-player
 * preference to draw a category from.
 *
 * <p>An ownerless unit (a Berezka structure crew, an ambient event rifleman) gets nothing at
 * all — no roll, no cached name, no nameplate — until someone actually owns it. Assigning one
 * early would (a) draw from no meaningful preference, since nobody has claimed it yet, and
 * (b) show a name over a unit nobody has recruited, which reads as every wandering PMC already
 * being "somebody's."
 */
public final class NpcIdentity {

    private static final String ISSUED = "sewv:identity_issued";
    private static final String TAG_NAME = "sewv:crew_name";
    private static final String TAG_SURNAME = "sewv:crew_surname";
    private static final String TAG_CATEGORY = "sewv:crew_category";

    private NpcIdentity() {
    }

    public static void issue(PmcUnitEntity unit) {
        if (!SewvConfig.NAME_ASSIGNMENT_ENABLED.get()) return;

        UUID owner = unit.getOwnerUUID();
        if (owner == null) return;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ISSUED)) return;

        String category = SewvConfig.DEFAULT_NAME_CATEGORY.get();
        if (unit.level() instanceof ServerLevel level) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(owner);
            if (sp != null) category = NamePreference.get(sp, category);
        }

        NamePools.RolledIdentity id = NamePools.active().roll(unit.level().getRandom(), category);
        data.putString(TAG_NAME, id.name());
        data.putString(TAG_SURNAME, id.surname());
        data.putString(TAG_CATEGORY, id.category());
        unit.setCustomName(Component.literal(id.name() + " " + id.surname()));
        data.putBoolean(ISSUED, true);
    }

    /** Forces a fresh roll, e.g. if a unit already has a cached identity and gains a NEW owner
     * (see MixinPmcUnitEntity's setOwner hook) — for the common case of a first-time owner this
     * is equivalent to {@link #issue}, since nothing was cached yet. */
    public static void reissue(PmcUnitEntity unit) {
        unit.getPersistentData().remove(ISSUED);
        issue(unit);
    }

    public static String name(PmcUnitEntity unit) {
        return unit.getPersistentData().getString(TAG_NAME);
    }

    public static String surname(PmcUnitEntity unit) {
        return unit.getPersistentData().getString(TAG_SURNAME);
    }

    public static String fullName(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        if (!data.getBoolean(ISSUED)) return "";
        return data.getString(TAG_NAME) + " " + data.getString(TAG_SURNAME);
    }
}
