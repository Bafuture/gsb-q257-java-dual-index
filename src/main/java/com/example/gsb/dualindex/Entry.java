package com.example.gsb.dualindex;

import java.util.Objects;

/**
 * 一条被双索引同时引用的数据条目。
 *
 * <p>哈希索引与有序索引持有同一个 {@code Entry} 实例，不存在两份拷贝。
 * 排序键可变（通过 {@link DualIndex#updateSortKey}），主键与值不可变。
 */
public final class Entry<K, V> {

    private final K primaryKey;
    private final V value;
    private long sortKey;

    Entry(K primaryKey, long sortKey, V value) {
        this.primaryKey = Objects.requireNonNull(primaryKey, "primaryKey");
        this.sortKey = sortKey;
        this.value = value;
    }

    public K primaryKey() {
        return primaryKey;
    }

    public long sortKey() {
        return sortKey;
    }

    public V value() {
        return value;
    }

    void sortKey(long newSortKey) {
        this.sortKey = newSortKey;
    }

    /** 索引语义：按主键判等（同一主键在任一索引中至多出现一次）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Entry<?, ?> other)) {
            return false;
        }
        return primaryKey.equals(other.primaryKey);
    }

    @Override
    public int hashCode() {
        return primaryKey.hashCode();
    }

    @Override
    public String toString() {
        return "Entry{pk=" + primaryKey + ", sortKey=" + sortKey + ", value=" + value + '}';
    }
}
