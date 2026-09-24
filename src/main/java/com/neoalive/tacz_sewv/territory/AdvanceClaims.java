package com.neoalive.tacz_sewv.territory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.compat.OpenPacCompat.ClaimOutcome;
import com.neoalive.tacz_sewv.entity.ai.support.AdvanceMath;
import com.neoalive.tacz_sewv.entity.ai.support.AdvanceMath.ChunkState;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;

/**
 * Claims one Advance Plan layer through OpenPAC. NEW logic: Sweep &amp; Advance only ever claimed free chunks, and
 * through the raw call that bypasses claim limits.
 *
 * <p><b>All-or-nothing per layer.</b> Every chunk is read first. If the free plus foreign chunks exceed the owner's
 * headroom, or the dimension cannot be claimed at all, NOTHING is touched (in particular no enemy chunk is unclaimed
 * first) and the layer is held. Only then does it act: free chunks are claimed, foreign chunks are unclaimed and then
 * claimed, chunks already ours are left alone.
 *
 * <p><b>Enemy diplomacy is not consulted here.</b> It was decided at bake, so the player saw the conflicts before Start.
 * Claim time reads ownership only (free / ours / someone else). Known residual risk: a relation that changed since bake
 * is not noticed.
 *
 * <p>Honesty about the residue: OpenPAC can still refuse a single chunk after the pre-check passes. That is reported as
 * {@code RETRY} and the caller tries again next pass (chunks already ours then count as done). It is never skipped
 * silently, and never counted as claimed.
 */
public final class AdvanceClaims {

    public enum Status {
        /** Every chunk of the layer is now ours. */
        DONE,
        /** Not enough headroom for the whole layer (or the limit was hit mid-way): the layer is held. */
        HELD_LIMIT,
        /** Claims are disabled or the dimension cannot be claimed: the layer is held (a different problem from a limit). */
        NOT_CLAIMABLE,
        /** Some chunks were refused for another reason; retry next pass. */
        RETRY
    }

    /** {@code claimed} = newly claimed this call; {@code failed} = chunks still not ours afterwards. */
    public record Result(Status status, int claimed, int failed) {}

    private AdvanceClaims() {}

    /**
     * @param owner      the claim owner: the party owner, as Sweep &amp; Advance does
     * @param selfClaims the player's own and party claims in this level, read once per pass; a chunk in it is ours
     */
    public static Result claimLayer(ServerLevel level, UUID owner, Set<Long> layer, Set<Long> selfClaims) {
        if (!OpenPacCompat.claimable(level)) return new Result(Status.NOT_CLAIMABLE, 0, layer.size());

        Map<Long, ChunkState> states = new HashMap<>();
        for (long key : layer) {
            if (selfClaims.contains(key)) {
                states.put(key, ChunkState.OURS);
                continue;
            }
            FrontlineMath.Chunk c = FrontlineMath.unpack(key);
            UUID holder = OpenPacCompat.claimOwnerId(level, c.x(), c.z());
            states.put(key, holder == null ? ChunkState.FREE : owner.equals(holder) ? ChunkState.OURS : ChunkState.FOREIGN);
        }
        AdvanceMath.ClaimPlan plan = AdvanceMath.planClaim(states);
        if (plan.toClaim() == 0) return new Result(Status.DONE, 0, 0);
        if (!plan.fits(OpenPacCompat.claimHeadroom(level.getServer(), owner))) return new Result(Status.HELD_LIMIT, 0, plan.toClaim());

        List<Long> todo = new ArrayList<>();
        for (Map.Entry<Long, ChunkState> e : states.entrySet()) {
            if (e.getValue() != ChunkState.OURS) todo.add(e.getKey());
        }
        todo.sort(Comparator.comparingInt((Long k) -> FrontlineMath.unpack(k).x()).thenComparingInt(k -> FrontlineMath.unpack(k).z()));

        int claimed = 0;
        int failed = 0;
        for (int i = 0; i < todo.size(); i++) {
            long key = todo.get(i);
            FrontlineMath.Chunk c = FrontlineMath.unpack(key);
            if (states.get(key) == ChunkState.FOREIGN) {
                OpenPacCompat.unclaim(level, c.x(), c.z());
                if (OpenPacCompat.claimOwnerId(level, c.x(), c.z()) != null) { // the unclaim did not take
                    failed++;
                    continue;
                }
            }
            ClaimOutcome outcome = OpenPacCompat.tryClaim(level, owner, c.x(), c.z());
            switch (outcome) {
                case CLAIMED -> claimed++;
                case ALREADY_OURS -> { }
                case LIMIT -> {
                    return new Result(Status.HELD_LIMIT, claimed, todo.size() - i - claimed);
                }
                case NOT_CLAIMABLE -> {
                    return new Result(Status.NOT_CLAIMABLE, claimed, todo.size() - i - claimed);
                }
                case TAKEN, OTHER -> failed++;
            }
        }
        return new Result(failed == 0 ? Status.DONE : Status.RETRY, claimed, failed);
    }
}
