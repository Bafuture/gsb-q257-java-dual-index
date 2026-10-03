package com.example.gsb.dualindex;

import java.util.Objects;

/**
 * Immutable data record shared (by reference) by both indexes.
 */
public record Entry<K, S, V>(K primaryKey, S sortKey, V value) {

    public Entry {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(sortKey, "sortKey");
    }

    public Entry<K, S, V> withSortKey(S newSortKey) {
        Objects.requireNonNull(newSortKey, "newSortKey");
        return new Entry<>(primaryKey, newSortKey, value);
    }
}
