package com.neoalive.tacz_sewv.invasion;

import java.util.List;
import java.util.Set;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;

import com.neoalive.tacz_sewv.invasion.CaptureRules.Outcome;
import com.neoalive.tacz_sewv.invasion.force.BaseForce;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.invasion.force.InfantrySpec;

/**
 * Headless checks for the invasion rework: the capture/contest decision, {@link BaseForce}
 * serialisation both ways, the pre-rework migration, infantry headcount bounds and which deploy
 * options a player is actually offered. Run with {@code ./gradlew selfCheckInvasion}.
 */
public final class InvasionSelfCheck {

    private InvasionSelfCheck() {}

    public static void main(String[] args) {
        captureRules();
        forceRoundTrip();
        legacyMigration();
        infantryCount();
        deployOptions();
        System.out.println("invasion self-check: OK");
    }

    private static void captureRules() {
        Set<String> none = Set.of();
        // Nobody: reset. Holder alone: held.
        expect(CaptureRules.step("A", none, none), Outcome.RESET);
        expect(CaptureRules.step("A", Set.of("A"), none), Outcome.HELD);
        expect(CaptureRules.step("A", none, Set.of("A")), Outcome.HELD);
        // A capturer of another team advances — on a held zone and on a neutral one.
        expect(CaptureRules.step("A", Set.of("B"), none), Outcome.ADVANCE);
        expect(CaptureRules.step("", Set.of("B"), none), Outcome.ADVANCE);
        assert CaptureRules.step("A", Set.of("B"), none).team().equals("B");
        // Contest-only infantry alone on an enemy or neutral zone: frozen, never advancing.
        expect(CaptureRules.step("A", none, Set.of("B")), Outcome.FROZEN);
        expect(CaptureRules.step("", none, Set.of("B")), Outcome.FROZEN);
        // Contest-only defenders block an attacking capturer: that is what "defend" means.
        expect(CaptureRules.step("A", Set.of("B"), Set.of("A")), Outcome.CONTESTED);
        expect(CaptureRules.step("", Set.of("B"), Set.of("A")), Outcome.CONTESTED);
        // Two capturing teams: contested.
        expect(CaptureRules.step("", Set.of("A", "B"), none), Outcome.CONTESTED);
        // A team's own capturer and contester together still read as one team.
        expect(CaptureRules.step("A", Set.of("B"), Set.of("B")), Outcome.ADVANCE);
    }

    private static void forceRoundTrip() {
        BaseForce f = new BaseForce();
        f.pool(ForceKind.GROUND).addAll(List.of("superbwarfare:t_90a", "superbwarfare:bmp_2"));
        f.pool(ForceKind.ROTOR).add("superbwarfare:ah_6");
        f.pool(ForceKind.PLANE).add("superbwarfare:su_25");
        f.setAiCount(ForceKind.GROUND, 4);
        f.setAiCount(ForceKind.ROTOR, 1);
        f.setAiCount(ForceKind.PLANE, 2);
        f.infantry().mode = InfantrySpec.Mode.RANDOM;
        f.infantry().min = 3;
        f.infantry().max = 9;
        f.infantry().canCapture = false;
        f.playerDeploy().clear();
        f.playerDeploy().add(ForceKind.PLANE);
        f.playerDeploy().add(ForceKind.INFANTRY);
        f.setNpcCrewForPlayers(true);

        assertSame(f, BaseForce.load(f.save()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        f.write(buf);
        assertSame(f, BaseForce.read(buf));
        assert buf.readableBytes() == 0 : "BaseForce.read left bytes unread";

        // Sanitize: duplicates and junk ids dropped, counts clamped.
        BaseForce dirty = new BaseForce();
        dirty.pool(ForceKind.GROUND).addAll(List.of("a:b", "a:b", "NOT VALID!"));
        dirty.slot(ForceKind.ROTOR).aiCount = 999;
        dirty.sanitize();
        assert dirty.pool(ForceKind.GROUND).equals(List.of("a:b")) : dirty.pool(ForceKind.GROUND);
        assert dirty.aiCount(ForceKind.ROTOR) == BaseForce.MAX_AI_VEHICLES;
    }

    private static void legacyMigration() {
        BaseForce legacy = BaseForce.fromLegacy(List.of("superbwarfare:t_90a"), 3, true);
        assert legacy.pool(ForceKind.GROUND).equals(List.of("superbwarfare:t_90a"));
        assert legacy.aiCount(ForceKind.GROUND) == 3;
        assert legacy.aiCount(ForceKind.ROTOR) == 0 && legacy.aiCount(ForceKind.PLANE) == 0;
        assert !legacy.infantry().isEnabled();
        assert legacy.playerDeploy().equals(Set.of(ForceKind.GROUND));
        assert legacy.npcCrewForPlayers();

        // A NBT with only some keys still loads (no exception, empty kinds at 0).
        CompoundTag partial = new CompoundTag();
        CompoundTag ground = new CompoundTag();
        ListTag pool = new ListTag();
        pool.add(StringTag.valueOf("x:y"));
        ground.put("Pool", pool);
        ground.putInt("Count", 2);
        partial.put("ground", ground);
        BaseForce loaded = BaseForce.load(partial);
        assert loaded.aiCount(ForceKind.GROUND) == 2 && loaded.aiCount(ForceKind.PLANE) == 0;
        assert loaded.playerDeploy().isEmpty();
    }

    private static void infantryCount() {
        RandomSource random = RandomSource.create(42L);
        InfantrySpec spec = new InfantrySpec();
        assert spec.resolveCount(random) == 0 : "NONE fields nobody";
        spec.mode = InfantrySpec.Mode.FIXED;
        spec.fixed = 7;
        assert spec.resolveCount(random) == 7 && spec.minimumCount() == 7;
        spec.mode = InfantrySpec.Mode.RANDOM;
        spec.min = 4;
        spec.max = 6;
        boolean sawLo = false;
        boolean sawHi = false;
        for (int i = 0; i < 500; i++) {
            int n = spec.resolveCount(random);
            assert n >= 4 && n <= 6 : n;
            sawLo |= n == 4;
            sawHi |= n == 6;
        }
        assert sawLo && sawHi : "RANDOM must reach both ends inclusive";
        assert spec.minimumCount() == 4;
        spec.fixed = 1000;
        spec.clamp();
        assert spec.fixed == InfantrySpec.MAX;
    }

    private static void deployOptions() {
        BaseForce f = new BaseForce();
        f.playerDeploy().clear();
        f.playerDeploy().addAll(List.of(ForceKind.GROUND, ForceKind.ROTOR, ForceKind.INFANTRY));
        f.pool(ForceKind.ROTOR).add("superbwarfare:ah_6");
        // GROUND allowed but its pool is empty: not offered. On foot needs no pool.
        assert f.deployOptions().equals(List.of(ForceKind.ROTOR, ForceKind.INFANTRY)) : f.deployOptions();
        f.playerDeploy().clear();
        assert f.deployOptions().isEmpty();
        assert new BaseForce().fieldsAnyAi() : "default base fields one ground hull";
        BaseForce empty = new BaseForce();
        empty.setAiCount(ForceKind.GROUND, 0);
        assert !empty.fieldsAnyAi();
        empty.infantry().mode = InfantrySpec.Mode.FIXED;
        empty.infantry().fixed = 5;
        assert empty.fieldsAnyAi() : "an infantry-only base fields something";
    }

    private static void expect(CaptureRules.Step step, Outcome want) {
        assert step.outcome() == want : "expected " + want + " got " + step;
    }

    private static void assertSame(BaseForce a, BaseForce b) {
        for (ForceKind kind : ForceKind.VEHICLES) {
            assert a.pool(kind).equals(b.pool(kind)) : kind + " pool " + b.pool(kind);
            assert a.aiCount(kind) == b.aiCount(kind) : kind + " count";
        }
        InfantrySpec x = a.infantry();
        InfantrySpec y = b.infantry();
        assert x.mode == y.mode && x.fixed == y.fixed && x.min == y.min && x.max == y.max
                && x.canCapture == y.canCapture : "infantry";
        assert a.playerDeploy().equals(b.playerDeploy()) : "deploy " + b.playerDeploy();
        assert a.npcCrewForPlayers() == b.npcCrewForPlayers() : "npc crew";
    }
}
