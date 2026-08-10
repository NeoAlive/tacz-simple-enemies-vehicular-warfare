package com.neoalive.tacz_sewv.init;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Dedicated creative inventory tab for every SEWV item (except {@code trench_x_cross}, which
 * stays registered for worldgen/junctions but is not offered in creative).
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TaczSewv.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tacz_sewv"))
                    .icon(() -> new ItemStack(ModItems.HANDHELD_RADIO.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.HANDHELD_RADIO.get());
                        output.accept(ModItems.TACTICAL_DATA_TERMINAL.get());
                        output.accept(ModItems.DOCTRINE_LEDGER.get());
                        output.accept(ModItems.POOL_CLIPBOARD.get());
                        output.accept(ModItems.CAPTURE_POINT.get());
                        output.accept(ModItems.TEAM_BASE.get());
                        output.accept(ModItems.TRENCH.get());
                        output.accept(ModItems.FOXHOLE.get());
                        output.accept(ModItems.EMPLACEMENT.get());
                        output.accept(ModItems.SANDBAG.get());
                        output.accept(ModItems.RU_MEDIC_SPAWN_EGG.get());
                        output.accept(ModItems.US_MEDIC_SPAWN_EGG.get());
                        output.accept(ModItems.RU_ENGINEER_SPAWN_EGG.get());
                        output.accept(ModItems.US_ENGINEER_SPAWN_EGG.get());
                        output.accept(ModItems.RU_COMBAT_ENGINEER_SPAWN_EGG.get());
                        output.accept(ModItems.US_COMBAT_ENGINEER_SPAWN_EGG.get());
                    })
                    .build());

    private ModCreativeTabs() {}
}
