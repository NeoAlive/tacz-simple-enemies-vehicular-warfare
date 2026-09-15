package com.neoalive.tacz_sewv.skin;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.LogoPoolIndex;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.init.ModItems;

/**
 * Stamps the wearer's PMC logo onto {@code pmc_helmet_mich} via item NBT (equipment syncs, no
 * extra packet). Client composites that logo into the DogTag UV rect of the helmet texture.
 */
public final class HelmetLogoSupport {

    public static final String TAG_POOL = "sewv:helmet_logo_pool";
    public static final String TAG_LOGO = "sewv:helmet_logo_id";

    /** DogTag west-face UV on the 64×64 helmet atlas. */
    public static final int UV_X = 0;
    public static final int UV_Y = 42;
    public static final int UV_W = 24;
    public static final int UV_H = 14;

    private HelmetLogoSupport() {
    }

    public static boolean isMich(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.PMC_HELMET_MICH.get());
    }

    public static void stamp(ItemStack stack, String poolId, String logoId) {
        if (!isMich(stack)) return;
        if (!LogoPoolIndex.isValidIcon(poolId, logoId)) {
            poolId = PmcIdentityPreference.DEFAULT_POOL;
            logoId = PmcIdentityPreference.DEFAULT_LOGO;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_POOL, poolId);
        tag.putString(TAG_LOGO, logoId);
    }

    public static void stampDefaults(ItemStack stack) {
        stamp(stack, PmcIdentityPreference.DEFAULT_POOL, PmcIdentityPreference.DEFAULT_LOGO);
    }

    /** Resolve owner identity when possible, else defaults — stamps {@code stack} in place. */
    public static void stampForUnitStack(PmcUnitEntity unit, ItemStack stack) {
        if (!isMich(stack)) return;

        PmcIdentityPreference.PmcIdentity identity = PmcIdentityPreference.PmcIdentity.defaults();
        UUID owner = unit.getOwnerUUID();
        if (owner != null && unit.level() instanceof ServerLevel level) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(owner);
            if (sp != null) {
                identity = PmcIdentityPreference.get(sp);
            }
        }
        stamp(stack, identity.logoPool(), identity.logoId());
    }

    public static void stampForUnit(PmcUnitEntity unit) {
        ItemStack head = unit.getItemBySlot(EquipmentSlot.HEAD);
        if (!isMich(head)) return;
        stampForUnitStack(unit, head);
        unit.setItemSlot(EquipmentSlot.HEAD, head);
    }

    /** After TDT Apply — restamp every owned PMC's mich helmet in range. */
    public static void restampOwned(ServerPlayer owner) {
        PmcIdentityPreference.PmcIdentity identity = PmcIdentityPreference.get(owner);
        AABB box = owner.getBoundingBox().inflate(512.0);
        for (PmcUnitEntity unit : owner.level().getEntitiesOfClass(PmcUnitEntity.class, box,
                u -> owner.getUUID().equals(u.getOwnerUUID()))) {
            ItemStack head = unit.getItemBySlot(EquipmentSlot.HEAD);
            if (!isMich(head)) continue;
            stamp(head, identity.logoPool(), identity.logoId());
            unit.setItemSlot(EquipmentSlot.HEAD, head);
        }
    }

    @Nullable
    public static String poolOf(ItemStack stack) {
        if (!isMich(stack) || !stack.hasTag()) return null;
        String pool = stack.getTag().getString(TAG_POOL);
        return pool.isEmpty() ? null : pool;
    }

    @Nullable
    public static String logoOf(ItemStack stack) {
        if (!isMich(stack) || !stack.hasTag()) return null;
        String logo = stack.getTag().getString(TAG_LOGO);
        return logo.isEmpty() ? null : logo;
    }
}
