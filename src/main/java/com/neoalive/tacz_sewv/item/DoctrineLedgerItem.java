package com.neoalive.tacz_sewv.item;

import com.neoalive.tacz_sewv.client.gui.DoctrineEditorScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class DoctrineLedgerItem extends Item {

    private static final String TAG_OWNER = "sewv_doctrine_owner";

    public DoctrineLedgerItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static void setOwner(ItemStack stack, UUID owner) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(TAG_OWNER, owner);
    }

    @Nullable
    public static UUID getOwner(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.hasUUID(TAG_OWNER)) {
            return tag.getUUID(TAG_OWNER);
        }
        return null;
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        if (!level.isClientSide) {
            setOwner(stack, player.getUUID());
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            UUID owner = getOwner(stack);
            if (owner != null && owner.equals(player.getUUID())) {
                openEditorScreen();
            } else {
                player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.not_owner").withStyle(ChatFormatting.RED), true);
            }
        }
        return InteractionResultHolder.success(stack);
    }

    @OnlyIn(Dist.CLIENT)
    private void openEditorScreen() {
        Minecraft.getInstance().setScreen(new DoctrineEditorScreen());
    }
}
