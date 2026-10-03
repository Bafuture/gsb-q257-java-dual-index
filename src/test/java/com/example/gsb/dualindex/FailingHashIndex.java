package com.example.gsb.dualindex;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@link HashIndex} decorator that fails the next invocation of an armed
 * operation exactly once, before touching the delegate.
 */
final class FailingHashIndex<K, E> implements HashIndex<K, E> {

    enum Op { GET, PUT, REMOVE, SIZE, ENTRIES }

    private final MapHashIndex<K, E> delegate = new MapHashIndex<>();
    private final Set<Op> armed = EnumSet.noneOf(Op.class);

    void failNext(Op op) {
        armed.add(op);
    }

    MapHashIndex<K, E> delegate() {
        return delegate;
    }

    private void maybeFail(Op op) {
        if (armed.remove(op)) {
            throw new InjectedFailureException("injected hash-index failure on " + op);
        }
    }

    @Override
    public E get(K key) {
        maybeFail(Op.GET);
        return delegate.get(key);
    }

    @Override
    public void put(K key, E entry) {
        maybeFail(Op.PUT);
        delegate.put(key, entry);
    }

    @Override
    public void remove(K key) {
        maybeFail(Op.REMOVE);
        delegate.remove(key);
    }

    @Override
    public int size() {
        maybeFail(Op.SIZE);
        return delegate.size();
    }

    @Override
    public List<E> entries() {
        maybeFail(Op.ENTRIES);
        return delegate.entries();
    }
}
