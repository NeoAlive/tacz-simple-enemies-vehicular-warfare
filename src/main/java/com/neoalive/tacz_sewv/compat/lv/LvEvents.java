package com.neoalive.tacz_sewv.compat.lv;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * The two LV mount rules that are events rather than goals. Imports no LV type, so it is
 * registered unconditionally and checks for the mod itself.
 *
 * <p><b>Keep the crew aboard through the first-tick purge.</b> {@code AbstractVehicle.tick} throws
 * every passenger out when {@code tickCount == 1}, and {@code tickCount} is not saved — so that
 * tick is the first one after EVERY load, and every chunk reload would leave an LV crew standing
 * beside its tank. By then the passengers already hold their seats (the saved hull builds its
 * seats in {@code readAdditionalSaveData}, before vanilla re-attaches the passengers, and that
 * attach assigns them), so for our units the dismount is simply refused. It is refused here, at
 * Forge's dismount event ({@code Entity.removeVehicle} aborts when it is cancelled), and not by a
 * mixin into the hull: transforming LV's {@code AbstractVehicle} recomputes frames that name its
 * client-only {@code LocalVehiclePlayer}, which takes a dedicated server down at mod construction.
 *
 * <p><b>Remember that a player has ridden a hull.</b> SBW keeps a last-driver UUID that
 * {@code SeekAbandonedVehicleGoal} uses to leave a player's tank alone; LV has no such field, so
 * this writes the equivalent into the hull's persistent data.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class LvEvents {

    /** Persistent-data flag on an LV hull a player has ridden. Read by {@code LvHulls.findAbandoned}. */
    public static final String TAG_PLAYER_DRIVEN = "tacz_sewv_lv_player";

    private LvEvents() {}

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity hull = event.getEntityBeingMounted();
        if (!LvCompat.isLvHull(hull)) return;
        if (event.isMounting()) {
            if (event.getEntityMounting() instanceof Player) {
                hull.getPersistentData().putBoolean(TAG_PLAYER_DRIVEN, true);
            }
        } else if (hull.tickCount == 1
                && event.getEntityMounting() instanceof AbstractUnit
                && !LvHulls.isDestroyed(hull)) {
            event.setCanceled(true);
        }
    }
}
