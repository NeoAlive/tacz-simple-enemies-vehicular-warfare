package com.neoalive.tacz_sewv.init;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.inventory.StockpileMenu;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, TaczSewv.MODID);

    public static final RegistryObject<MenuType<StockpileMenu>> STOCKPILE =
            MENUS.register("stockpile", () -> IForgeMenuType.create(StockpileMenu::new));

    private ModMenus() {}
}
