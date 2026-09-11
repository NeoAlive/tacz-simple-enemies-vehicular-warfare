package com.neoalive.tacz_sewv.mixin.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle_skin.SkinInfo;
import com.atsuishio.superbwarfare.data.vehicle_skin.VehicleSkin;
import com.atsuishio.superbwarfare.data.vehicle_skin.VehicleSkinData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.client.skin.VehicleSkinClient;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry.CatalogEntry;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry.CatalogId;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Soft-merge sewv filesystem skins onto SBW's datapack catalog.
 *
 * <p>Datapack rows win on id collision. Hulls with no sewv PNGs are untouched — other mods'
 * {@code sbw/vehicle_skins} entries keep working. {@link #resolve} only overrides
 * {@code getSkin} when sewv actually has art for that hull.
 */
@Mixin(value = VehicleSkin.Companion.class, remap = false)
public abstract class MixinVehicleSkinCatalog {

    @Inject(method = "getSkins(Lnet/minecraft/world/entity/EntityType;)Lcom/atsuishio/superbwarfare/data/vehicle_skin/VehicleSkinData;",
            at = @At("RETURN"), cancellable = true)
    private void tacz_sewv$mergeSkins(EntityType<?> type, CallbackInfoReturnable<VehicleSkinData> cir) {
        List<SkinInfo> sewv = buildSewvRows(type);
        if (sewv.isEmpty()) {
            return;
        }
        VehicleSkinData original = cir.getReturnValue();
        List<SkinInfo> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (original != null) {
            for (SkinInfo skin : original.getSkins()) {
                if (skin == null) continue;
                String id = skin.getId();
                if (id != null) seen.add(id);
                merged.add(skin);
            }
        }
        for (SkinInfo skin : sewv) {
            if (seen.add(skin.getId())) {
                merged.add(skin);
            }
        }
        cir.setReturnValue(new VehicleSkinData(merged));
    }

    @Inject(method = "getSkin(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;)Lcom/atsuishio/superbwarfare/data/vehicle_skin/SkinInfo;",
            at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$sewvSkin(VehicleEntity entity, CallbackInfoReturnable<SkinInfo> cir) {
        SkinInfo skin = resolve(entity);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }

    @Unique
    private static List<SkinInfo> buildSewvRows(EntityType<?> type) {
        List<SkinInfo> skins = new ArrayList<>();
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (typeId == null) return skins;
        int priority = 1;
        for (CatalogEntry entry : VehicleSkinRegistry.catalogFor(typeId.getPath())) {
            skins.add(new SkinInfo(
                    entry.id(),
                    entry.displayName(),
                    "Sewv faction paint",
                    entry.texture().toString(),
                    priority++));
        }
        return skins;
    }

    @Unique
    @Nullable
    private static SkinInfo resolve(VehicleEntity entity) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId == null) {
            return null;
        }
        String path = typeId.getPath();

        CatalogId fromId = VehicleSkinRegistry.parseSkinId(entity.getSkinId());
        if (fromId != null) {
            ResourceLocation texture = VehicleSkinRegistry.getExact(path, fromId.faction(), fromId.variant());
            if (texture == null && fromId.variant() < 0) {
                texture = VehicleSkinRegistry.get(path, fromId.faction(), VehicleSkinClient.salt(entity.getId()));
            }
            if (texture != null) {
                String id = entity.getSkinId() != null && !entity.getSkinId().isBlank()
                        ? entity.getSkinId().toLowerCase(java.util.Locale.ROOT)
                        : (fromId.variant() < 0
                                ? fromId.faction().name().toLowerCase(java.util.Locale.ROOT)
                                : fromId.faction().name().toLowerCase(java.util.Locale.ROOT)
                                        + "_" + fromId.variant());
                return new SkinInfo(id, fromId.faction().name(), "Sewv faction paint",
                        texture.toString(), fromId.faction().ordinal() + 1);
            }
        }

        CrewFacts.Faction sticky = VehicleSkinClient.get(entity.getId());
        if (sticky == null) {
            return null;
        }
        int salt = VehicleSkinClient.salt(entity.getId());
        ResourceLocation texture = VehicleSkinRegistry.get(path, sticky, salt);
        if (texture == null) {
            return null;
        }
        String id = sticky.name().toLowerCase(java.util.Locale.ROOT);
        for (CatalogEntry entry : VehicleSkinRegistry.catalogFor(path)) {
            if (entry.faction() == sticky && entry.texture().equals(texture)) {
                id = entry.id();
                break;
            }
        }
        return new SkinInfo(id, sticky.name(), "Sewv faction paint", texture.toString(),
                sticky.ordinal() + 1);
    }
}
