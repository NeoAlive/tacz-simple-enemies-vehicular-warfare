package com.neoalive.tacz_sewv.entity.ai.core;

/**
 * Self-check for which mobs a crew leaves to its machine gun. Run via {@code ./gradlew selfCheckMobToughness}.
 */
public final class MobToughnessSelfCheck {

    public static void main(String[] args) {
        soft("zombie", 20, 0, 2, 0, 0);
        soft("phayriosis stalker", 50, 0, 10, 0, 0);
        hard("vanilla iron golem, the old 100 HP line", 100, 0, 0, 0, 0);
        hard("phayriosis immaculate resistor", 80, 0, 25, 0, 0);
        hard("phayriosis immaculate blightlord", 60, 0, 20, 0, 0);
        hard("spore braurei with resistance II", 80, 0, 6, 0, 2);
        hard("absorption counts as health", 60, 60, 0, 0, 0);
        check(Double.isInfinite(MobToughness.effectiveHealth(20, 0, 30, 20, 5)), "untouchable reads infinite");
        System.out.println("mob toughness self-check: OK");
    }

    private static void soft(String what, double hp, double abs, double armor, double tough, int res) {
        double e = MobToughness.effectiveHealth(hp, abs, armor, tough, res);
        check(e < MobToughness.SOFT_EFFECTIVE_HEALTH, what + " should be an MG target, effective " + e);
    }

    private static void hard(String what, double hp, double abs, double armor, double tough, int res) {
        double e = MobToughness.effectiveHealth(hp, abs, armor, tough, res);
        check(e >= MobToughness.SOFT_EFFECTIVE_HEALTH, what + " should get the main gun, effective " + e);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("MobToughnessSelfCheck failed: " + what);
    }
}
