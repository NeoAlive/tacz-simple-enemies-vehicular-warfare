package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Pure commander election — no world types.
 *
 * <p>Two non-overlapping cases after the player-designation hook:
 * <ol>
 *   <li>Incumbent still in the member list → keep unless a <b>ready</b> challenger beats
 *       {@code incumbent.fitness + margin}.</li>
 *   <li>No incumbent → highest ready fitness; ties break on {@code min(id)}. If fewer than
 *       {@code quorum} members are Facts-ready, defer ({@code null}) — never elect off blank
 *       defaults via {@code min(id)}.</li>
 * </ol>
 */
public final class Election {

    private Election() {}

    /**
     * One group member's election inputs. {@code fitness} is meaningful only when {@code ready}.
     */
    public static final class Candidate {
        public final int id;
        public final boolean ready;
        public final double fitness;

        public Candidate(int id, boolean ready, double fitness) {
            this.id = id;
            this.ready = ready;
            this.fitness = fitness;
        }
    }

    /**
     * @param members            current group members (in-group = present in this list)
     * @param incumbentId        previous commander, or null
     * @param margin             {@code COMMAND_MARGIN} — challenger must beat incumbent by more than this
     * @param playerDesignatedId TODO(command-player-designation) hook; null = no override
     * @param quorum             minimum Facts-ready members required before a <b>first</b> election
     * @return commander id, or {@code null} if the first election must defer
     */
    @Nullable
    public static Integer electCommander(List<Candidate> members,
                                         @Nullable Integer incumbentId,
                                         double margin,
                                         @Nullable Integer playerDesignatedId,
                                         int quorum) {
        if (members.isEmpty()) return null;

        // Player designation wins first — alive+in-group only (presence in members).
        if (playerDesignatedId != null && contains(members, playerDesignatedId)) {
            return playerDesignatedId;
        }

        Candidate incumbent = incumbentId != null ? find(members, incumbentId) : null;
        if (incumbent != null) {
            // Incumbent stays unless a ready challenger clears the margin. An unready incumbent
            // cannot be scored against, so it is kept (stickiness) rather than replaced by blank math.
            if (!incumbent.ready) return incumbent.id;

            Candidate bestChallenger = null;
            for (Candidate c : members) {
                if (c.id == incumbent.id || !c.ready) continue;
                if (c.fitness <= incumbent.fitness + margin) continue;
                if (bestChallenger == null
                        || c.fitness > bestChallenger.fitness
                        || (c.fitness == bestChallenger.fitness && c.id < bestChallenger.id)) {
                    bestChallenger = c;
                }
            }
            return bestChallenger != null ? bestChallenger.id : incumbent.id;
        }

        // No incumbent: require a ready quorum, then pick among ready members only.
        int readyCount = 0;
        for (Candidate c : members) {
            if (c.ready) readyCount++;
        }
        if (readyCount < Math.max(1, quorum)) return null;

        Candidate best = null;
        for (Candidate c : members) {
            if (!c.ready) continue;
            if (best == null
                    || c.fitness > best.fitness
                    || (c.fitness == best.fitness && c.id < best.id)) {
                best = c;
            }
        }
        return best != null ? best.id : null;
    }

    private static boolean contains(List<Candidate> members, int id) {
        return find(members, id) != null;
    }

    @Nullable
    private static Candidate find(List<Candidate> members, int id) {
        for (Candidate c : members) {
            if (c.id == id) return c;
        }
        return null;
    }
}
