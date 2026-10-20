package com.neoalive.tacz_sewv.init;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.inventory.StockpileMenu;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, TaczSewv.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<StockpileMenu>> STOCKPILE =
            MENUS.register("stockpile", () -> IMenuTypeExtension.create(StockpileMenu::new));

    private ModMenus() {}
}
