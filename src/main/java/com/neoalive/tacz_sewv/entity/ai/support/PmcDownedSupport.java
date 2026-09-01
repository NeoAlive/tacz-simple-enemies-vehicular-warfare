package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.network.PacketReviveProgress;

/**
 * PMC-only "downed, not dead" mechanic. RU/US simply die — only a PMC is worth a player's
 * investment (recruited, equipped, ordered) enough to be worth a second chance, and only PMC
 * implements {@link IPmcDowned}.
 *
 * <p>This is entirely this mod's own state — it never touches the PlayerReviveMod soft-compat used
 * for downed <em>players</em> ({@code PlayerReviveCompat}), whose capability only ever attaches to
 * {@code Player} entities and so has nothing to say about an NPC. It is nonetheless gated on that
 * mod's presence ({@link #onDeath}), same as {@code DownedGoal}/{@code PmcReviveGoal}'s goal-add
 * gate in {@code MixinPmcUnitEntity}: PlayerReviveMod is an optional dependency, and a downed-PMC
 * system is bundled under that same optionality rather than behaving differently from the
 * downed-player half depending on an unrelated mod's presence.
 *
 * <p>Two ways back up: a player holding attack while looking at a downed PMC
 * ({@link #handleHoldRevive}, fed by {@code PacketHoldRevive}/{@code ReviveHoldInput}), or another
 * PMC's medic channeling one ({@code PmcReviveGoal}, calls {@link #revive} once done) — both funnel
 * through the same {@link #revive} so there is exactly one "how does a downed PMC come back"
 * implementation. {@code DownedGoal} is the other half: it freezes a downed unit in place and kills
 * it for real if {@link #onDeath}'s deadline passes unrevived.
 *
 * <p><b>Player revive is entirely client-poll-driven, not event-driven</b> — {@link #CHANNELS}
 * tracks one in-progress revive per player (target entity id + ticks elapsed), advanced by
 * {@link #handleHoldRevive} exactly once per {@code PacketHoldRevive} received. There is no separate
 * server tick loop for this: since the client sends a fresh packet every tick it is genuinely
 * holding, "advance on packet arrival" already IS "advance every tick, while actually held" — and it
 * makes the channel naturally stale-safe against a client that stops sending (dropped connection,
 * crash): with nothing arriving, nothing advances, and it simply sits inert until
 * {@link #onPlayerLoggedOut} clears it (or a fresh packet corrects it, if the client is still alive
 * and just changed target). Every value is re-validated on every packet (still downed, still alive,
 * still in range, still friendly) rather than trusted from a prior tick — the "reviver backs away" /
 * "downed dies from other damage" / "reviver dies" cases all fall out of that same re-validation
 * rather than needing bespoke handling: the very next packet (or {@link #onAttack} /
 * {@link #onPlayerLoggedOut} for the cases a packet can't reach) drops the channel and clears the
 * ring.
 *
 * <p>{@link #onAttack} separately blocks ordinary melee damage against a downed friendly PMC
 * entirely — holding the same key that revives them must never be able to instead finish them off
 * by accident.
 *
 * <p>Visual: PlayerReviveMod poses a downed <em>player</em> via {@code setForcedPose}, its own
 * mixin-injected method — not reachable from an unrelated class like {@code PmcUnitEntity}, and not
 * needed here anyway: SEM's {@code PmcUnitModel} never reads vanilla {@code Pose} at all (its
 * {@code setupAnim} picks purely from its own walk/idle/hurt/death {@code AnimationState}s). The
 * actual downed pose is a client-side bone override ({@code MixinPmcDownedPose} /
 * {@code DownedUnitPose}, keyed off {@link IPmcDowned#sewv$isDownedSynced}) — this class only flips
 * that synced flag alongside the durable {@link IPmcDowned#sewv$setDowned} state.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class PmcDownedSupport {

    /** How far a player may drift from their revive target before the channel drops. */
    private static final double CHANNEL_MAX_DISTANCE_SQ = 36.0;

    /** One in-progress player-revives-PMC channel per player. */
    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();

    private record Channel(int targetId, int ticksElapsed) {}

    private PmcDownedSupport() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PmcUnitEntity pmc)) return;
        if (!(pmc instanceof IPmcDowned downed)) return;
        if (!SewvConfig.PMC_DOWNED_ENABLED.get()) return;
        // Optional dependency: see the class doc for why this bundles under PlayerReviveMod's
        // presence even though it never calls into that mod's own API.
        if (!PlayerReviveCompat.isLoaded()) return;
        // Already downed once: this hit is what finishes them for real — let the death proceed.
        if (downed.sewv$isDowned()) return;

        event.setCanceled(true);
        downed.sewv$setDowned(true, pmc.level().getGameTime() + SewvConfig.PMC_DOWNED_BLEED_TICKS.get());
        downed.sewv$setDownedSynced(true);
        pmc.setHealth((float) Math.max(1.0, SewvConfig.PMC_DOWNED_HEALTH.get()));
        OrderStandDown.clearAll(pmc, "PmcDownedSupport.onDeath");
    }

    /**
     * A downed friendly PMC can never be melee'd — including by accident from holding the same
     * attack key used to revive them. Only the friendly/downed case is blocked; an enemy or an
     * un-downed PMC is untouched.
     */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof PmcUnitEntity pmc)) return;
        if (!(pmc instanceof IPmcDowned downed) || !downed.sewv$isDowned()) return;
        if (!VehicleTargeting.isFriendlyPlayer(pmc, event.getEntity())) return;
        event.setCanceled(true);
    }

    /**
     * One packet's worth of "still holding" — advances that player's channel by exactly one step,
     * or drops it if anything no longer checks out. {@code targetId < 0} means "not holding" and
     * always drops the channel outright.
     */
    public static void handleHoldRevive(ServerPlayer player, int targetId) {
        if (targetId < 0) {
            CHANNELS.remove(player.getUUID());
            PacketReviveProgress.sendTo(player, 0.0F, false);
            return;
        }
        if (!SewvConfig.PMC_DOWNED_ENABLED.get() || !player.isAlive()) return;

        Entity targetEntity = player.level().getEntity(targetId);
        if (!(targetEntity instanceof PmcUnitEntity pmc)
                || !(pmc instanceof IPmcDowned downed)
                || !downed.sewv$isDowned()
                || !pmc.isAlive()
                || player.distanceToSqr(pmc) > CHANNEL_MAX_DISTANCE_SQ
                || !VehicleTargeting.isFriendlyPlayer(pmc, player)) {
            CHANNELS.remove(player.getUUID());
            PacketReviveProgress.sendTo(player, 0.0F, false);
            return;
        }

        Channel existing = CHANNELS.get(player.getUUID());
        int ticksElapsed = (existing != null && existing.targetId() == targetId)
                ? existing.ticksElapsed() + 1 : 1;
        int total = SewvConfig.PMC_REVIVE_CHANNEL_TICKS.get();
        if (ticksElapsed >= total) {
            revive(pmc);
            CHANNELS.remove(player.getUUID());
            PacketReviveProgress.sendTo(player, 1.0F, false);
            return;
        }
        CHANNELS.put(player.getUUID(), new Channel(targetId, ticksElapsed));
        PacketReviveProgress.sendTo(player, (float) ticksElapsed / total, true);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CHANNELS.remove(event.getEntity().getUUID());
    }

    /** Clears the downed state and restores a fraction of health. Idempotent past the first call. */
    public static void revive(PmcUnitEntity pmc) {
        if (!(pmc instanceof IPmcDowned downed) || !downed.sewv$isDowned()) return;
        downed.sewv$setDowned(false, 0L);
        downed.sewv$setDownedSynced(false);
        pmc.setHealth((float) Math.min(pmc.getMaxHealth(), SewvConfig.PMC_DOWNED_REVIVE_HEALTH.get()));
    }
}
