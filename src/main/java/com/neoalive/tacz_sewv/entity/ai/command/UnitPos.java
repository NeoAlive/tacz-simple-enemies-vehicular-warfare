package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Plain driver position for the pure grouping core — no world types.
 *
 * <p>{@code faction} is {@link com.neoalive.tacz_sewv.util.CrewFacts.Faction#ordinal()}.
 */
public final class UnitPos {

    public final int id;
    public final int faction;
    public final double x;
    public final double z;

    public UnitPos(int id, int faction, double x, double z) {
        this.id = id;
        this.faction = faction;
        this.x = x;
        this.z = z;
    }
}
