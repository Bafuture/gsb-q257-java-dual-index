package com.example.gsb.dualindex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link HashIndex} backed by a {@link HashMap}.
 */
public class MapHashIndex<K, E> implements HashIndex<K, E> {

    private final Map<K, E> map;

    public MapHashIndex() {
        this(new HashMap<>());
    }

    public MapHashIndex(Map<K, E> backingMap) {
        this.map = backingMap;
    }

    @Override
    public E get(K key) {
        return map.get(key);
    }

    @Override
    public void put(K key, E entry) {
        map.put(key, entry);
    }

    @Override
    public void remove(K key) {
        map.remove(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public List<E> entries() {
        return new ArrayList<>(map.values());
    }
}
