package com.neoalive.tacz_sewv.mixin.client;

import java.util.ArrayList;
import java.util.List;

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
 * Replace SBW's datapack vehicle-skin catalog with sewv filesystem skins.
 *
 * <p>Each plain file and each numbered RNG pool member is its own {@link SkinInfo} row
 * ({@code ru}, {@code ru_0}, {@code ru_1}, …) so the spray GUI can pick a specific camo.
 */
@Mixin(value = VehicleSkin.Companion.class, remap = false)
public abstract class MixinVehicleSkinCatalog {

    @Inject(method = "getSkins(Lnet/minecraft/world/entity/EntityType;)Lcom/atsuishio/superbwarfare/data/vehicle_skin/VehicleSkinData;",
            at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$sewvSkins(EntityType<?> type, CallbackInfoReturnable<VehicleSkinData> cir) {
        cir.setReturnValue(buildCatalog(type));
    }

    @Inject(method = "getSkin(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;)Lcom/atsuishio/superbwarfare/data/vehicle_skin/SkinInfo;",
            at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$sewvSkin(VehicleEntity entity, CallbackInfoReturnable<SkinInfo> cir) {
        cir.setReturnValue(resolve(entity));
    }

    @Unique
    private static VehicleSkinData buildCatalog(EntityType<?> type) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (typeId == null) {
            return new VehicleSkinData(List.of());
        }
        List<SkinInfo> skins = new ArrayList<>();
        int priority = 1;
        for (CatalogEntry entry : VehicleSkinRegistry.catalogFor(typeId.getPath())) {
            skins.add(new SkinInfo(
                    entry.id(),
                    entry.displayName(),
                    "Sewv faction paint",
                    entry.texture().toString(),
                    priority++));
        }
        return new VehicleSkinData(skins);
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
        // Prefer a catalog id that matches the resolved pool member so the spray highlight sticks.
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
