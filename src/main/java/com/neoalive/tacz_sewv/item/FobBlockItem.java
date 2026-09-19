package com.neoalive.tacz_sewv.item;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import com.neoalive.tacz_sewv.client.FobItemRenderer;

/**
 * FOB block item drawn from its geo model rather than a flat texture. The per-context
 * transforms (gui / hand / ground / fixed) come from the item's own {@code display} block in
 * {@code models/item/<name>.json}, which is {@code builtin/entity} and copied from the geo's
 * {@code item_display_transforms}.
 */
public class FobBlockItem extends BlockItem {

    public FobBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    Minecraft mc = Minecraft.getInstance();
                    this.renderer = new FobItemRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                }
                return this.renderer;
            }
        });
    }
}
