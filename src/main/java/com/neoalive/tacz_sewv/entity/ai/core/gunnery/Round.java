package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.Locale;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * One thing a seat can fire: a physical slot plus one of its ammo consumers, with the numbers that
 * consumer fires with (the gun's base data, the consumer's override on top) and what kind of round
 * it is. The kind is what the fire command reads; the numbers only feed the penetration card.
 */
public record Round(int slot, int consumerIndex, int consumerCount, String ammoId, String projectileId,
                    int role, Kind kind, double directDamage, int pellets, double blastDamage,
                    double blastRadius, double spreadDeg) {

    public enum Kind { COAX, GRAPESHOT, HE, HEAT, PENETRATOR, MISSILE, OTHER }

    private static final String[] MISSILE_CUES = {
            "missile", "hellfire", "malyutka", "sidewinder", "javelin", "agm_", "9m1", "9m3", "kornet"};

    public boolean grapeshot() {
        return kind == Kind.GRAPESHOT || projectileId.contains("grapeshot");
    }

    /**
     * Kind from the slot role, then the projectile, then the shell type or ammo id suffix. A gun
     * launched missile is a consumer on the cannon slot, so the projectile decides it, not the role.
     */
    public static Kind kindOf(int role, String ammoId, String projectileId, String shellType, double blastDamage) {
        String ammo = lower(ammoId);
        String proj = lower(projectileId);
        String shell = lower(shellType);
        if (role == VehicleWeapons.WEAPON_MG) return Kind.COAX;
        if (role == VehicleWeapons.WEAPON_SPECIAL) return Kind.MISSILE;
        if (proj.contains("grapeshot") || ammo.endsWith("_gs")) return Kind.GRAPESHOT;
        for (String cue : MISSILE_CUES) {
            if (proj.contains(cue) || ammo.contains(cue)) return Kind.MISSILE;
        }
        if (shell.equals("heat") || ammo.endsWith("_heat")) return Kind.HEAT;
        if (shell.equals("ap") || shell.equals("apfsds") || shell.equals("sabot")
                || ammo.endsWith("_ap") || ammo.endsWith("_apfsds") || ammo.endsWith("_sabot")) {
            return Kind.PENETRATOR;
        }
        if (shell.equals("he") || ammo.endsWith("_he")) return Kind.HE;
        if (!shell.isEmpty() || ammo.endsWith("_cm") || ammo.endsWith("_wp") || ammo.endsWith("_aa")) {
            return Kind.OTHER;
        }
        // An untagged cannon: a shell that bursts is HE, a solid one is a penetrator.
        if (role == VehicleWeapons.WEAPON_CANNON) return blastDamage > 0 ? Kind.HE : Kind.PENETRATOR;
        return Kind.OTHER;
    }

    public String shortAmmo() {
        int colon = ammoId.indexOf(':');
        return colon >= 0 ? ammoId.substring(colon + 1) : ammoId;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
