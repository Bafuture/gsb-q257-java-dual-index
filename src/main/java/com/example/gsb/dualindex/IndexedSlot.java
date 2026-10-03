package com.example.gsb.dualindex;

/**
 * One entry located at a position in the ordered index.
 * {@code sortKey} is the position the entry is stored at (which may differ
 * from the sort key carried by the entry itself when the index has drifted).
 */
public record IndexedSlot<K, S, E>(S sortKey, K primaryKey, E entry) {
}
