package com.neoalive.tacz_sewv.platform;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * NeoForge port: an item's free-form NBT is the {@link DataComponents#CUSTOM_DATA} component on 1.21,
 * and that component is immutable. Reads get a copy; every write goes through {@link #update}, the
 * replacement for mutating {@code getOrCreateTag()} in place. TaCZ 1.21 keeps its gun keys there
 * too, so this covers its tags as well as ours.
 */
public final class StackTag {

    private StackTag() {}

    public static boolean has(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_DATA);
    }

    /** A copy of the stack's tag, or null when it has none. Writing to it changes nothing. */
    @Nullable
    public static CompoundTag get(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** A copy of the stack's tag, empty when it has none. */
    public static CompoundTag getOrEmpty(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /** Edits the stack's tag (creating it if absent) and stores the result back on the stack. */
    public static void update(ItemStack stack, Consumer<CompoundTag> edit) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, edit);
    }
}
