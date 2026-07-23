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
 */
public record VehicleMarker(int driverId, int vehicleId, double x, double y, double z, float yaw,
                            VehicleMarker.Kind kind, VehicleMarker.Allegiance allegiance,
                            ResourceKey<Level> dimension) {

    /**
     * Which NATO symbol to draw. Resolved <b>server-side</b> from the hull's engine type and
     * {@code HullFacts.isIfvHull}, and carried as this enum's own ordinal rather than
     * SuperbWarfare's, so an upstream enum reorder cannot silently repaint every marker.
     *
     * <p>Each value names its texture under {@code textures/map/}; they are APP-6 icons, so the
     * frame shape is part of the art (ground = rectangle, air = dome, sea = circle).
     */
    public enum Kind {
        ARMOR("armor"),
        MECHANIZED("mechanized"),
        EMPLACEMENT("emplacement"),
        ROTARY_WING("rotarywing"),
        SURFACE_COMBATANT("surfacecombatant");

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
