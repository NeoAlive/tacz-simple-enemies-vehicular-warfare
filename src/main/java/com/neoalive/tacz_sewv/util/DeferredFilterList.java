package com.neoalive.tacz_sewv.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * A filtered list whose predicate runs only as far as it is read.
 *
 * <p>Built for a caller that asks for "everything matching", sorts it, and stops at the first element it
 * likes: sorting happens on the raw elements, and iterating tests the predicate one element at a time, so
 * a walk that breaks early never pays for the rest. Equal to the eager list as long as the comparator does
 * not depend on the predicate and the predicate has no side effects. Answers are memoised per element, so
 * a second walk does not test again. Every other kind of access ({@code size}, {@code get}, a write)
 * filters everything first and behaves exactly like the eager list from then on. Not thread safe.
 */
public final class DeferredFilterList<T> extends AbstractList<T> {

    private static long candidates;
    private static long tested;

    private final List<T> raw;
    private final Predicate<? super T> predicate;
    /** Per raw index: 0 untested, 1 passes, 2 fails. Matches {@code raw}'s current order. */
    private byte[] verdict;
    private boolean anyTested;
    private List<T> eager;

    public DeferredFilterList(List<T> raw, Predicate<? super T> predicate) {
        this.raw = raw;
        this.predicate = predicate;
        this.verdict = new byte[raw.size()];
        candidates += raw.size();
    }

    private boolean passes(int i) {
        byte v = this.verdict[i];
        if (v == 0) {
            tested++;
            this.anyTested = true;
            v = this.predicate.test(this.raw.get(i)) ? (byte) 1 : (byte) 2;
            this.verdict[i] = v;
        }
        return v == 1;
    }

    private List<T> eager() {
        if (this.eager == null) {
            List<T> out = new ArrayList<>();
            for (int i = 0; i < this.raw.size(); i++) {
                if (passes(i)) out.add(this.raw.get(i));
            }
            this.eager = out;
        }
        return this.eager;
    }

    @Override
    public void sort(Comparator<? super T> c) {
        if (this.eager != null || this.anyTested) {
            eager().sort(c);
            return;
        }
        this.raw.sort(c);
    }

    @Override
    public Iterator<T> iterator() {
        if (this.eager != null) return this.eager.iterator();
        return new Iterator<T>() {
            private int next = advance(0);

            private int advance(int from) {
                int i = from;
                while (i < DeferredFilterList.this.raw.size() && !passes(i)) i++;
                return i;
            }

            @Override
            public boolean hasNext() {
                return this.next < DeferredFilterList.this.raw.size();
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                T out = DeferredFilterList.this.raw.get(this.next);
                this.next = advance(this.next + 1);
                return out;
            }
        };
    }

    @Override
    public T get(int index) {
        return eager().get(index);
    }

    @Override
    public int size() {
        return eager().size();
    }

    @Override
    public T set(int index, T element) {
        return eager().set(index, element);
    }

    @Override
    public void add(int index, T element) {
        eager().add(index, element);
    }

    @Override
    public T remove(int index) {
        return eager().remove(index);
    }

    /** Candidates handed out and predicate tests actually run since the last call. */
    public static String snapshotAndReset() {
        String out = "candidates=" + candidates + " tested=" + tested
                + " (" + (candidates == 0 ? 0 : Math.round(100.0 * (candidates - tested) / candidates)) + "% skipped)";
        candidates = 0;
        tested = 0;
        return out;
    }
}
