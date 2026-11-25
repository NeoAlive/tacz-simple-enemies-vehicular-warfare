package com.neoalive.tacz_sewv.crew;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.utility.DoctrineKnobs;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.network.PacketDoctrineSync;

/**
 * Owned PMC units gain experience by fighting: XP on the unit ({@link #TAG_XP}, persistent), levels and their effects
 * in {@link DoctrineKnobs}. Every point also feeds the owner's command rating, which grows their ledger budget.
 *
 * <p>Kills are credited to the unit that made them, or to every owned PMC aboard the hull that did. Riders are not
 * credited when they die in their seat; the hull kill is, once, from {@code MixinVehicleVoicelines.destroy}. This is
 * independent of the crew radio, so turning voice lines off never stops progression.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class Veterancy {

    public static final String TAG_XP = "sewv:vet_xp";
    private static final String TAG_HULL_CREDITED = "sewv:vet_credited";
    private static final String TAG_SURVIVED_AT = "sewv:vet_survived_at";

    static final int KILL_XP = 3;
    static final int HULL_KILL_XP = 6;
    static final int SURVIVE_XP = 1;
    /** At most one survival award per hull in this many ticks, so a flickering target cannot farm XP. */
    private static final long SURVIVE_COOLDOWN_TICKS = 600L;

    private Veterancy() {}

    public static int xp(Entity unit) {
        return unit.getPersistentData().getInt(TAG_XP);
    }

    public static int level(Entity unit) {
        return unit instanceof PmcUnitEntity ? DoctrineKnobs.veterancyLevel(xp(unit)) : 0;
    }

    /** The crew's level: the mean of the PMC aboard, rounded down. 0 for anything else. */
    public static int crewLevel(Entity hull) {
        int sum = 0;
        int count = 0;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof PmcUnitEntity) {
                sum += level(passenger);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    // ---- awarding ------------------------------------------------------------------------------------------------

    /**
     * What one event earned one owner. The action bar holds a single line, so promotions and a new ledger point from
     * the same kill are gathered here and announced once, rather than each overwriting the last.
     */
    private static final class Report {
        final ServerPlayer player;
        Component promoted;
        int promotions;
        boolean budgetUp;

        Report(ServerPlayer player) {
            this.player = player;
        }

        void send() {
            if (this.promotions == 0 && !this.budgetUp) return;
            MutableComponent line = Component.empty();
            if (this.promotions == 1) line.append(this.promoted);
            else if (this.promotions > 1) {
                line.append(Component.translatable("message.tacz_sewv.veterancy.promoted_many", this.promotions));
            }
            if (this.budgetUp) {
                if (this.promotions > 0) line.append("   ");
                line.append(Component.translatable("message.tacz_sewv.veterancy.budget",
                        PlayerDoctrineData.get(this.player.level()).budget(this.player.getUUID())));
                PacketDoctrineSync.sendTo(this.player);
            }
            this.player.displayClientMessage(line.withStyle(ChatFormatting.GOLD), true);
        }
    }

    static void award(PmcUnitEntity pmc, int amount, Map<UUID, Report> reports) {
        UUID owner = pmc.getOwnerUUID();
        if (owner == null || amount <= 0 || pmc.level().isClientSide()) return;
        CompoundTag tag = pmc.getPersistentData();
        int before = DoctrineKnobs.veterancyLevel(tag.getInt(TAG_XP));
        tag.putInt(TAG_XP, tag.getInt(TAG_XP) + amount);
        int after = DoctrineKnobs.veterancyLevel(tag.getInt(TAG_XP));

        MinecraftServer server = pmc.getServer();
        if (server == null) return;
        boolean budgetUp = PlayerDoctrineData.get(pmc.level()).addRating(owner, amount);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || (after <= before && !budgetUp)) return;
        Report report = reports.computeIfAbsent(owner, k -> new Report(player));
        if (after > before) {
            report.promotions++;
            report.promoted = Component.translatable("message.tacz_sewv.veterancy.promoted",
                    pmc.getDisplayName(), Component.translatable("gui.tacz_sewv.veterancy." + after));
        }
        report.budgetUp |= budgetUp;
    }

    private static void announce(Map<UUID, Report> reports) {
        for (Report report : reports.values()) report.send();
    }

    /** Credits {@code amount} to whoever {@code attacker} is: an owned PMC on foot, or every owned PMC of a hull. */
    static void credit(Entity attacker, Entity victim, int amount) {
        Map<UUID, Report> reports = new HashMap<>();
        if (attacker instanceof PmcUnitEntity pmc && pmc.getVehicle() == null) {
            if (!sameSide(pmc, victim)) award(pmc, amount, reports);
            announce(reports);
            return;
        }
        VehicleEntity hull = attacker instanceof VehicleEntity v ? v
                : attacker instanceof AbstractUnit u && u.getVehicle() instanceof VehicleEntity v ? v : null;
        if (hull == null) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof PmcUnitEntity pmc && !sameSide(pmc, victim)) award(pmc, amount, reports);
        }
        announce(reports);
    }

    /** No XP for killing your own units or yourself. */
    private static boolean sameSide(PmcUnitEntity pmc, Entity victim) {
        UUID owner = pmc.getOwnerUUID();
        if (owner == null) return true;
        if (victim instanceof Player p) return owner.equals(p.getUUID());
        if (victim instanceof PmcUnitEntity other) return owner.equals(other.getOwnerUUID());
        if (victim instanceof VehicleEntity hull) {
            for (Entity passenger : hull.getPassengers()) {
                if (passenger instanceof PmcUnitEntity other && owner.equals(other.getOwnerUUID())) return true;
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim instanceof AbstractUnit) && !(victim instanceof Player)) return;
        if (victim.getVehicle() instanceof VehicleEntity) return; // the hull kill is credited on destroy
        Entity attacker = event.getSource().getEntity();
        if (attacker != null) credit(attacker, victim, KILL_XP);
    }

    /** A hull was destroyed: credit its killer once. Called from the hull's destroy hook. */
    public static void onHullDestroyed(VehicleEntity hull, Entity attacker) {
        if (hull.level().isClientSide() || attacker == null) return;
        CompoundTag data = hull.getPersistentData();
        if (data.getBoolean(TAG_HULL_CREDITED)) return;
        data.putBoolean(TAG_HULL_CREDITED, true);
        if (hull.getPassengers().isEmpty()) return; // an empty hull is not a fight
        credit(attacker, hull, HULL_KILL_XP);
    }

    /** A crew came out of contact with its hull intact. */
    public static void onEngagementSurvived(VehicleEntity hull) {
        if (hull.level().isClientSide() || hull.isWreck()) return;
        long now = hull.level().getGameTime();
        CompoundTag data = hull.getPersistentData();
        if (data.contains(TAG_SURVIVED_AT) && now - data.getLong(TAG_SURVIVED_AT) < SURVIVE_COOLDOWN_TICKS) return;
        data.putLong(TAG_SURVIVED_AT, now);
        Map<UUID, Report> reports = new HashMap<>();
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof PmcUnitEntity pmc) award(pmc, SURVIVE_XP, reports);
        }
        announce(reports);
    }
}
