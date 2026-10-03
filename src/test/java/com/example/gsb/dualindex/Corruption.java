package com.example.gsb.dualindex;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 测试工具：绕过事务逻辑直接篡改单个索引，制造不一致场景。
 */
final class Corruption {

    private Corruption() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Entry<String, String>> hashIndexOf(DualIndex<String, String> index) {
        try {
            Field field = DualIndex.class.getDeclaredField("hashIndex");
            field.setAccessible(true);
            return (Map<String, Entry<String, String>>) field.get(index);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static TreeMap<Long, LinkedHashMap<String, Entry<String, String>>> orderedIndexOf(
            DualIndex<String, String> index) {
        try {
            Field field = DualIndex.class.getDeclaredField("orderedIndex");
            field.setAccessible(true);
            return (TreeMap<Long, LinkedHashMap<String, Entry<String, String>>>) field.get(index);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void setOrderedSize(DualIndex<String, String> index, int size) {
        try {
            Field field = DualIndex.class.getDeclaredField("orderedSize");
            field.setAccessible(true);
            field.setInt(index, size);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** 只在哈希索引中插入一条数据。 */
    static void addOnlyInHash(DualIndex<String, String> index, String pk, long sortKey, String value) {
        hashIndexOf(index).put(pk, new Entry<>(pk, sortKey, value));
    }

    /** 只在有序索引中插入一条数据。 */
    static void addOnlyInOrdered(DualIndex<String, String> index, String pk, long sortKey, String value) {
        Entry<String, String> entry = new Entry<>(pk, sortKey, value);
        orderedIndexOf(index).computeIfAbsent(sortKey, k -> new LinkedHashMap<>()).put(pk, entry);
        setOrderedSize(index, index.orderedIndexSize() + 1);
    }

    /** 两侧都有该主键，但引用的是内容不同的两个条目实例。 */
    static void makeMismatch(DualIndex<String, String> index, String pk,
                             long hashSortKey, String hashValue,
                             long orderedSortKey, String orderedValue) {
        hashIndexOf(index).put(pk, new Entry<>(pk, hashSortKey, hashValue));
        Entry<String, String> orderedEntry = new Entry<>(pk, orderedSortKey, orderedValue);
        orderedIndexOf(index).computeIfAbsent(orderedSortKey, k -> new LinkedHashMap<>())
                .put(pk, orderedEntry);
        setOrderedSize(index, index.orderedIndexSize() + 1);
    }

    /** 从哈希索引中移除，制造 ONLY_IN_ORDERED。 */
    static void removeFromHashOnly(DualIndex<String, String> index, String pk) {
        hashIndexOf(index).remove(pk);
    }

    /** 从有序索引中移除，制造 ONLY_IN_HASH。 */
    static void removeFromOrderedOnly(DualIndex<String, String> index, String pk, long sortKey) {
        LinkedHashMap<String, Entry<String, String>> bucket = orderedIndexOf(index).get(sortKey);
        bucket.remove(pk);
        if (bucket.isEmpty()) {
            orderedIndexOf(index).remove(sortKey);
        }
        setOrderedSize(index, index.orderedIndexSize() - 1);
    }
}
