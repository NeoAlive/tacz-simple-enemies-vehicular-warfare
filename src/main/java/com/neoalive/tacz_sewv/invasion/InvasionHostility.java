package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Explicit invasion hostility: a crew fights scoreboard teams listed on its stamped enemy list,
 * not "anyone whose team name differs from mine".
 */
public final class InvasionHostility {

    private InvasionHostility() {}

    public static void stampEnemies(Entity entity, List<String> enemyTeams) {
        if (entity == null) return;
        CompoundTag data = entity.getPersistentData();
        ListTag list = new ListTag();
        if (enemyTeams != null) {
            for (String team : enemyTeams) {
                if (team == null || team.isEmpty()) continue;
                list.add(StringTag.valueOf(team));
            }
        }
        data.put(InvasionTags.ENEMIES, list);
    }

    public static List<String> enemiesOf(Entity entity) {
        if (entity == null) return List.of();
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(InvasionTags.ENEMIES, Tag.TAG_LIST)) return List.of();
        ListTag list = data.getList(InvasionTags.ENEMIES, Tag.TAG_STRING);
        if (list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (!s.isEmpty()) out.add(s);
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    /** Scoreboard team of a player, or {@link InvasionTags#TEAM} on an invasion entity. */
    @Nullable
    public static String teamOf(LivingEntity target) {
        if (target instanceof Player player) {
            Team scoreboard = player.getTeam();
            return scoreboard == null ? null : scoreboard.getName();
        }
        String tagged = target.getPersistentData().getString(InvasionTags.TEAM);
        return tagged == null || tagged.isEmpty() ? null : tagged;
    }

    /**
     * True when the shooter is invasion-tagged with a non-empty enemy list and {@code target}'s
     * team is on that list.
     */
    public static boolean isEnemy(AbstractUnit shooter, LivingEntity target) {
        List<String> enemies = enemiesOf(shooter);
        if (enemies.isEmpty()) return false;
        String other = teamOf(target);
        if (other == null) return false;
        return enemies.contains(other);
    }

    /** Same invasion/scoreboard team as the shooter (never hostile via invasion). */
    public static boolean isAlly(AbstractUnit shooter, LivingEntity target) {
        String self = shooter.getPersistentData().getString(InvasionTags.TEAM);
        if (self == null || self.isEmpty()) return false;
        String other = teamOf(target);
        return self.equals(other);
    }
}
