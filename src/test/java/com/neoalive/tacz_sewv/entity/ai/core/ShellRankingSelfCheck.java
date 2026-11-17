package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.Arrays;
import java.util.List;

import com.neoalive.tacz_sewv.entity.ai.core.ShellRanking.Round;

/**
 * Self-check for picking a cannon shell by its damage against the target hull's own rules.
 * Run via {@code ./gradlew selfCheckShellRanking}.
 */
public final class ShellRankingSelfCheck {

    private static final Round AP = new Round("superbwarfare:large_shell_ap", "superbwarfare:cannon_shell",
            774.2, 1, 0, 0);
    private static final Round HE = new Round("superbwarfare:large_shell_he", "superbwarfare:cannon_shell",
            19.4, 1, 29, 19.5);
    private static final Round GS = new Round("superbwarfare:large_shell_gs", "superbwarfare:grapeshot",
            18, 36, 0, 0);

    public static void main(String[] args) {
        fcpTank();
        blastSoftHull();
        nothingHurts();
        System.out.println("shell ranking self-check: OK");
    }

    // FCP 2.0 tank rules: All * 0.2, projectile_hit * 1.25, custom_explosion * 2, grapeshot_hit * 0.3, All - 11.
    private static void fcpTank() {
        String[] ranked = rank(r -> r.grapeshot() ? 0.3 : 1.25, 2.0);
        check(ranked != null && ranked[0].endsWith("_ap"), "AP leads against an FCP tank: " + Arrays.toString(ranked));
        check(Arrays.stream(ranked).noneMatch(id -> id.endsWith("_gs")), "grapeshot does nothing to it");
        check(ranked.length == 2 && ranked[1].endsWith("_he"), "HE still listed, its blast gets through");
    }

    // A hull that shrugs off penetrators but not blast.
    private static void blastSoftHull() {
        String[] ranked = ShellRanking.order(List.of(AP, HE, GS), r -> ShellRanking.expected(r,
                raw -> raw * 0.05, raw -> raw * 3));
        check(ranked != null && ranked[0].endsWith("_he"), "HE leads when blast hurts more: " + Arrays.toString(ranked));
    }

    private static void nothingHurts() {
        String[] ranked = ShellRanking.order(List.of(AP, HE, GS), r -> ShellRanking.expected(r,
                raw -> raw - 10000, raw -> raw - 10000));
        check(ranked == null, "no ranking when nothing does damage, doctrine decides");
    }

    private static String[] rank(java.util.function.ToDoubleFunction<Round> directMult, double blastMult) {
        return ShellRanking.order(List.of(AP, HE, GS), r -> ShellRanking.expected(r,
                raw -> raw * 0.2 * directMult.applyAsDouble(r) - 11,
                raw -> raw * 0.2 * blastMult - 11));
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("ShellRankingSelfCheck failed: " + what);
    }
}
