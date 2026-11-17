package com.neoalive.tacz_sewv.command.quick.scavenge;

/** Scavenge priority, worked in this order: a unit finishes one stage before the next. */
public enum ScavengeStage {
    AMMO, EMPLACEMENT, UTILITY, ARMOR;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
