package com.example.gsb.dualindex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 有序哈希双索引组件。
 *
 * <p>同一份 {@link Entry} 数据同时被两个索引引用：
 * <ul>
 *   <li>哈希索引：主键 → 条目，O(1) 定位；</li>
 *   <li>有序索引：排序键 → 条目集合（TreeMap），支持范围查询。</li>
 * </ul>
 *
 * <p>所有写操作（put/delete/updateSortKey）在写锁内以"先改一侧、失败即回滚"
 * 的方式原子地落到两个索引上；读操作持有读锁，因此查询永远不会看到
 * 只更新了一侧的中间状态。修复与一致性检查也分别在写锁/读锁内完成。
 *
 * @param <K> 主键类型
 * @param <V> 值类型
 */
public final class DualIndex<K, V> {

    /** 修复时的权威侧。 */
    public enum AuthoritativeSide {
        /** 以哈希索引为准修复有序索引。 */
        HASH,
        /** 以有序索引为准修复哈希索引。 */
        ORDERED
    }

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<K, Entry<K, V>> hashIndex = new HashMap<>();
    /** 排序键 → (主键 → 条目)，允许不同主键共享同一排序键。 */
    private final TreeMap<Long, LinkedHashMap<K, Entry<K, V>>> orderedIndex = new TreeMap<>();
    private int orderedSize;

    private final FaultControl faultControl = new FaultControl();

    private final AtomicLong putCount = new AtomicLong();
    private final AtomicLong deleteCount = new AtomicLong();
    private final AtomicLong sortKeyUpdateCount = new AtomicLong();
    private final AtomicLong inconsistenciesFound = new AtomicLong();
    private final AtomicLong checkCount = new AtomicLong();
    private final AtomicLong entriesRepaired = new AtomicLong();

    // ------------------------------------------------------------------
    // 写入路径（带事务回滚）
    // ------------------------------------------------------------------

    /**
     * 写入或覆盖一条数据，同时更新两个索引；任一侧失败则整体回滚。
     *
     * @throws IndexUpdateException 某一侧索引操作失败（已回滚到操作前状态）
     */
    public void put(K primaryKey, long sortKey, V value) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        lock.writeLock().lock();
        try {
            Entry<K, V> previous = hashIndex.get(primaryKey);
            Entry<K, V> entry = new Entry<>(primaryKey, sortKey, value);
            if (previous == null) {
                putNew(primaryKey, entry);
            } else {
                replaceExisting(primaryKey, previous, entry);
            }
            putCount.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void putNew(K primaryKey, Entry<K, V> entry) {
        try {
            hashPut(primaryKey, entry);
        } catch (RuntimeException e) {
            throw new IndexUpdateException("put: hash index update failed, nothing changed", e);
        }
        try {
            orderedPut(entry);
        } catch (RuntimeException e) {
            hashRemoveDirect(primaryKey);
            throw new IndexUpdateException("put: ordered index update failed, hash index rolled back", e);
        }
    }

    private void replaceExisting(K primaryKey, Entry<K, V> previous, Entry<K, V> entry) {
        try {
            orderedRemove(previous);
        } catch (RuntimeException e) {
            throw new IndexUpdateException("put(replace): ordered index remove failed, nothing changed", e);
        }
        try {
            hashPut(primaryKey, entry);
        } catch (RuntimeException e) {
            orderedAddDirect(previous);
            throw new IndexUpdateException("put(replace): hash index update failed, ordered index rolled back", e);
        }
        try {
            orderedPut(entry);
        } catch (RuntimeException e) {
            hashPutDirect(primaryKey, previous);
            orderedAddDirect(previous);
            throw new IndexUpdateException("put(replace): ordered index re-insert failed, both indexes rolled back", e);
        }
    }

    /**
     * 删除一条数据，同时更新两个索引；任一侧失败则整体回滚。
     *
     * @return 主键存在且删除成功时为 {@code true}
     * @throws IndexUpdateException 某一侧索引操作失败（已回滚到操作前状态）
     */
    public boolean delete(K primaryKey) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        lock.writeLock().lock();
        try {
            Entry<K, V> previous = hashIndex.get(primaryKey);
            if (previous == null) {
                return false;
            }
            try {
                hashRemove(primaryKey);
            } catch (RuntimeException e) {
                throw new IndexUpdateException("delete: hash index remove failed, nothing changed", e);
            }
            try {
                orderedRemove(previous);
            } catch (RuntimeException e) {
                hashPutDirect(primaryKey, previous);
                throw new IndexUpdateException("delete: ordered index remove failed, hash index rolled back", e);
            }
            deleteCount.incrementAndGet();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新排序键：先从有序索引移除旧位置，再插入新位置。
     *
     * <p>整个过程中哈希索引始终指向同一个条目，且条目在有序索引中
     * 任一时刻至多出现一次，并发查询不会看到重复条目。
     *
     * @return 主键存在时为 {@code true}
     * @throws IndexUpdateException 有序索引操作失败（已回滚到操作前状态）
     */
    public boolean updateSortKey(K primaryKey, long newSortKey) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        lock.writeLock().lock();
        try {
            Entry<K, V> entry = hashIndex.get(primaryKey);
            if (entry == null) {
                return false;
            }
            long oldSortKey = entry.sortKey();
            if (oldSortKey == newSortKey) {
                return true;
            }
            try {
                orderedRemove(entry);
            } catch (RuntimeException e) {
                throw new IndexUpdateException("updateSortKey: ordered index remove failed, nothing changed", e);
            }
            entry.sortKey(newSortKey);
            try {
                orderedPut(entry);
            } catch (RuntimeException e) {
                entry.sortKey(oldSortKey);
                orderedAddDirect(entry);
                throw new IndexUpdateException("updateSortKey: ordered index re-insert failed, rolled back", e);
            }
            sortKeyUpdateCount.incrementAndGet();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 查询路径
    // ------------------------------------------------------------------

    /** 按主键定位，未命中返回 {@code null}。 */
    public Entry<K, V> get(K primaryKey) {
        lock.readLock().lock();
        try {
            return hashIndex.get(primaryKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按排序键范围查询（闭区间），结果按 (排序键, 插入序) 排列。 */
    public List<Entry<K, V>> range(long fromSortKey, long toSortKey) {
        lock.readLock().lock();
        try {
            List<Entry<K, V>> result = new ArrayList<>();
            for (LinkedHashMap<K, Entry<K, V>> bucket
                    : orderedIndex.subMap(fromSortKey, true, toSortKey, true).values()) {
                result.addAll(bucket.values());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 哈希索引当前条目数。 */
    public int hashIndexSize() {
        lock.readLock().lock();
        try {
            return hashIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 有序索引当前条目数。 */
    public int orderedIndexSize() {
        lock.readLock().lock();
        try {
            return orderedSize;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 一致性检查与修复
    // ------------------------------------------------------------------

    /**
     * 全量扫描两个索引并比对条目集合，返回包含差异明细的报告。
     * 发现不一致时累加统计计数。
     */
    public ConsistencyReport checkConsistency() {
        lock.readLock().lock();
        try {
            ConsistencyReport report = collectDiffs();
            checkCount.incrementAndGet();
            inconsistenciesFound.addAndGet(report.diffs().size());
            return report;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 根据差异自动修复：以指定一侧为准，把另一侧补齐/纠正。
     * 幂等——没有差异时不做任何修改、修复条数为 0。
     */
    public RepairReport repair(AuthoritativeSide authoritative) {
        Objects.requireNonNull(authoritative, "authoritative");
        lock.writeLock().lock();
        try {
            ConsistencyReport report = collectDiffs();
            long repaired = 0;
            for (ConsistencyReport.Diff diff : report.diffs()) {
                @SuppressWarnings("unchecked")
                K primaryKey = (K) diff.primaryKey();
                switch (diff.type()) {
                    case ONLY_IN_HASH -> {
                        if (authoritative == AuthoritativeSide.HASH) {
                            @SuppressWarnings("unchecked")
                            Entry<K, V> entry = (Entry<K, V>) diff.hashEntry();
                            orderedAddDirect(entry);
                        } else {
                            hashRemoveDirect(primaryKey);
                        }
                        repaired++;
                    }
                    case ONLY_IN_ORDERED -> {
                        if (authoritative == AuthoritativeSide.HASH) {
                            @SuppressWarnings("unchecked")
                            Entry<K, V> entry = (Entry<K, V>) diff.orderedEntry();
                            orderedRemoveDirect(entry);
                        } else {
                            @SuppressWarnings("unchecked")
                            Entry<K, V> entry = (Entry<K, V>) diff.orderedEntry();
                            hashPutDirect(primaryKey, entry);
                        }
                        repaired++;
                    }
                    case VALUE_MISMATCH -> {
                        if (authoritative == AuthoritativeSide.HASH) {
                            @SuppressWarnings("unchecked")
                            Entry<K, V> hashEntry = (Entry<K, V>) diff.hashEntry();
                            @SuppressWarnings("unchecked")
                            Entry<K, V> orderedEntry = (Entry<K, V>) diff.orderedEntry();
                            orderedRemoveDirect(orderedEntry);
                            orderedAddDirect(hashEntry);
                        } else {
                            @SuppressWarnings("unchecked")
                            Entry<K, V> orderedEntry = (Entry<K, V>) diff.orderedEntry();
                            hashPutDirect(primaryKey, orderedEntry);
                        }
                        repaired++;
                    }
                }
            }
            entriesRepaired.addAndGet(repaired);
            boolean consistentAfterRepair = collectDiffs().isConsistent();
            return new RepairReport(repaired, consistentAfterRepair);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 调用方必须持有锁（读锁或写锁）。 */
    private ConsistencyReport collectDiffs() {
        Map<K, Entry<K, V>> orderedByKey = new HashMap<>();
        for (LinkedHashMap<K, Entry<K, V>> bucket : orderedIndex.values()) {
            orderedByKey.putAll(bucket);
        }
        List<ConsistencyReport.Diff> diffs = new ArrayList<>();
        for (Map.Entry<K, Entry<K, V>> e : hashIndex.entrySet()) {
            Entry<K, V> orderedEntry = orderedByKey.remove(e.getKey());
            if (orderedEntry == null) {
                diffs.add(new ConsistencyReport.Diff(
                        ConsistencyReport.DiffType.ONLY_IN_HASH, e.getKey(), e.getValue(), null));
            } else if (orderedEntry != e.getValue()
                    && (orderedEntry.sortKey() != e.getValue().sortKey()
                    || !Objects.equals(orderedEntry.value(), e.getValue().value()))) {
                diffs.add(new ConsistencyReport.Diff(
                        ConsistencyReport.DiffType.VALUE_MISMATCH, e.getKey(), e.getValue(), orderedEntry));
            }
        }
        for (Map.Entry<K, Entry<K, V>> e : orderedByKey.entrySet()) {
            diffs.add(new ConsistencyReport.Diff(
                    ConsistencyReport.DiffType.ONLY_IN_ORDERED, e.getKey(), null, e.getValue()));
        }
        return new ConsistencyReport(diffs, hashIndex.size(), orderedSize);
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    /** 当前统计快照。 */
    public Stats stats() {
        lock.readLock().lock();
        try {
            return new Stats(
                    hashIndex.size(),
                    orderedSize,
                    putCount.get(),
                    deleteCount.get(),
                    sortKeyUpdateCount.get(),
                    inconsistenciesFound.get(),
                    checkCount.get(),
                    entriesRepaired.get());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 故障注入开关（测试用）。 */
    public FaultControl faultControl() {
        return faultControl;
    }

    // ------------------------------------------------------------------
    // 底层索引原语：带故障注入的检查版本 + 回滚用的直写版本
    // ------------------------------------------------------------------

    private void hashPut(K key, Entry<K, V> entry) {
        if (faultControl.failHashPut) {
            throw new IllegalStateException("injected fault: hash put");
        }
        hashPutDirect(key, entry);
    }

    private void hashPutDirect(K key, Entry<K, V> entry) {
        hashIndex.put(key, entry);
    }

    private void hashRemove(K key) {
        if (faultControl.failHashRemove) {
            throw new IllegalStateException("injected fault: hash remove");
        }
        hashRemoveDirect(key);
    }

    private void hashRemoveDirect(K key) {
        hashIndex.remove(key);
    }

    private void orderedPut(Entry<K, V> entry) {
        if (faultControl.failOrderedPut) {
            throw new IllegalStateException("injected fault: ordered put");
        }
        orderedAddDirect(entry);
    }

    private void orderedAddDirect(Entry<K, V> entry) {
        orderedIndex.computeIfAbsent(entry.sortKey(), k -> new LinkedHashMap<>())
                .put(entry.primaryKey(), entry);
        orderedSize++;
    }

    private void orderedRemove(Entry<K, V> entry) {
        if (faultControl.failOrderedRemove) {
            throw new IllegalStateException("injected fault: ordered remove");
        }
        orderedRemoveDirect(entry);
    }

    private void orderedRemoveDirect(Entry<K, V> entry) {
        LinkedHashMap<K, Entry<K, V>> bucket = orderedIndex.get(entry.sortKey());
        if (bucket == null || bucket.remove(entry.primaryKey()) == null) {
            throw new IndexCorruptedException(
                    "ordered index missing entry for pk=" + entry.primaryKey()
                            + " at sortKey=" + entry.sortKey());
        }
        orderedSize--;
        if (bucket.isEmpty()) {
            orderedIndex.remove(entry.sortKey());
        }
    }
}
