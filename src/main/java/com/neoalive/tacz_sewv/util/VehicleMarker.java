package com.neoalive.tacz_sewv.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One crewed vehicle as the map sees it. Common, not client-only: the server builds these
 * ({@code OwnedVehicleTracker}), the packet carries them, and the client's marker store holds them.
 *
 * <p>{@code driverId} is the unit an order names — SEM commands units, not hulls, and the drive
 * goal runs on the first passenger. It is only actionable on an {@link Allegiance#OWN} marker;
 * on anything else it is just an identity for the client to track the hull by.
 *
 * <p>{@code healthFrac} is 0..1. {@code energyFrac} is 0..1 when the hull has energy storage,
 * or {@link #NO_ENERGY} when it does not (mortars, some emplacements) so the client can hide
 * the energy bar rather than invent a reading.
 *
 * <p>{@code commandRole}/{@code groupId}/{@code playRole} are debug-only tags filled read-only
 * from {@code CommandCoordinator} — they never drive AI.
 */
public record VehicleMarker(int driverId, int vehicleId, double x, double y, double z, float yaw,
                            VehicleMarker.Kind kind, VehicleMarker.Allegiance allegiance,
                            CrewFacts.Faction faction, MarkerOrder order, ResourceKey<Level> dimension,
                            float healthFrac, float energyFrac,
                            VehicleMarker.CommandRole commandRole, int groupId,
                            VehicleMarker.PlayRole playRole) {

    /** Sentinel: hull has no energy storage — do not draw an energy bar. */
    public static final float NO_ENERGY = -1.0F;

    /** Sentinel: driver is not in a battle group. */
    public static final int NO_GROUP = -1;

    /**
     * Command-tier debug role for map markers. Three states on purpose: election can defer
     * ({@link #MEMBER} in a group with no elected commander), which must not look like
     * {@link #NONE}.
     */
    public enum CommandRole {
        /** Not in any battle group. */
        NONE,
        /** In a group but not the elected commander (includes deferred election). */
        MEMBER,
        /** Elected commander of its group. */
        COMMANDER;

        private static final CommandRole[] VALUES = values();

        public static CommandRole byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }
    }

    /**
     * Stage-4 play assignment element for map debug. Wire ordinal — keep stable; add only at end.
     */
    public enum PlayRole {
        NONE(""),
        BASE_OF_FIRE("BoF"),
        MANEUVER("MNV"),
        OVERWATCH("OVW"),
        RESERVE("RSV"),
        HOLD("HLD"),
        WITHDRAW("WDR");

        private static final PlayRole[] VALUES = values();
        public final String tag;

        PlayRole(String tag) {
            this.tag = tag;
        }

        public static PlayRole byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }
    }

    /**
     * Which NATO symbol to draw. Resolved <b>server-side</b> from the hull's engine type and
     * {@code HullFacts.isIfvHull} (or, for the infantry kinds, the on-foot unit's own class/role),
     * and carried as this enum's own ordinal rather than SuperbWarfare's, so an upstream enum
     * reorder cannot silently repaint every marker.
     *
     * <p>Each value names its texture under {@code textures/map/}; they are APP-6 icons, so the
     * frame shape is part of the art. New kinds go on the END — the ordinal is the wire value.
     */
    public enum Kind {
        ARMOR("armor"),
        MECHANIZED("mechanized"),
        EMPLACEMENT("emplacement"),
        ROTARY_WING("rotarywing"),
        SURFACE_COMBATANT("surfacecombatant"),
        INFANTRY("infantry"),
        INFANTRY_MEDIC("infantry_medic"),
        INFANTRY_ENGINEER("infantry_engineer"),
        FIXED_WING("airplane"),
        /** ASH Sapsan-style coordinate ballistic launcher. Reuses emplacement art. */
        MISSILE_SYSTEM("emplacement"),
        /** ASH Gepard/Pantsir-style AA. Reuses armor art until a dedicated symbol ships. */
        ANTI_AIR("armor");

        private static final Kind[] VALUES = values();
        private final String texture;

        Kind(String texture) {
            this.texture = texture;
        }

        public String textureName() {
            return this.texture;
        }

        public static Kind byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : ARMOR;
        }
    }

    /**
     * The viewing player's relationship to the crew, which is what the symbol's fill colour shows.
     *
     * <p>Decided on the <b>server</b>, because that is the only side that authoritatively knows
     * SEM's {@code ruUnitsFriendly}/{@code usUnitsFriendly} toggles: a client reading its own copy
     * of SEM's config would disagree with the server whenever the two differ, and get it exactly
     * backwards about who is shooting at whom.
     */
    public enum Allegiance {
        OWN, FRIENDLY, HOSTILE;

        private static final Allegiance[] VALUES = values();

        public static Allegiance byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : HOSTILE;
        }
    }
}
