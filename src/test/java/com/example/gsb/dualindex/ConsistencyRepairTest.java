package com.example.gsb.dualindex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsistencyRepairTest {

    private MapHashIndex<String, Entry<String, Long, String>> hashIndex;
    private TreeMapOrderedIndex<String, Long, Entry<String, Long, String>> orderedIndex;
    private DualIndexStore<String, Long, String> store;

    @BeforeEach
    void setUp() {
        hashIndex = new MapHashIndex<>();
        orderedIndex = new TreeMapOrderedIndex<>();
        store = new DualIndexStore<>(hashIndex, orderedIndex);
        store.put("a", 1L, "va");
        store.put("b", 2L, "vb");
        store.put("c", 3L, "vc");
    }

    @Test
    void detectsAndRepairsEntryMissingInOrderedIndex() {
        orderedIndex.remove(2L, "b");

        ConsistencyReport<String, Long, String> report = store.checkConsistency();

        assertThat(report.isConsistent()).isFalse();
        assertThat(report.differences()).hasSize(1);
        IndexDifference<String, Long, String> diff = report.differences().get(0);
        assertThat(diff.type()).isEqualTo(IndexDifference.Type.MISSING_IN_ORDERED);
        assertThat(diff.primaryKey()).isEqualTo("b");
        assertThat(diff.expectedSortKey()).isEqualTo(2L);
        assertThat(report.describe())
                .contains("MISSING_IN_ORDERED")
                .contains("b")
                .contains("sortKey=2");

        RepairResult<String, Long, String> repair = store.repair();

        assertThat(repair.repairedCount()).isEqualTo(1);
        assertThat(repair.actions()).hasSize(1);
        assertThat(repair.actions().get(0)).contains("ordered").contains("b");
        assertThat(store.checkConsistency().isConsistent()).isTrue();
        assertThat(store.rangeQuery(2L, 2L)).extracting(Entry::primaryKey).containsExactly("b");
    }

    @Test
    void detectsAndRepairsOrphanSlotMissingInHashIndex() {
        orderedIndex.put(9L, "ghost", new Entry<>("ghost", 9L, "vg"));

        ConsistencyReport<String, Long, String> report = store.checkConsistency();

        assertThat(report.isConsistent()).isFalse();
        assertThat(report.ofType(IndexDifference.Type.MISSING_IN_HASH)).hasSize(1);
        assertThat(report.describe()).contains("MISSING_IN_HASH").contains("ghost");

        RepairResult<String, Long, String> repair = store.repair();

        assertThat(repair.repairedCount()).isEqualTo(1);
        assertThat(store.get("ghost").value()).isEqualTo("vg");
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void detectsAndRepairsStaleOrderedSlot() {
        Entry<String, Long, String> entry = store.get("a");
        orderedIndex.remove(1L, "a");
        orderedIndex.put(7L, "a", entry);

        ConsistencyReport<String, Long, String> report = store.checkConsistency();

        assertThat(report.ofType(IndexDifference.Type.STALE_ORDERED_SLOT)).hasSize(1);
        assertThat(report.ofType(IndexDifference.Type.MISSING_IN_ORDERED)).hasSize(1);
        IndexDifference<String, Long, String> stale = report.ofType(IndexDifference.Type.STALE_ORDERED_SLOT).get(0);
        assertThat(stale.expectedSortKey()).isEqualTo(1L);
        assertThat(stale.actualSortKey()).isEqualTo(7L);
        assertThat(report.describe()).contains("STALE_ORDERED_SLOT").contains("sortKey=7");

        RepairResult<String, Long, String> repair = store.repair();

        assertThat(repair.repairedCount()).isEqualTo(2);
        assertThat(store.rangeQuery(7L, 7L)).isEmpty();
        assertThat(store.rangeQuery(1L, 1L)).extracting(Entry::primaryKey).containsExactly("a");
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void detectsAndRepairsEntryMismatchAtSamePosition() {
        orderedIndex.remove(1L, "a");
        orderedIndex.put(1L, "a", new Entry<>("a", 1L, "corrupted"));

        ConsistencyReport<String, Long, String> report = store.checkConsistency();

        assertThat(report.ofType(IndexDifference.Type.ENTRY_MISMATCH)).hasSize(1);
        assertThat(report.describe()).contains("ENTRY_MISMATCH").contains("corrupted");

        RepairResult<String, Long, String> repair = store.repair();

        assertThat(repair.repairedCount()).isEqualTo(1);
        Entry<String, Long, String> repaired = store.rangeQuery(1L, 1L).stream()
                .filter(e -> e.primaryKey().equals("a"))
                .findFirst()
                .orElseThrow();
        assertThat(repaired.value()).isEqualTo("va");
        assertThat(repaired).isSameAs(store.get("a"));
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void repairIsIdempotentAndAccumulatesStatistics() {
        orderedIndex.remove(1L, "a");
        orderedIndex.remove(2L, "b");

        RepairResult<String, Long, String> first = store.repair();
        RepairResult<String, Long, String> second = store.repair();

        assertThat(first.repairedCount()).isEqualTo(2);
        assertThat(second.repairedCount()).isZero();
        assertThat(second.actions()).isEmpty();
        assertThat(second.report().isConsistent()).isTrue();

        IndexStats stats = store.stats();
        assertThat(stats.repairedCount()).isEqualTo(2);
        assertThat(stats.inconsistencyCount()).isEqualTo(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void repairOnHealthyStoreIsANoOp() {
        RepairResult<String, Long, String> result = store.repair();

        assertThat(result.repairedAnything()).isFalse();
        assertThat(result.repairedCount()).isZero();
        assertThat(result.report().isConsistent()).isTrue();
        assertThat(store.stats().inconsistencyCount()).isZero();
        assertThat(store.stats().repairedCount()).isZero();
    }

    @Test
    void repairsBothDirectionsInOneRun() {
        orderedIndex.remove(1L, "a");
        orderedIndex.put(9L, "ghost", new Entry<>("ghost", 9L, "vg"));

        RepairResult<String, Long, String> repair = store.repair();

        assertThat(repair.repairedCount()).isEqualTo(2);
        assertThat(store.rangeQuery(1L, 1L)).extracting(Entry::primaryKey).containsExactly("a");
        assertThat(store.get("ghost")).isNotNull();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }
}
