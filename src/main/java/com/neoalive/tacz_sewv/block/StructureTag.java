package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.init.ModBlocks;

/**
 * "Structure" flag for kit blocks that a native (world-generated) structure will one day place.
 * A flagged block is a plain, unowned block: no GUI, no use, but it digs out near-instantly, and
 * because the flag lives in the block entity's Forge persistent data it is gone the moment the
 * block breaks (the loot tables copy no NBT), so the dropped item is a normal one.
 *
 * <p>Eligibility is the {@code tacz_sewv:structure_eligible} block tag; the flag is applied only by
 * {@code /sewv debug applyStructureTag}. Handling is server-side only; the client never learns the
 * flag, so its break animation is the normal one (the server breaks the block first).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class StructureTag {

    public static final TagKey<Block> ELIGIBLE =
            TagKey.create(Registries.BLOCK, new ResourceLocation(TaczSewv.MODID, "structure_eligible"));
    private static final String KEY = "sewv_structure";

    private StructureTag() {}

    public static boolean eligible(BlockState state) {
        return state.is(ELIGIBLE);
    }

    /** The helipad keeps its block entity on the CENTER part only. */
    private static BlockEntity holder(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return level.getBlockEntity(state.getBlock() instanceof HelipadBlock ? HelipadBlock.masterOf(pos, state) : pos);
    }

    public static boolean has(BlockGetter level, BlockPos pos) {
        if (!eligible(level.getBlockState(pos))) return false;
        BlockEntity be = holder(level, pos);
        return be != null && be.getPersistentData().getBoolean(KEY);
    }

    /**
     * Flag the block and wipe everything it held: the block entity is swapped for a fresh one (no
     * owner, contents or cleared strip; {@code setRemoved} makes runway/helipad forget their
     * registry entry) and the FOB registration is dropped. The flag goes on before the unlink
     * because the unlink re-scans the FOB, which must already skip this block.
     *
     * @return false if the block is not eligible or has no block entity to carry the flag.
     */
    public static boolean apply(ServerLevel level, BlockPos pos) {
        BlockState clicked = level.getBlockState(pos);
        if (!eligible(clicked)) return false;
        BlockPos at = clicked.getBlock() instanceof HelipadBlock ? HelipadBlock.masterOf(pos, clicked) : pos;
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof EntityBlock eb)) return false;
        BlockEntity fresh = eb.newBlockEntity(at, state);
        if (fresh == null) return false;
        level.removeBlockEntity(at);
        level.setBlockEntity(fresh);
        fresh.getPersistentData().putBoolean(KEY, true);
        fresh.setChanged();
        if (state.is(ModBlocks.QUARTERS_BENCH.get())) {
            FobManager.get(level).removeFob(at, level);
        } else if (state.is(ModBlocks.STOCKPILE_AMMO.get()) || state.is(ModBlocks.PARKING_FIELD.get())) {
            FobSubBlock.onSubRemoved(level, at);
        }
        return true;
    }

    private static void hint(net.minecraft.world.entity.player.Player player, BlockState state) {
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.structure.break_to_use", state.getBlock().getName()), true);
    }

    /** Deny the block's own use (GUI) only; placing against it still works like any block. */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !has(event.getLevel(), event.getPos())) return;
        event.setUseBlock(Event.Result.DENY);
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            hint(event.getEntity(), event.getLevel().getBlockState(event.getPos()));
        }
    }

    /** Not cancelled: a flagged block must stay breakable. */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                || !has(event.getLevel(), event.getPos())) return;
        hint(event.getEntity(), event.getLevel().getBlockState(event.getPos()));
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        event.getPosition().ifPresent(pos -> {
            Level level = event.getEntity().level();
            if (!level.isClientSide && has(level, pos)) event.setNewSpeed(1_000_000f);
        });
    }
}
