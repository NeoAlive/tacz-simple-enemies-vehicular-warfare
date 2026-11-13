package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.item.misc.MortarDeployerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;
import com.neoalive.tacz_sewv.zone.SpawnZones;

/**
 * Deploy Equipment: a PMC sets down the emplacement it carries. Two item shapes count, both SBW's
 * own: the mortar deployer (an item that is a mortar), and a container whose stored entity type is
 * in the TOW or MORTAR pool. The container's stored entity data is carried over, as SBW's own
 * crowbar opening does.
 */
public final class EquipmentDeploy {

    /** How far ahead of the unit the emplacement goes before the spot search spirals out. */
    private static final double AHEAD = 2.0;
    private static final int SEARCH = 4;

    public record Kit(int slot, EntityType<?> type, @Nullable CompoundTag entityNbt) {}

    private EquipmentDeploy() {}

    /** The first pocket item the unit could set down, or null. */
    @Nullable
    public static Kit find(ServerLevel level, IItemHandlerModifiable inv) {
        WorldVehiclePools pools = WorldVehiclePools.get(level);
        List<String> tow = pools.list(TankFaction.PMC, WorldVehiclePools.Category.TOW);
        List<String> mortar = pools.list(TankFaction.PMC, WorldVehiclePools.Category.MORTAR);
        for (int slot = RoleKit.FIRST_POCKET; slot < inv.getSlots(); slot++) {
            Kit kit = resolve(inv.getStackInSlot(slot), slot, tow, mortar);
            if (kit != null) return kit;
        }
        return null;
    }

    @Nullable
    public static Kit resolve(ItemStack stack, int slot, List<String> tow, List<String> mortar) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof MortarDeployerItem) {
            EntityType<?> type = ModEntities.MORTAR.get();
            return pooled(type, tow, mortar) ? new Kit(slot, type, null) : null;
        }
        if (!(stack.getItem() instanceof BlockItem)) return null;
        CompoundTag be = BlockItem.getBlockEntityData(stack);
        if (be == null || !be.contains("EntityType", Tag.TAG_STRING)) return null;
        EntityType<?> type = EntityType.byString(be.getString("EntityType")).orElse(null);
        if (type == null || !pooled(type, tow, mortar)) return null;
        CompoundTag data = be.contains("Entity", Tag.TAG_COMPOUND) ? be.getCompound("Entity") : null;
        return new Kit(slot, type, data);
    }

    private static boolean pooled(EntityType<?> type, List<String> tow, List<String> mortar) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (id == null) return false;
        String s = id.toString();
        return tow.contains(s) || mortar.contains(s);
    }

    /** Where the emplacement goes: ahead of the unit along {@code yaw}, on flat open ground, or null. */
    @Nullable
    public static Vec3 spot(ServerLevel level, PmcUnitEntity unit, EntityType<?> type, float yaw) {
        double fx = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        double fz = Mth.cos(yaw * Mth.DEG_TO_RAD);
        double cx = unit.getX() + fx * AHEAD;
        double cz = unit.getZ() + fz * AHEAD;
        for (int r = 0; r <= SEARCH; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Vec3 at = fits(level, unit, type, Mth.floor(cx) + dx, Mth.floor(cz) + dz);
                    if (at != null) return at;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Vec3 fits(ServerLevel level, PmcUnitEntity unit, EntityType<?> type, int x, int z) {
        if (!SpawnZones.allowsChunk(level, x >> 4, z >> 4)) return null;
        int y = (int) GroundSnap.groundY(level, x + 0.5, z + 0.5, unit.getY());
        BlockPos feet = new BlockPos(x, y, z);
        if (!level.hasChunkAt(feet) || Math.abs(y - unit.getY()) > 3) return null;
        if (!BailOutSupport.isStandable(level, feet)) return null;
        if (!level.getBlockState(feet).getFluidState().isEmpty()
                || !level.getBlockState(feet.below()).getFluidState().isEmpty()) {
            return null;
        }
        Vec3 at = Vec3.atBottomCenterOf(feet);
        AABB box = type.getAABB(at.x, at.y, at.z);
        if (!level.noCollision(box)) return null;
        // Flat across the footprint: no corner more than a block above or below the centre.
        double hx = box.getXsize() * 0.5;
        double hz = box.getZsize() * 0.5;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                double gy = GroundSnap.groundY(level, at.x + sx * hx, at.z + sz * hz, at.y);
                if (Math.abs(gy - at.y) > 1.0) return null;
            }
        }
        if (!level.getEntitiesOfClass(VehicleEntity.class, box.inflate(0.5)).isEmpty()) return null;
        return at;
    }

    /** Builds and adds the emplacement. Null when the world refused it. */
    @Nullable
    public static Entity place(ServerLevel level, Kit kit, Vec3 at, float yaw) {
        Entity e = kit.type() == ModEntities.MORTAR.get() && kit.entityNbt() == null
                ? new MortarEntity(level, yaw)
                : kit.type().create(level);
        if (e == null) return null;
        if (kit.entityNbt() != null) {
            CompoundTag data = kit.entityNbt().copy();
            data.remove("UUID");
            data.remove("Pos");
            e.load(data);
            e.setUUID(UUID.randomUUID());
        }
        e.moveTo(at.x, at.y, at.z, yaw, 0.0f);
        e.setYRot(yaw);
        e.setDeltaMovement(Vec3.ZERO);
        return level.addFreshEntity(e) ? e : null;
    }

    /** Put the placing unit on the weapon, the way each kind is crewed. */
    public static boolean man(PmcUnitEntity unit, Entity placed) {
        if (placed instanceof MortarEntity mortar) {
            MortarSupport.claim(unit, mortar);
            return true;
        }
        if (placed instanceof Type63Entity launcher) {
            Type63Support.claim(unit, launcher);
            return true;
        }
        if (placed instanceof VehicleEntity hull && !NpcVehicleOverrides.refusesNpcRiders(hull)) {
            BoardOrders.issueCleared(unit, hull.getId(), false);
            return true;
        }
        return false;
    }
}
