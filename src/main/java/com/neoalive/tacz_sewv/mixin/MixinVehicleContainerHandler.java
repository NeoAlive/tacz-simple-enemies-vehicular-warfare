package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.inventory.handler.VehicleContainerHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.util.CreativeAmmoConsumable;

/**
 * Any {@code superbwarfare:creative_ammo_box} written into an SBW vehicle container — spawn
 * stock, GUI click, capability insert, NBT load — is stamped {@code sewv_consumable} so it
 * cannot be looted as a free infinite-ammo box. Non-vehicle {@link ItemStackHandler}s are
 * untouched.
 */
@Mixin(value = ItemStackHandler.class, remap = false)
public abstract class MixinVehicleContainerHandler {

    @Inject(method = "setStackInSlot", at = @At("HEAD"))
    private void tacz_sewv$stampCreativeAmmo(int slot, ItemStack stack, CallbackInfo ci) {
        if (!((Object) this instanceof VehicleContainerHandler)) return;
        CreativeAmmoConsumable.stamp(stack);
    }
}
