package com.neoalive.tacz_sewv.entity.ai.targeting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.TripodCompat;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.MobToughness;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.PenetrationCard;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.SeatLoadout;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.TargetClass;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.targeting.StackEntry.Group;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/**
 * One faction's priority stack, compiled for the AI: per entity type caches for the static rows
 * (id, mod, category, tag) and live evaluation of the groups. Server thread only.
 */
public final class TargetStack {

    private static final Map<TankFaction, TargetStack> COMPILED = new EnumMap<>(TankFaction.class);

    private final List<StackEntry> priority;
    private final List<StackEntry> excluded;
    /** Topmost priority index of each group present. */
    private final Map<Group, Integer> groupIndex = new EnumMap<>(Group.class);
    private final Map<EntityType<?>, Integer> livingRank = new HashMap<>();
    private final Map<EntityType<?>, Integer> hullRank = new HashMap<>();
    private final Map<EntityType<?>, Integer> excludedType = new HashMap<>();
    private final Map<EntityType<?>, Integer> priorityLift = new HashMap<>();
    private final Map<Group, Boolean> excludedGroup = new EnumMap<>(Group.class);

    private TargetStack(WorldTargetStack.Stack stack) {
        this.priority = parseAll(stack.priority());
        this.excluded = parseAll(stack.excluded());
        for (int i = 0; i < priority.size(); i++) {
            Group g = priority.get(i).group();
            if (g != null) groupIndex.putIfAbsent(g, i);
        }
        for (StackEntry e : excluded) {
            Group g = e.group();
            if (g != null) excludedGroup.put(g, Boolean.TRUE);
        }
    }

    /** Drops every compiled stack: after an edit, a tag reload or a world load. */
    public static void invalidate() {
        COMPILED.clear();
    }

    public static TargetStack of(Level level, TankFaction faction) {
        TargetStack s = COMPILED.get(faction);
        if (s == null) {
            s = new TargetStack(WorldTargetStack.get(level).of(faction));
            COMPILED.put(faction, s);
        }
        return s;
    }

    @Nullable
    public static TargetStack forUnit(AbstractUnit unit) {
        TankFaction f = VehicleTargeting.factionOf(unit);
        return f == null ? null : of(unit.level(), f);
    }

    public int size() {
        return priority.size();
    }

    // --- exclusion -------------------------------------------------------------------------------

    /**
     * Whether Excluded holds this mob. Callers apply the exemptions first (troops, players, iron
     * golems for RU/US), exactly as the old category gate did.
     */
    public boolean excludes(LivingEntity e) {
        int lift = priorityLift.computeIfAbsent(e.getType(), t -> mostSpecific(priority, t));
        int byType = excludedType.computeIfAbsent(e.getType(), t -> mostSpecific(excluded, t));
        if (StackEntry.stillExcluded(byType, lift)) return true;
        return excludedGroup.containsKey(Group.HEAVY_CREATURE)
                && StackEntry.stillExcluded(1, lift) && isHeavyCreature(e);
    }

    /** Whether any faction may target this mob at all: the combatant index's soft-tier test. */
    public static boolean anyFactionAllows(Level level, LivingEntity e) {
        for (TankFaction f : TankFaction.values()) {
            if (!of(level, f).excludes(e)) return true;
        }
        return false;
    }

    // --- rank ------------------------------------------------------------------------------------

    /** Index of the topmost row {@code candidate} matches, as {@code observer} sees it; {@link #size} if none. */
    public int rank(AbstractUnit observer, LivingEntity candidate) {
        int best = livingRank.computeIfAbsent(candidate.getType(), t -> firstMatch(t, true));
        Entity root = candidate.getRootVehicle();
        if (root != candidate) {
            best = Math.min(best, hullRank.computeIfAbsent(root.getType(), t -> firstMatch(t, false)));
        }
        if (groupIndex.isEmpty()) return best;
        for (Map.Entry<Group, Integer> g : groupIndex.entrySet()) {
            if (g.getValue() < best && inGroup(observer, candidate, g.getKey())) best = g.getValue();
        }
        return best;
    }

    private int firstMatch(EntityType<?> type, boolean living) {
        for (int i = 0; i < priority.size(); i++) {
            if (matches(priority.get(i), type, living)) return i;
        }
        return priority.size();
    }

    /** Specificity of the narrowest static row matching a living target of this type, 0 if none. */
    private static int mostSpecific(List<StackEntry> rows, EntityType<?> type) {
        int best = 0;
        for (StackEntry e : rows) {
            if (e.specificity() > best && matches(e, type, true)) best = e.specificity();
        }
        return best;
    }

    /** Static rows. A hull is matched by id, mod and tag; category is the living target's own. */
    private static boolean matches(StackEntry e, EntityType<?> type, boolean living) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return switch (e.kind()) {
            case TYPE -> id != null && id.toString().equals(e.value());
            case MOD -> id != null && id.getNamespace().equals(e.value());
            case CATEGORY -> living && type.getCategory().getName().equals(e.value());
            case TAG -> {
                ResourceLocation tag = ResourceLocation.tryParse(e.value());
                yield tag != null && type.is(TagKey.create(Registries.ENTITY_TYPE, tag));
            }
            case GROUP -> false;
        };
    }

    // --- groups ----------------------------------------------------------------------------------

    public static boolean inGroup(AbstractUnit observer, LivingEntity c, Group g) {
        Entity root = c.getRootVehicle();
        if (root instanceof VehicleEntity hull) {
            return switch (g) {
                case PLAYERS -> c instanceof Player;
                case AIRCRAFT -> {
                    EngineType t = HullFacts.engineType(hull);
                    yield t == EngineType.HELICOPTER || t == EngineType.AIRCRAFT;
                }
                case EMPLACEMENTS -> HullFacts.engineType(hull) == EngineType.FIXED;
                case HARD_ARMOUR -> hullClass(observer, hull) == TargetClass.HARD_ARMOUR;
                case LIGHT_ARMOUR -> hullClass(observer, hull) == TargetClass.LIGHT_ARMOUR;
                case UNARMOURED_VEHICLE -> hullClass(observer, hull) == TargetClass.TROOPS;
                default -> false;
            };
        }
        if (root != c && LvCompat.isLvHull(root)) {
            return switch (g) {
                case PLAYERS -> c instanceof Player;
                case HARD_ARMOUR -> LvCompat.isArmouredHull(root);
                case UNARMOURED_VEHICLE -> !LvCompat.isArmouredHull(root);
                default -> false;
            };
        }
        return switch (g) {
            case PLAYERS -> c instanceof Player;
            case TROOPS -> c instanceof AbstractUnit;
            case HEAVY_CREATURE -> isHeavyCreature(c);
            default -> false;
        };
    }

    private static boolean isHeavyCreature(LivingEntity c) {
        if (c instanceof Player || c instanceof AbstractUnit) return false;
        return TripodCompat.isTripod(c) || !MobToughness.isSoft(c);
    }

    /** The gunner's own class for a hull: its seat's card when mounted, a rifle round on foot. */
    private static TargetClass hullClass(AbstractUnit observer, VehicleEntity target) {
        if (observer.getVehicle() instanceof VehicleEntity own) {
            int seat = observerSeat(own, observer);
            if (seat >= 0) {
                PenetrationCard.Card card = PenetrationCard.of(own, seat, SeatLoadout.of(own, seat), target);
                if (card != null) return TargetClass.ofHull(card.mgFraction(), card.otherRoundHurts());
            }
        }
        double f = PenetrationCard.referenceFraction(target);
        return TargetClass.ofHull(f, true);
    }

    /** The unit's own seat if armed, else the hull's main gun seat (the most weapons). */
    public static int observerSeat(VehicleEntity hull, AbstractUnit unit) {
        int own = hull.getSeatIndex(unit);
        if (weaponCount(hull, own) > 0) return own;
        int best = -1;
        int most = 0;
        int seats = Math.max(1, hull.getMaxPassengers());
        for (int s = 0; s < seats; s++) {
            int n = weaponCount(hull, s);
            if (n > most) {
                most = n;
                best = s;
            }
        }
        return best;
    }

    private static int weaponCount(VehicleEntity hull, int seat) {
        if (seat < 0) return 0;
        try {
            SeatInfo info = hull.getSeat(seat);
            return info == null || info.weapons() == null ? 0 : info.weapons().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- can hurt --------------------------------------------------------------------------------

    /**
     * False only for a hull rider the observer cannot damage: a crew whose seat has no round on the
     * hull's card, or a unit on foot whose rifle round does nothing and who holds no anti-armour.
     */
    public static boolean canHurt(AbstractUnit observer, LivingEntity c) {
        if (!(c.getRootVehicle() instanceof VehicleEntity target)) return true;
        // An LV crew's guns are not on SBW's damage rules; nothing is ruled out for it.
        if (LvCompat.isLvHull(observer.getVehicle())) return true;
        if (observer.getVehicle() instanceof VehicleEntity own) {
            int seat = observerSeat(own, observer);
            if (seat < 0) return true;
            PenetrationCard.Card card = PenetrationCard.of(own, seat, SeatLoadout.of(own, seat), target);
            if (card == null) return true;
            for (double d : card.damage()) {
                if (d > 0) return true;
            }
            return false;
        }
        if (SmallArmsSupport.holdsAntiArmor(observer)) return true;
        double f = PenetrationCard.referenceFraction(target);
        return Double.isNaN(f) || f >= TargetClass.NO_EFFECT_FRACTION;
    }

    // --- ranking ---------------------------------------------------------------------------------

    public static double maxFactor() {
        try {
            return SewvConfig.TARGET_STACK_MAX_FACTOR.get();
        } catch (Throwable unbaked) {
            return 4.0;
        }
    }

    /**
     * Ranks {@code targets} for {@code observer}, best first. {@code dist} is the caller's own
     * distance measure. The commander's focus target wins ties inside its own row.
     */
    public static List<TargetRanker.Candidate<LivingEntity>> rank(AbstractUnit observer,
                                                                 List<LivingEntity> targets,
                                                                 ToDoubleFunction<LivingEntity> dist) {
        List<TargetRanker.Candidate<LivingEntity>> out = new ArrayList<>(targets.size());
        TargetStack stack = forUnit(observer);
        int size = stack == null ? 0 : stack.size();
        Integer focus = CrewAssignment.priorityTargetId(observer.getId());
        for (LivingEntity e : targets) {
            int r = stack == null ? 0 : stack.rank(observer, e);
            out.add(new TargetRanker.Candidate<>(e, e.getId(), dist.applyAsDouble(e), r, canHurt(observer, e),
                    focus != null && focus == e.getId()));
        }
        TargetRanker.sort(out, size, maxFactor());
        return out;
    }

    private static List<StackEntry> parseAll(List<String> rows) {
        List<StackEntry> out = new ArrayList<>(rows.size());
        for (String raw : rows) {
            StackEntry e = StackEntry.parse(raw);
            if (e != null) out.add(e);
        }
        return List.copyOf(out);
    }
}
