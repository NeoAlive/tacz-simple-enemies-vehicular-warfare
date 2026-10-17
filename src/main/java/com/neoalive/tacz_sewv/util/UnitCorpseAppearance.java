package com.neoalive.tacz_sewv.util;

import javax.annotation.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;

/**
 * Common-side identity for a unit corpse skin — faction + SEM variant + optional role folder.
 *
 * <p>Encoded into CorpseMod's synched corpse name (invisible markers) so the client can resolve
 * skins without a CorpseEntity class mixin — keeps softcompat loadable when Corpse is absent.
 */
public record UnitCorpseAppearance(String factionKey, String roleFolder, int variant) {

    /** Invisible separator wrapping the skin header in the corpse name. */
    private static final char MARK = '\u2063';
    private static final String HEADER = "sewv:";

    @Nullable
    public static UnitCorpseAppearance of(AbstractUnit unit) {
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        if (faction == null) return null;
        String factionKey = faction.name().toLowerCase();
        return new UnitCorpseAppearance(factionKey, roleFolder(unit, faction), variantOf(unit));
    }

    /** Embed skin identity in a CorpseMod corpse name (synched to clients). */
    public String encodeName(String displayName) {
        String safeDisplay = displayName == null || displayName.isEmpty() ? "Unit" : displayName;
        String role = roleFolder == null ? "" : roleFolder.replace(MARK, ' ').replace(':', '_');
        String faction = factionKey == null ? "" : factionKey.replace(MARK, ' ').replace(':', '_');
        return MARK + HEADER + faction + ":" + Math.max(0, variant) + ":" + role + MARK + safeDisplay;
    }

    /** Visible name for "Corpse of …" — strips the skin header when present. */
    public static String displayName(String rawCorpseName) {
        if (rawCorpseName == null || rawCorpseName.isEmpty()) return "";
        if (rawCorpseName.charAt(0) != MARK) return rawCorpseName;
        int end = rawCorpseName.indexOf(MARK, 1);
        if (end < 0 || end + 1 >= rawCorpseName.length()) return rawCorpseName;
        return rawCorpseName.substring(end + 1);
    }

    @Nullable
    public static UnitCorpseAppearance parseEncodedName(String rawCorpseName) {
        if (rawCorpseName == null || rawCorpseName.isEmpty() || rawCorpseName.charAt(0) != MARK) {
            return null;
        }
        int end = rawCorpseName.indexOf(MARK, 1);
        if (end < 0) return null;
        String header = rawCorpseName.substring(1, end);
        if (!header.startsWith(HEADER)) return null;
        String[] parts = header.substring(HEADER.length()).split(":", 3);
        if (parts.length < 2) return null;
        String faction = parts[0];
        if (faction.isEmpty()) return null;
        int variant;
        try {
            variant = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        String role = parts.length >= 3 ? parts[2] : "";
        return new UnitCorpseAppearance(faction, role, Math.max(0, variant));
    }

    private static int variantOf(LivingEntity unit) {
        if (unit instanceof RUunitEntity ru) return ru.getVariant();
        if (unit instanceof USunitEntity us) return us.getVariant();
        if (unit instanceof PmcUnitEntity pmc) return pmc.getVariant();
        return 0;
    }

    /** Empty string = infantry ({@code <faction>_unit} variant skins). */
    private static String roleFolder(LivingEntity unit, CrewFacts.Faction faction) {
        if (faction == CrewFacts.Faction.PMC) {
            if (unit instanceof PmcCommanderEntity) return "pmc_commander";
            return switch (SupportRole.of(unit)) {
                case MEDIC -> "pmc_medic";
                case COMBAT_ENGINEER -> "pmc_combat_engineer";
                case ENGINEER -> "pmc_engineer";
                case NONE -> "";
            };
        }
        String prefix = faction.name().toLowerCase();
        if (unit instanceof RuMedicEntity || unit instanceof UsMedicEntity) return prefix + "_medic";
        if (unit instanceof RuCombatEngineerEntity || unit instanceof UsCombatEngineerEntity) {
            return prefix + "_combat_engineer";
        }
        if (unit instanceof RuEngineerEntity || unit instanceof UsEngineerEntity) {
            return prefix + "_engineer";
        }
        return "";
    }
}
