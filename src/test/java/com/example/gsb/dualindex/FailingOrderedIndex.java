package com.example.gsb.dualindex;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@link OrderedIndex} decorator that fails the next invocation of an armed
 * operation exactly once, before touching the delegate.
 */
final class FailingOrderedIndex<K, S extends Comparable<? super S>, E> implements OrderedIndex<K, S, E> {

    enum Op { PUT, REMOVE, RANGE, SIZE, SLOTS }

    private final TreeMapOrderedIndex<K, S, E> delegate = new TreeMapOrderedIndex<>();
    private final Set<Op> armed = EnumSet.noneOf(Op.class);

    void failNext(Op op) {
        armed.add(op);
    }

    TreeMapOrderedIndex<K, S, E> delegate() {
        return delegate;
    }

    private void maybeFail(Op op) {
        if (armed.remove(op)) {
            throw new InjectedFailureException("injected ordered-index failure on " + op);
        }
    }

    @Override
    public void put(S sortKey, K primaryKey, E entry) {
        maybeFail(Op.PUT);
        delegate.put(sortKey, primaryKey, entry);
    }

    @Override
    public void remove(S sortKey, K primaryKey) {
        maybeFail(Op.REMOVE);
        delegate.remove(sortKey, primaryKey);
    }

    @Override
    public List<E> range(S fromInclusive, S toInclusive) {
        maybeFail(Op.RANGE);
        return delegate.range(fromInclusive, toInclusive);
    }

    @Override
    public int size() {
        maybeFail(Op.SIZE);
        return delegate.size();
    }

    @Override
    public List<IndexedSlot<K, S, E>> slots() {
        maybeFail(Op.SLOTS);
        return delegate.slots();
    }
}
