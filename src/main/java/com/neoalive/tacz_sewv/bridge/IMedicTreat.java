package com.neoalive.tacz_sewv.bridge;

/**
 * Synched treating flag for units that run
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.MedicGoal}. RU/US medics own the fields; PMC gets
 * them via {@code MixinPmcUnitEntity}.
 */
public interface IMedicTreat {

    boolean sewv$isTreating();

    void sewv$setTreating(boolean treating);
}
