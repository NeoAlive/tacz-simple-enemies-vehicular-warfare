package com.neoalive.tacz_sewv.mixin.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Replace SBW's datapack vehicle-skin catalog with sewv's filesystem faction skins.
 *
 * <p>The spray GUI and {@code GeoVehicleRenderer}'s native skin apply both read through
 * {@link VehicleSkin.Companion}; feeding them sewv entries (and only sewv entries) removes the
 * truck-green style datapack skins without writing a parallel GUI.
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
        String path = typeId.getPath();
        List<SkinInfo> skins = new ArrayList<>(3);
        for (CrewFacts.Faction faction : VehicleSkinRegistry.factionsFor(path)) {
            ResourceLocation texture = VehicleSkinRegistry.get(path, faction, 0);
            if (texture == null) continue;
            String id = faction.name().toLowerCase(Locale.ROOT);
            skins.add(new SkinInfo(
                    id,
                    faction.name(),
                    "Sewv faction paint",
                    texture.toString(),
                    faction.ordinal() + 1));
        }
        return new VehicleSkinData(skins);
    }

    @Unique
    @Nullable
    private static SkinInfo resolve(VehicleEntity entity) {
        CrewFacts.Faction faction = factionOf(entity);
        if (faction == null) {
            return null;
        }
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId == null) {
            return null;
        }
        int salt = VehicleSkinClient.salt(entity.getId());
        ResourceLocation texture = VehicleSkinRegistry.get(typeId.getPath(), faction, salt);
        if (texture == null) {
            return null;
        }
        String id = faction.name().toLowerCase(Locale.ROOT);
        return new SkinInfo(id, faction.name(), "Sewv faction paint", texture.toString(),
                faction.ordinal() + 1);
    }

    @Unique
    @Nullable
    private static CrewFacts.Faction factionOf(VehicleEntity entity) {
        CrewFacts.Faction sticky = VehicleSkinClient.get(entity.getId());
        if (sticky != null) {
            return sticky;
        }
        String skinId = entity.getSkinId();
        if (skinId == null || skinId.isBlank()) {
            return null;
        }
        return switch (skinId.toLowerCase(Locale.ROOT)) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
    }
}
