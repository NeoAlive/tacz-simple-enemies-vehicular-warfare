package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * Diagnostic-only: names why one of the player's own PMC units is <b>not shooting back</b>.
 *
 * <p>Added to reproduce the vague "my PMCs stop shooting after a while / won't shoot back" report,
 * which gives no state (on foot vs mounted, order, held item). Rather than guess-fix one of several
 * candidate causes, this dumps — once per unit per {@link #LOG_INTERVAL} game ticks, and only when
 * {@code pmcCombatDebugLogging} is on — the fields that tell those causes apart, at the exact moment
 * the symptom holds: an <em>owned</em> PMC that has been hit recently but has no live target.
 *
 * <p>Modelled on {@link SeekAbandonedVehicleGoal}: the whole check lives in {@link #canUse()}, which
 * then always returns {@code false}, so the goal never actually runs, holds no flags, and can never
 * contend with what the unit is doing. When the log is off it costs one boolean read per poll, the
 * same as every other always-registered gate goal here.
 */
public class PmcCombatDebugGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How recently (game ticks) the unit must have been attacked to count as "should be shooting back". */
    private static final int RECENT_HURT_TICKS = 100; // ~5 s

    /** Minimum game ticks between log lines for one unit — a permanently stuck unit logs periodically, not every tick. */
    private static final int LOG_INTERVAL = 100;

    private final PmcUnitEntity unit;

    /** Game time; goals tick every other tick, so a game-time deadline means the interval it says. */
    private long nextLog;

    public PmcCombatDebugGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.PMC_COMBAT_DEBUG_LOGGING.get()) return false;
        // Only the player's own units — the report is about the reporter's squad, not hostile PMC.
        if (this.unit.getOwnerUUID() == null) return false;

        long now = this.unit.level().getGameTime();
        if (now < this.nextLog) return false;

        // "Should be shooting back": hit within the last few seconds...
        LivingEntity attacker = this.unit.getLastHurtByMob();
        if (attacker == null || this.unit.tickCount - this.unit.getLastHurtByMobTimestamp() > RECENT_HURT_TICKS) {
            return false;
        }
        // ...but isn't — no live target to fire at. A held-but-DEAD target (the support-role freeze)
        // counts as not shooting back and is logged too, so the target field below names it.
        LivingEntity target = this.unit.getTarget();
        if (target != null && target.isAlive()) return false;

        this.nextLog = now + LOG_INTERVAL;
        log(attacker, target);
        return false; // evaluated; never actually run the goal
    }

    private void log(LivingEntity attacker, LivingEntity target) {
        String targetDesc = target == null ? "null"
                : target.getType().getDescriptionId() + "#" + target.getId() + (target.isAlive() ? "" : " DEAD");
        String ride = this.unit.getVehicle() instanceof VehicleEntity v
                ? v.getType().getDescriptionId() + " hp " + v.getHealth() + "/" + v.getMaxHealth()
                        + (v.isWreck() ? " WRECK" : "")
                : (this.unit.isPassenger() ? String.valueOf(this.unit.getVehicle()) : "on foot");

        LOGGER.info("[pmc-debug] unit {} not shooting back: order={} target={} role={} main={} off={} ride={} hitBy={}",
                this.unit.getId(),
                this.unit.getOrder(),
                targetDesc,
                SupportRole.of(this.unit),
                this.unit.getMainHandItem(),
                this.unit.getOffhandItem(),
                ride,
                attacker.getType().getDescriptionId() + "#" + attacker.getId());
    }
}
