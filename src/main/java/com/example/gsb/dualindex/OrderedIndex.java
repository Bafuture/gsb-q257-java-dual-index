package com.example.gsb.dualindex;

import java.util.List;

/**
 * Sort-key (ordered) index supporting range queries.
 *
 * <p>Multiple entries may share the same sort key; they are distinguished by
 * primary key. Contract: every operation must be atomic and leave the index
 * unchanged when it throws.
 */
public interface OrderedIndex<K, S, E> {

    void put(S sortKey, K primaryKey, E entry);

    void remove(S sortKey, K primaryKey);

    /** Entries whose sort key is within the inclusive bounds, sorted by (sortKey, primaryKey). */
    List<E> range(S fromInclusive, S toInclusive);

    int size();

    /** Full scan of every slot, including the sort-key position it is stored at. */
    List<IndexedSlot<K, S, E>> slots();
}
