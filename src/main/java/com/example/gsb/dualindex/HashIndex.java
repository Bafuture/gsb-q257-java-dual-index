package com.example.gsb.dualindex;

import java.util.List;

/**
 * Primary-key (hash) index.
 *
 * <p>Contract for every operation: it must be atomic. When an operation throws,
 * the index must be left exactly as it was before the call (no partial state),
 * so callers can rely on rollback bookkeeping.
 */
public interface HashIndex<K, E> {

    E get(K key);

    void put(K key, E entry);

    void remove(K key);

    int size();

    List<E> entries();
}
