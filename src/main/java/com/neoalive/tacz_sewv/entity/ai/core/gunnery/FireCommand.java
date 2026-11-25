package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.List;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.entity.ai.core.gunnery.Round.Kind;

/**
 * The fire command: per target class, which kinds of round a gunner uses and in what order. The
 * damage card only filters; it never reorders. Battlecarry is in the step order: nothing changes
 * the ammo type while anything on the list can fire now.
 */
public final class FireCommand {

    public record Entry(Kind kind, boolean lastResort) {}

    /**
     * One candidate as the walk sees it. {@code chambered} = its consumer is the one selected on its
     * slot, so firing it needs no ammo change. {@code usable} folds in stock, the card, a missile
     * being ready and lined up, and the grapeshot cone; {@code why} names the first thing that failed.
     */
    public record Option(Round round, boolean chambered, boolean usable, double cardDamage,
                         @Nullable String why) {}

    public record Pick(Option option, int step) {
        public boolean ammoChange() {
            return !option.chambered();
        }
    }

    private static final List<Entry> TROOPS = List.of(
            new Entry(Kind.COAX, false), new Entry(Kind.GRAPESHOT, false), new Entry(Kind.HE, false));
    private static final List<Entry> HARD_ARMOUR = List.of(
            new Entry(Kind.PENETRATOR, false), new Entry(Kind.MISSILE, false), new Entry(Kind.HEAT, false));
    private static final List<Entry> LIGHT = List.of(
            new Entry(Kind.HE, false), new Entry(Kind.HEAT, false),
            new Entry(Kind.MISSILE, true), new Entry(Kind.COAX, true));

    private FireCommand() {}

    public static List<Entry> listFor(TargetClass cls) {
        return switch (cls) {
            case TROOPS -> TROOPS;
            case HARD_ARMOUR -> HARD_ARMOUR;
            case LIGHT_ARMOUR, HEAVY_CREATURE -> LIGHT;
            case UNSCORED -> List.of();
        };
    }

    /**
     * Steps: 1 fire now from the list, 2 reload onto the list, 3 last resort that fires now. Null
     * when nothing on the list works, and the caller uses the fallback table.
     */
    @Nullable
    public static Pick decide(TargetClass cls, List<Option> options) {
        List<Entry> list = listFor(cls);
        for (Entry e : list) {
            if (e.lastResort()) continue;
            Option o = best(options, e.kind(), true);
            if (o != null) return new Pick(o, 1);
        }
        for (Entry e : list) {
            if (e.lastResort()) continue;
            Option o = best(options, e.kind(), false);
            if (o != null) return new Pick(o, 2);
        }
        for (Entry e : list) {
            if (!e.lastResort()) continue;
            Option o = best(options, e.kind(), true);
            if (o != null) return new Pick(o, 3);
        }
        return null;
    }

    /** Within one kind, the higher card value wins; ties keep data order. */
    @Nullable
    private static Option best(List<Option> options, Kind kind, boolean chambered) {
        Option best = null;
        for (Option o : options) {
            if (o.round().kind() != kind || o.chambered() != chambered || !o.usable()) continue;
            if (best == null || o.cardDamage() > best.cardDamage()) best = o;
        }
        return best;
    }

    /** Grapeshot still covers a body when the cone radius at this range is within its width. */
    public static boolean inCone(double range, double spreadDeg, double bodyWidth) {
        double half = spreadDeg > 0 ? spreadDeg : DEFAULT_CONE_DEG;
        return range * Math.tan(Math.toRadians(half)) <= bodyWidth;
    }

    public static final double DEFAULT_CONE_DEG = 5.0;
}
