package com.example.gsb.dualindex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Dual-index store keeping a hash index (primary-key lookup) and an ordered
 * index (sort-key range queries) in sync. Both indexes reference the same
 * immutable {@link Entry} instances.
 *
 * <p>Every mutating operation updates both indexes under a write lock and
 * rolls the first side back when the second side fails, so the two indexes
 * never expose a half-applied state. Readers run under a read lock and
 * therefore always observe a consistent snapshot of both indexes.
 *
 * <p>Consistency can be verified with {@link #checkConsistency()} and
 * restored with {@link #repair()}; both scans feed the statistics available
 * via {@link #stats()}.
 */
public class DualIndexStore<K, S extends Comparable<? super S>, V> {

    private final HashIndex<K, Entry<K, S, V>> hashIndex;
    private final OrderedIndex<K, S, Entry<K, S, V>> orderedIndex;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong updateCount = new AtomicLong();
    private final AtomicLong inconsistencyCount = new AtomicLong();
    private final AtomicLong repairedCount = new AtomicLong();

    public DualIndexStore() {
        this(new MapHashIndex<>(), new TreeMapOrderedIndex<>());
    }

    public DualIndexStore(HashIndex<K, Entry<K, S, V>> hashIndex,
                          OrderedIndex<K, S, Entry<K, S, V>> orderedIndex) {
        this.hashIndex = Objects.requireNonNull(hashIndex, "hashIndex");
        this.orderedIndex = Objects.requireNonNull(orderedIndex, "orderedIndex");
    }

    /** Inserts or replaces the entry for {@code key} in both indexes. */
    public void put(K key, S sortKey, V value) {
        Entry<K, S, V> entry = new Entry<>(key, sortKey, value);
        lock.writeLock().lock();
        try {
            Entry<K, S, V> previous;
            try {
                previous = hashIndex.get(key);
                hashIndex.put(key, entry);
            } catch (RuntimeException hashFailure) {
                throw new IndexUpdateException(
                        "hash index update failed for key " + key + "; ordered index untouched", hashFailure);
            }
            try {
                if (previous != null) {
                    orderedIndex.remove(previous.sortKey(), key);
                }
                try {
                    orderedIndex.put(sortKey, key, entry);
                } catch (RuntimeException orderedPutFailure) {
                    if (previous != null) {
                        restoreOrdered(previous, orderedPutFailure);
                    }
                    throw orderedPutFailure;
                }
            } catch (RuntimeException orderedFailure) {
                restoreHash(key, previous, orderedFailure);
                throw new IndexUpdateException(
                        "put failed for key " + key + "; hash index rolled back", orderedFailure);
            }
            updateCount.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes the entry for {@code key} from both indexes; returns false when absent. */
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.writeLock().lock();
        try {
            Entry<K, S, V> previous = hashIndex.get(key);
            if (previous == null) {
                return false;
            }
            try {
                hashIndex.remove(key);
            } catch (RuntimeException hashFailure) {
                throw new IndexUpdateException(
                        "hash index remove failed for key " + key + "; ordered index untouched", hashFailure);
            }
            try {
                orderedIndex.remove(previous.sortKey(), key);
            } catch (RuntimeException orderedFailure) {
                restoreHash(key, previous, orderedFailure);
                throw new IndexUpdateException(
                        "remove failed for key " + key + "; hash index rolled back", orderedFailure);
            }
            updateCount.incrementAndGet();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Moves the entry for {@code key} to a new sort-key position. The old
     * ordered slot is removed before the new one is inserted and the hash
     * index is repointed last, so concurrent readers never observe the key
     * twice or at a position that disagrees with the hash index.
     */
    public Entry<K, S, V> updateSortKey(K key, S newSortKey) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(newSortKey, "newSortKey");
        lock.writeLock().lock();
        try {
            Entry<K, S, V> current = hashIndex.get(key);
            if (current == null) {
                throw new NoSuchElementException("no entry for key " + key);
            }
            if (current.sortKey().equals(newSortKey)) {
                return current;
            }
            Entry<K, S, V> updated = current.withSortKey(newSortKey);
            try {
                orderedIndex.remove(current.sortKey(), key);
            } catch (RuntimeException orderedFailure) {
                throw new IndexUpdateException(
                        "ordered index remove failed for key " + key + "; hash index untouched", orderedFailure);
            }
            try {
                orderedIndex.put(newSortKey, key, updated);
            } catch (RuntimeException orderedFailure) {
                restoreOrdered(current, orderedFailure);
                throw new IndexUpdateException(
                        "sort key update failed for key " + key + "; ordered index rolled back", orderedFailure);
            }
            try {
                hashIndex.put(key, updated);
            } catch (RuntimeException hashFailure) {
                try {
                    orderedIndex.remove(newSortKey, key);
                    restoreOrdered(current, hashFailure);
                } catch (RuntimeException rollbackFailure) {
                    hashFailure.addSuppressed(rollbackFailure);
                }
                throw new IndexUpdateException(
                        "hash index update failed for key " + key + "; ordered index rolled back", hashFailure);
            }
            updateCount.incrementAndGet();
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Point lookup by primary key, or {@code null} when absent. */
    public Entry<K, S, V> get(K key) {
        lock.readLock().lock();
        try {
            return hashIndex.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Entries with sort key within the inclusive bounds, in index order. */
    public List<Entry<K, S, V>> rangeQuery(S fromInclusive, S toInclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
        lock.readLock().lock();
        try {
            return orderedIndex.range(fromInclusive, toInclusive);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Scans both indexes and compares their entry sets. The returned report
     * lists every divergence in detail; finding any increments the
     * inconsistency counter.
     */
    public ConsistencyReport<K, S, V> checkConsistency() {
        lock.readLock().lock();
        try {
            ConsistencyReport<K, S, V> report = scanForDifferences();
            if (!report.isConsistent()) {
                inconsistencyCount.incrementAndGet();
            }
            return report;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Repairs both indexes from a fresh difference scan: entries missing on
     * one side are copied from the other, stale ordered slots are removed and
     * mismatched ordered entries are realigned with the hash index. The
     * operation is idempotent - a second run on a healthy store repairs
     * nothing and reports zero.
     */
    public RepairResult<K, S, V> repair() {
        lock.writeLock().lock();
        try {
            ConsistencyReport<K, S, V> report = scanForDifferences();
            if (report.isConsistent()) {
                return new RepairResult<>(0, List.of(), report);
            }
            inconsistencyCount.incrementAndGet();
            List<String> actions = new ArrayList<>();
            for (IndexDifference<K, S, V> diff : report.differences()) {
                switch (diff.type()) {
                    case MISSING_IN_ORDERED -> {
                        Entry<K, S, V> entry = diff.hashEntry();
                        orderedIndex.put(entry.sortKey(), entry.primaryKey(), entry);
                        actions.add("reinserted into ordered index: " + diff.describe());
                    }
                    case MISSING_IN_HASH -> {
                        Entry<K, S, V> entry = diff.orderedEntry();
                        hashIndex.put(entry.primaryKey(), entry);
                        actions.add("reinserted into hash index: " + diff.describe());
                    }
                    case STALE_ORDERED_SLOT -> {
                        orderedIndex.remove(diff.actualSortKey(), diff.primaryKey());
                        actions.add("removed stale ordered slot: " + diff.describe());
                    }
                    case ENTRY_MISMATCH -> {
                        Entry<K, S, V> entry = diff.hashEntry();
                        orderedIndex.remove(diff.actualSortKey(), diff.primaryKey());
                        orderedIndex.put(entry.sortKey(), entry.primaryKey(), entry);
                        actions.add("realigned ordered entry with hash index: " + diff.describe());
                    }
                }
            }
            repairedCount.addAndGet(actions.size());
            return new RepairResult<>(actions.size(), actions, report);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Snapshot of sizes, update, inconsistency and repair counters. */
    public IndexStats stats() {
        lock.readLock().lock();
        try {
            return new IndexStats(
                    hashIndex.size(),
                    orderedIndex.size(),
                    updateCount.get(),
                    inconsistencyCount.get(),
                    repairedCount.get());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Direct access to the hash index, e.g. for inspection or drift simulation. */
    public HashIndex<K, Entry<K, S, V>> hashIndex() {
        return hashIndex;
    }

    /** Direct access to the ordered index, e.g. for inspection or drift simulation. */
    public OrderedIndex<K, S, Entry<K, S, V>> orderedIndex() {
        return orderedIndex;
    }

    private ConsistencyReport<K, S, V> scanForDifferences() {
        List<IndexDifference<K, S, V>> differences = new ArrayList<>();
        Map<K, Entry<K, S, V>> hashEntries = new LinkedHashMap<>();
        for (Entry<K, S, V> entry : hashIndex.entries()) {
            hashEntries.put(entry.primaryKey(), entry);
        }
        List<IndexedSlot<K, S, Entry<K, S, V>>> slots = orderedIndex.slots();
        Map<K, List<IndexedSlot<K, S, Entry<K, S, V>>>> slotsByKey = new LinkedHashMap<>();
        for (IndexedSlot<K, S, Entry<K, S, V>> slot : slots) {
            slotsByKey.computeIfAbsent(slot.primaryKey(), key -> new ArrayList<>()).add(slot);
        }
        for (Entry<K, S, V> entry : hashEntries.values()) {
            IndexedSlot<K, S, Entry<K, S, V>> matching = null;
            for (IndexedSlot<K, S, Entry<K, S, V>> slot : slotsByKey.getOrDefault(entry.primaryKey(), List.of())) {
                if (slot.sortKey().equals(entry.sortKey())) {
                    matching = slot;
                    break;
                }
            }
            if (matching == null) {
                differences.add(new IndexDifference<>(
                        IndexDifference.Type.MISSING_IN_ORDERED,
                        entry.primaryKey(), entry.sortKey(), null, entry, null));
            } else if (!matching.entry().equals(entry)) {
                differences.add(new IndexDifference<>(
                        IndexDifference.Type.ENTRY_MISMATCH,
                        entry.primaryKey(), entry.sortKey(), matching.sortKey(), entry, matching.entry()));
            }
        }
        for (IndexedSlot<K, S, Entry<K, S, V>> slot : slots) {
            Entry<K, S, V> hashEntry = hashEntries.get(slot.primaryKey());
            if (hashEntry == null) {
                differences.add(new IndexDifference<>(
                        IndexDifference.Type.MISSING_IN_HASH,
                        slot.primaryKey(), null, slot.sortKey(), null, slot.entry()));
            } else if (!slot.sortKey().equals(hashEntry.sortKey())) {
                differences.add(new IndexDifference<>(
                        IndexDifference.Type.STALE_ORDERED_SLOT,
                        slot.primaryKey(), hashEntry.sortKey(), slot.sortKey(), hashEntry, slot.entry()));
            }
        }
        return new ConsistencyReport<>(differences);
    }

    private void restoreHash(K key, Entry<K, S, V> previous, RuntimeException cause) {
        try {
            if (previous == null) {
                hashIndex.remove(key);
            } else {
                hashIndex.put(key, previous);
            }
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private void restoreOrdered(Entry<K, S, V> previous, RuntimeException cause) {
        try {
            orderedIndex.put(previous.sortKey(), previous.primaryKey(), previous);
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
