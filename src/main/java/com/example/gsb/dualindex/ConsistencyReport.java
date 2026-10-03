package com.example.gsb.dualindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次全量一致性检查的结果，包含差异明细而非仅布尔结论。
 */
public final class ConsistencyReport {

    /** 差异类别。 */
    public enum DiffType {
        /** 仅存在于哈希索引，有序索引缺失。 */
        ONLY_IN_HASH,
        /** 仅存在于有序索引，哈希索引缺失。 */
        ONLY_IN_ORDERED,
        /** 两侧都存在，但排序键或值不一致。 */
        VALUE_MISMATCH
    }

    /** 单条差异明细。 */
    public record Diff(DiffType type, Object primaryKey, Entry<?, ?> hashEntry, Entry<?, ?> orderedEntry) {
        @Override
        public String toString() {
            return switch (type) {
                case ONLY_IN_HASH -> "ONLY_IN_HASH pk=" + primaryKey + " hash=" + hashEntry;
                case ONLY_IN_ORDERED -> "ONLY_IN_ORDERED pk=" + primaryKey + " ordered=" + orderedEntry;
                case VALUE_MISMATCH -> "VALUE_MISMATCH pk=" + primaryKey
                        + " hash=" + hashEntry + " ordered=" + orderedEntry;
            };
        }
    }

    private final List<Diff> diffs;
    private final int hashIndexSize;
    private final int orderedIndexSize;

    ConsistencyReport(List<Diff> diffs, int hashIndexSize, int orderedIndexSize) {
        this.diffs = Collections.unmodifiableList(new ArrayList<>(diffs));
        this.hashIndexSize = hashIndexSize;
        this.orderedIndexSize = orderedIndexSize;
    }

    public boolean isConsistent() {
        return diffs.isEmpty();
    }

    public List<Diff> diffs() {
        return diffs;
    }

    public int hashIndexSize() {
        return hashIndexSize;
    }

    public int orderedIndexSize() {
        return orderedIndexSize;
    }

    /** 人类可读的差异明细报告。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConsistencyReport{consistent=").append(isConsistent())
                .append(", hashIndexSize=").append(hashIndexSize)
                .append(", orderedIndexSize=").append(orderedIndexSize)
                .append(", diffCount=").append(diffs.size()).append('}');
        for (Diff diff : diffs) {
            sb.append(System.lineSeparator()).append("  ").append(diff);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
