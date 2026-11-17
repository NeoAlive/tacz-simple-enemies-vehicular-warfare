package com.neoalive.tacz_sewv.procedural.marks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * Who the next mark is dealt to. Online players are shuffled into a deck (Fisher Yates) and dealt
 * in order, so nobody gets a second mark before everyone has had one; the deck is reshuffled when it
 * runs out or the roster changes. A player who fails the eligibility test (full, or cooling down
 * after an event) is passed over for the rest of that round. Pure.
 */
public final class MarkDeck {

    private final List<UUID> deck = new ArrayList<>();
    private final Set<UUID> roster = new HashSet<>();
    private int cursor;

    /** The next eligible player, or null when nobody online is eligible. */
    @Nullable
    public UUID next(Collection<UUID> online, Predicate<UUID> eligible, Random random) {
        if (online.isEmpty()) return null;
        if (!this.roster.equals(new HashSet<>(online))) reshuffle(online, random);
        for (int tries = 0; tries < this.deck.size() * 2; tries++) {
            if (this.cursor >= this.deck.size()) reshuffle(online, random);
            UUID who = this.deck.get(this.cursor++);
            if (eligible.test(who)) return who;
        }
        return null;
    }

    private void reshuffle(Collection<UUID> online, Random random) {
        this.deck.clear();
        this.deck.addAll(online);
        for (int i = this.deck.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            UUID t = this.deck.get(i);
            this.deck.set(i, this.deck.get(j));
            this.deck.set(j, t);
        }
        this.roster.clear();
        this.roster.addAll(online);
        this.cursor = 0;
    }
}
