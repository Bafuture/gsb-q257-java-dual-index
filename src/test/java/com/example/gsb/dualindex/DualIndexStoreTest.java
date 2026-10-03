package com.example.gsb.dualindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DualIndexStoreTest {

    private MapHashIndex<String, Entry<String, Long, String>> hashIndex;
    private TreeMapOrderedIndex<String, Long, Entry<String, Long, String>> orderedIndex;
    private DualIndexStore<String, Long, String> store;

    @BeforeEach
    void setUp() {
        hashIndex = new MapHashIndex<>();
        orderedIndex = new TreeMapOrderedIndex<>();
        store = new DualIndexStore<>(hashIndex, orderedIndex);
    }

    @Test
    void putMaintainsBothIndexesAndSharesTheSameEntryInstance() {
        store.put("a", 30L, "va");
        store.put("b", 10L, "vb");
        store.put("c", 20L, "vc");

        assertThat(store.get("b").value()).isEqualTo("vb");
        assertThat(store.rangeQuery(0L, 100L))
                .extracting(Entry::primaryKey)
                .containsExactly("b", "c", "a");

        Entry<String, Long, String> hashEntry = hashIndex.get("a");
        Entry<String, Long, String> orderedEntry = orderedIndex.range(30L, 30L).get(0);
        assertThat(orderedEntry).isSameAs(hashEntry);
        assertThat(store.get("a")).isSameAs(hashEntry);

        IndexStats stats = store.stats();
        assertThat(stats.hashIndexSize()).isEqualTo(3);
        assertThat(stats.orderedIndexSize()).isEqualTo(3);
        assertThat(stats.updateCount()).isEqualTo(3);
    }

    @Test
    void rangeQueryHonoursInclusiveBoundsAndDuplicateSortKeys() {
        store.put("a", 10L, "va");
        store.put("b", 20L, "vb");
        store.put("c", 20L, "vc");
        store.put("d", 30L, "vd");

        assertThat(store.rangeQuery(20L, 20L))
                .extracting(Entry::primaryKey)
                .containsExactlyInAnyOrder("b", "c");
        assertThat(store.rangeQuery(10L, 20L))
                .extracting(Entry::primaryKey)
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(store.rangeQuery(11L, 19L)).isEmpty();
        assertThat(store.rangeQuery(40L, 10L)).isEmpty();
    }

    @Test
    void putWithExistingKeyMovesEntryWithoutDuplicates() {
        store.put("a", 30L, "va");
        store.put("b", 10L, "vb");

        store.put("a", 5L, "va2");

        assertThat(store.get("a").sortKey()).isEqualTo(5L);
        assertThat(store.get("a").value()).isEqualTo("va2");
        assertThat(store.rangeQuery(30L, 30L)).isEmpty();
        assertThat(store.rangeQuery(5L, 5L))
                .extracting(Entry::primaryKey)
                .containsExactly("a");
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE))
                .filteredOn(e -> e.primaryKey().equals("a"))
                .hasSize(1);
        assertThat(store.stats().hashIndexSize()).isEqualTo(2);
        assertThat(store.stats().orderedIndexSize()).isEqualTo(2);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void removeDeletesFromBothIndexes() {
        store.put("a", 10L, "va");
        store.put("b", 20L, "vb");

        assertThat(store.remove("a")).isTrue();
        assertThat(store.remove("a")).isFalse();

        assertThat(store.get("a")).isNull();
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE))
                .extracting(Entry::primaryKey)
                .containsExactly("b");
        assertThat(store.stats().hashIndexSize()).isEqualTo(1);
        assertThat(store.stats().orderedIndexSize()).isEqualTo(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void updateSortKeyMovesEntryToNewPosition() {
        store.put("a", 10L, "va");
        store.put("b", 20L, "vb");

        Entry<String, Long, String> updated = store.updateSortKey("a", 50L);

        assertThat(updated.sortKey()).isEqualTo(50L);
        assertThat(updated.value()).isEqualTo("va");
        assertThat(store.get("a")).isSameAs(updated);
        assertThat(store.rangeQuery(10L, 10L)).isEmpty();
        assertThat(store.rangeQuery(50L, 50L)).containsExactly(updated);
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE))
                .filteredOn(e -> e.primaryKey().equals("a"))
                .hasSize(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void updateSortKeyWithSameKeyIsANoOp() {
        store.put("a", 10L, "va");
        Entry<String, Long, String> before = store.get("a");

        Entry<String, Long, String> result = store.updateSortKey("a", 10L);

        assertThat(result).isSameAs(before);
        assertThat(store.stats().updateCount()).isEqualTo(1);
    }

    @Test
    void updateSortKeyRejectsUnknownKey() {
        assertThatThrownBy(() -> store.updateSortKey("missing", 1L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void statsTrackSizesUpdatesInconsistenciesAndRepairs() {
        store.put("a", 1L, "va");
        store.put("b", 2L, "vb");
        store.put("c", 3L, "vc");
        store.put("a", 1L, "va2");
        store.remove("b");
        store.remove("absent");
        store.updateSortKey("c", 30L);
        store.updateSortKey("c", 30L);

        IndexStats stats = store.stats();
        assertThat(stats.hashIndexSize()).isEqualTo(2);
        assertThat(stats.orderedIndexSize()).isEqualTo(2);
        assertThat(stats.updateCount()).isEqualTo(6);
        assertThat(stats.inconsistencyCount()).isZero();
        assertThat(stats.repairedCount()).isZero();

        orderedIndex.remove(1L, "a");

        assertThat(store.checkConsistency().isConsistent()).isFalse();
        assertThat(store.stats().inconsistencyCount()).isEqualTo(1);

        RepairResult<String, Long, String> repair = store.repair();
        assertThat(repair.repairedCount()).isEqualTo(1);
        assertThat(store.stats().inconsistencyCount()).isEqualTo(2);
        assertThat(store.stats().repairedCount()).isEqualTo(1);

        assertThat(store.repair().repairedCount()).isZero();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
        assertThat(store.stats().inconsistencyCount()).isEqualTo(2);
        assertThat(store.stats().repairedCount()).isEqualTo(1);
    }

    @Test
    void healthyStoreReportsConsistentWithDescriptiveMessage() {
        store.put("a", 1L, "va");
        ConsistencyReport<String, Long, String> report = store.checkConsistency();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.differences()).isEmpty();
        assertThat(report.describe()).contains("consistent");
    }

    @Test
    void rangeQueryReturnsSnapshotList() {
        store.put("a", 1L, "va");
        List<Entry<String, Long, String>> snapshot = store.rangeQuery(0L, 10L);
        store.put("b", 2L, "vb");
        assertThat(snapshot).hasSize(1);
        assertThat(store.rangeQuery(0L, 10L)).hasSize(2);
    }
}
