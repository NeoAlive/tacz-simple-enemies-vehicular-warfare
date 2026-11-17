package com.neoalive.tacz_sewv.fpas;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;

/**
 * One MTI contact. {@code targetId} is what an attack order names: a unit on foot, or the crewman a
 * hull carries (a hull is not a LivingEntity, so it can never be a SEM target itself).
 */
public record RadarContact(int targetId, double x, double y, double z, Cls cls) {

    /** Contact class: dot colour, legend label and the dashboard/wheel attack button. Ordinal is wire order. */
    public enum Cls {
        GROUND(0xE8B84A),
        ROTOR(0x4AB8E8),
        PLANE(0xA06BD4),
        SHIP(0x3A7BD5),
        INFANTRY(0xE85A4A);

        public final int rgb;

        Cls(int rgb) {
            this.rgb = rgb;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Cls of(EngineType engine) {
            return switch (engine) {
                case HELICOPTER -> ROTOR;
                case AIRCRAFT, AIRSHIP -> PLANE;
                case SHIP -> SHIP;
                default -> GROUND;
            };
        }

        public static Cls byOrdinal(int i) {
            Cls[] v = values();
            return v[Math.floorMod(i, v.length)];
        }
    }
}
