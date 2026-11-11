package com.neoalive.tacz_sewv.item;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.init.ModItems;

/**
 * Bound to up to {@link #MAX_BINDINGS} FPAS arrays (use on one to bind, again to unbind). Carrying
 * one with a binding unlocks the quickwheel Signal leaves, which order off every bound MTI radar.
 */
public class SignalRelayItem extends Item {

    public static final int MAX_BINDINGS = 8;
    private static final String TAG = "SignalRadars";

    public SignalRelayItem() {
        super(new Properties().stacksTo(1));
    }

    public record Binding(String dimension, BlockPos pos) {}

    public static List<Binding> bindings(ItemStack stack) {
        List<Binding> out = new ArrayList<>();
        if (stack.getTag() == null) return out;
        for (Tag t : stack.getTag().getList(TAG, Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            out.add(new Binding(c.getString("Dim"), BlockPos.of(c.getLong("Pos"))));
        }
        return out;
    }

    private static void write(ItemStack stack, List<Binding> list) {
        ListTag tag = new ListTag();
        for (Binding b : list) {
            CompoundTag c = new CompoundTag();
            c.putString("Dim", b.dimension());
            c.putLong("Pos", b.pos().asLong());
            tag.add(c);
        }
        stack.getOrCreateTag().put(TAG, tag);
    }

    /** The relay the player carries with at least one binding: offhand first, then the inventory. */
    public static ItemStack findBound(Player player) {
        if (isBound(player.getOffhandItem())) return player.getOffhandItem();
        for (ItemStack stack : player.getInventory().items) {
            if (isBound(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static boolean isBound(ItemStack stack) {
        return stack.is(ModItems.SIGNAL_RELAY.get()) && stack.getTag() != null
                && !stack.getTag().getList(TAG, Tag.TAG_COMPOUND).isEmpty();
    }

    /** Every bound FPAS still standing and loaded. Bindings whose array is gone are pruned. */
    public static List<ZoneBeaconBlockEntity> resolve(ServerPlayer player, ItemStack stack) {
        List<ZoneBeaconBlockEntity> out = new ArrayList<>();
        List<Binding> keep = new ArrayList<>();
        for (Binding b : bindings(stack)) {
            ResourceLocation dim = ResourceLocation.tryParse(b.dimension());
            ServerLevel level = dim == null ? null
                    : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
            if (level == null || !level.isLoaded(b.pos())) {
                keep.add(b); // unloaded is not gone
                continue;
            }
            if (level.getBlockEntity(b.pos()) instanceof ZoneBeaconBlockEntity be) {
                keep.add(b);
                out.add(be);
            }
        }
        if (keep.size() != bindings(stack).size()) write(stack, keep);
        return out;
    }

    /** Runs before the block's own use, which would open the FPAS panel instead. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack unused, UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level.getBlockEntity(ctx.getClickedPos()) instanceof ZoneBeaconBlockEntity be)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!be.mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.zone_beacon.not_owner")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        ItemStack stack = ctx.getItemInHand();
        List<Binding> list = bindings(stack);
        Binding here = new Binding(level.dimension().location().toString(), ctx.getClickedPos().immutable());
        String msg;
        if (list.remove(here)) {
            msg = "message.tacz_sewv.signal_relay.unbound";
        } else if (list.size() >= MAX_BINDINGS) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.signal_relay.full", MAX_BINDINGS)
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        } else {
            list.add(here);
            msg = "message.tacz_sewv.signal_relay.bound";
        }
        write(stack, list);
        player.displayClientMessage(Component.translatable(msg, list.size(), MAX_BINDINGS)
                .withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tacz_sewv.signal_relay.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tacz_sewv.signal_relay.bindings", bindings(stack).size(),
                MAX_BINDINGS).withStyle(ChatFormatting.DARK_AQUA));
    }
}
