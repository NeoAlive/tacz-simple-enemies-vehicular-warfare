package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.fob.FobSupport;

/**
 * Who a mine laid by the player's engineers must not go off under. SBW's own filter resolves the
 * owner through {@code getPlayerByUUID} and so only knows players; this asks the same question a
 * PMC's splash hold does, from one of the owner's units. Only mines this mod laid are touched, so
 * a mine a player places by hand keeps SBW's rules.
 */
public final class MineFriends {

    public static final String TAG_LAID = "sewv:laid_mine";
    public static final String TAG_LAYER = "sewv:mine_layer";

    /** How long a failed perspective lookup is remembered before the level is scanned again. */
    private static final long RETRY_TICKS = 100L;

    private static final Map<UUID, WeakReference<PmcUnitEntity>> PERSPECTIVE = new HashMap<>();
    private static final Map<UUID, Long> MISSED = new HashMap<>();

    private MineFriends() {}

    public static boolean spares(Entity mine, Entity e) {
        if (!(mine.level() instanceof ServerLevel level)) return false;
        if (!mine.getPersistentData().getBoolean(TAG_LAID)) return false;
        if (!(mine instanceof OwnableEntity ownable)) return false;
        UUID owner = ownable.getOwnerUUID();
        if (owner == null) return false;
        if (e instanceof LivingEntity living) return sparesLiving(level, mine, owner, living);
        if (e.getPassengers().isEmpty()) return false;
        for (Entity rider : e.getPassengers()) {
            if (!(rider instanceof LivingEntity living) || !sparesLiving(level, mine, owner, living)) return false;
        }
        return true;
    }

    private static boolean sparesLiving(ServerLevel level, Entity mine, UUID owner, LivingEntity living) {
        if (living instanceof Player p && owner.equals(p.getUUID())) return true;
        if (living instanceof PmcUnitEntity pmc && owner.equals(pmc.getOwnerUUID())) return true;
        AbstractUnit from = perspective(level, mine, owner);
        if (from == null) return false;
        if (living instanceof AbstractUnit unit) return VehicleTargeting.isNonHostile(from, unit);
        return VehicleTargeting.isSplashProtected(from, living);
    }

    @Nullable
    private static AbstractUnit perspective(ServerLevel level, Entity mine, UUID owner) {
        if (mine.getPersistentData().hasUUID(TAG_LAYER)
                && level.getEntity(mine.getPersistentData().getUUID(TAG_LAYER)) instanceof PmcUnitEntity layer
                && layer.isAlive()) {
            return layer;
        }
        WeakReference<PmcUnitEntity> ref = PERSPECTIVE.get(owner);
        PmcUnitEntity cached = ref == null ? null : ref.get();
        if (cached != null && cached.isAlive() && cached.level() == level) return cached;
        long now = level.getGameTime();
        Long missed = MISSED.get(owner);
        if (missed != null && now - missed < RETRY_TICKS) return null;
        if (FobSupport.ownerPerspective(level, owner) instanceof PmcUnitEntity found) {
            PERSPECTIVE.put(owner, new WeakReference<>(found));
            MISSED.remove(owner);
            return found;
        }
        MISSED.put(owner, now);
        return null;
    }
}
