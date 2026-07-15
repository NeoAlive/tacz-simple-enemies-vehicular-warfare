package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.init.ModSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

/**
 * A unit carrying a handheld radio calls its own contacts in to the mortars, turning any
 * rifleman into a forward observer.
 *
 * <p>The unit keeps fighting normally — this only relays whatever it has already found to
 * crews that could never have seen it themselves.
 */
public class RadioObserverGoal extends Goal {

    /** Game ticks between rolls. */
    private static final int CHECK_INTERVAL = 20;

    /**
     * One roll in this many actually calls a mission. A radio that phoned in every contact
     * the moment it saw one would keep every tube in the field permanently re-tasked to
     * whatever was last spotted; this makes a call an occasional thing.
     */
    private static final int CALL_CHANCE = 10;

    /**
     * How long to wait after a call finds nothing. Scanning for crews is the expensive
     * part of this goal, so a unit with no mortars behind it — the normal case — backs
     * off instead of paying for that scan on every roll.
     */
    private static final int NO_CREWS_BACKOFF = 100;

    private final PmcUnitEntity unit;

    /** Game time, so the intervals mean the ticks they say; goals tick every other tick. */
    private long nextCheck;

    public RadioObserverGoal(PmcUnitEntity unit) {
        this.unit = unit;
        // Claims nothing: calling it in over the radio doesn't move, look at, or target
        // anything, so this must never contend with what the unit is actually doing.
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;

        // Cheapest checks first — canUse is polled constantly on every unit, and most of
        // them have no target and no radio.
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (this.unit.getOwnerUUID() == null) return false;
        return HandheldRadioItem.isCarriedBy(this.unit);
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        if (now < this.nextCheck) return;
        this.nextCheck = now + CHECK_INTERVAL;

        if (this.unit.getRandom().nextInt(CALL_CHANCE) != 0) return;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return;

        int ordered = MortarSupport.callFireMission(
                this.unit.level(), this.unit.getOwnerUUID(), this.unit.position(),
                SewvConfig.MORTAR_RADIO_RANGE.get(), target);

        if (ordered == 0) {
            this.nextCheck = now + NO_CREWS_BACKOFF;
        } else {
            this.unit.level().playSound(null, this.unit.blockPosition(), ModSounds.MORTAR_AFFIRMATIVE.get(),
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }
}
