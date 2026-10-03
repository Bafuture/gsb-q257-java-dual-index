package com.example.gsb.dualindex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Default {@link OrderedIndex} backed by a {@link TreeMap} of sort-key buckets.
 * Each bucket is a {@link LinkedHashMap} keyed by primary key so that equal
 * sort keys are supported while insertion/scan order stays deterministic.
 */
public class TreeMapOrderedIndex<K, S extends Comparable<? super S>, E> implements OrderedIndex<K, S, E> {

    private final NavigableMap<S, Map<K, E>> buckets = new TreeMap<>();
    private int size;

    @Override
    public void put(S sortKey, K primaryKey, E entry) {
        Map<K, E> bucket = buckets.computeIfAbsent(sortKey, key -> new LinkedHashMap<>());
        E previous = bucket.put(primaryKey, entry);
        if (previous == null) {
            size++;
        }
    }

    @Override
    public void remove(S sortKey, K primaryKey) {
        Map<K, E> bucket = buckets.get(sortKey);
        if (bucket != null && bucket.remove(primaryKey) != null) {
            size--;
            if (bucket.isEmpty()) {
                buckets.remove(sortKey);
            }
        }
    }

    @Override
    public List<E> range(S fromInclusive, S toInclusive) {
        if (fromInclusive.compareTo(toInclusive) > 0) {
            return List.of();
        }
        List<E> result = new ArrayList<>();
        for (Map<K, E> bucket : buckets.subMap(fromInclusive, true, toInclusive, true).values()) {
            result.addAll(bucket.values());
        }
        return result;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public List<IndexedSlot<K, S, E>> slots() {
        List<IndexedSlot<K, S, E>> result = new ArrayList<>(size);
        buckets.forEach((sortKey, bucket) ->
                bucket.forEach((primaryKey, entry) -> result.add(new IndexedSlot<>(sortKey, primaryKey, entry))));
        return result;
    }
}
