package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.projectile.EDDEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.slf4j.Logger;

/**
 * Puts one mine entity in the world where an engineer is kneeling. Ground and directional mines go
 * through their entity type so an addon mine needs no code; a wall charge needs SBW's EDD
 * constructor, which is the only way to give a hanging entity its face.
 */
public final class MineSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, Method> OWNER_SETTERS = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private MineSpawner() {}

    /** @return whether a mine is now in the world */
    public static boolean ground(ServerLevel level, MineKinds.Kind kind, BlockPos feet, float yaw,
                                 PmcUnitEntity engineer, UUID owner) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(kind.entity());
        if (type == null) return false;
        Entity mine = type.create(level);
        if (mine == null) return false;
        Vec3 at = Vec3.atBottomCenterOf(feet);
        mine.moveTo(at.x, at.y, at.z, yaw, 0.0f);
        mine.setYRot(yaw);
        mine.setDeltaMovement(Vec3.ZERO);
        return finish(level, mine, engineer, owner);
    }

    public static boolean wall(ServerLevel level, MineKinds.Kind kind, BlockPos pos, Direction face,
                               PmcUnitEntity engineer, UUID owner) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(kind.entity());
        if (type == null) return false;
        EDDEntity edd;
        try {
            @SuppressWarnings("unchecked")
            EntityType<? extends EDDEntity> eddType = (EntityType<? extends EDDEntity>) type;
            edd = new EDDEntity(eddType, engineer, level, pos, face, 0);
        } catch (ClassCastException e) {
            LOGGER.warn("[sewv] Mine kind {} is a wall mine but {} is not an EDD - skipped", kind.item(), kind.entity());
            return false;
        }
        if (!edd.survives()) return false;
        return finish(level, edd, engineer, owner);
    }

    /** A wall charge would hang here: the same test the planner and the plant both use. */
    public static boolean wallSurvives(ServerLevel level, MineKinds.Kind kind, BlockPos pos, Direction face,
                                       PmcUnitEntity engineer) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(kind.entity());
        if (type == null) return false;
        try {
            @SuppressWarnings("unchecked")
            EntityType<? extends EDDEntity> eddType = (EntityType<? extends EDDEntity>) type;
            return new EDDEntity(eddType, engineer, level, pos, face, 0).survives();
        } catch (ClassCastException e) {
            return false;
        }
    }

    private static boolean finish(ServerLevel level, Entity mine, PmcUnitEntity engineer, UUID owner) {
        if (!setOwner(mine, owner)) {
            LOGGER.warn("[sewv] {} has no setOwnerUUID(UUID) - a mine nobody owns would hit friendlies, skipped",
                    ForgeRegistries.ENTITY_TYPES.getKey(mine.getType()));
            return false;
        }
        mine.getPersistentData().putBoolean(MineFriends.TAG_LAID, true);
        mine.getPersistentData().putUUID(MineFriends.TAG_LAYER, engineer.getUUID());
        if (!level.addFreshEntity(mine)) return false;
        engineer.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    private static boolean setOwner(Entity mine, UUID owner) {
        Method m = OWNER_SETTERS.computeIfAbsent(mine.getClass(), MineSpawner::findSetter);
        if (m == NONE) return false;
        try {
            m.invoke(mine, owner);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Method findSetter(Class<?> c) {
        try {
            return c.getMethod("setOwnerUUID", UUID.class);
        } catch (NoSuchMethodException e) {
            return NONE;
        }
    }
}
