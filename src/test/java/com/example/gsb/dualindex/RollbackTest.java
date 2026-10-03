package com.example.gsb.dualindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RollbackTest {

    private FailingHashIndex<String, Entry<String, Long, String>> hashIndex;
    private FailingOrderedIndex<String, Long, Entry<String, Long, String>> orderedIndex;
    private DualIndexStore<String, Long, String> store;

    @BeforeEach
    void setUp() {
        hashIndex = new FailingHashIndex<>();
        orderedIndex = new FailingOrderedIndex<>();
        store = new DualIndexStore<>(hashIndex, orderedIndex);
    }

    @Test
    void putRollsBackHashWhenOrderedPutFails() {
        orderedIndex.failNext(FailingOrderedIndex.Op.PUT);

        assertThatThrownBy(() -> store.put("a", 1L, "va"))
                .isInstanceOf(IndexUpdateException.class)
                .hasCauseInstanceOf(InjectedFailureException.class);

        assertThat(store.get("a")).isNull();
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty();
        assertThat(store.stats().updateCount()).isZero();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void putRollsBackToPreviousEntryWhenReinsertFails() {
        store.put("a", 1L, "old");
        orderedIndex.failNext(FailingOrderedIndex.Op.PUT);

        assertThatThrownBy(() -> store.put("a", 5L, "new"))
                .isInstanceOf(IndexUpdateException.class);

        Entry<String, Long, String> current = store.get("a");
        assertThat(current.sortKey()).isEqualTo(1L);
        assertThat(current.value()).isEqualTo("old");
        assertThat(store.rangeQuery(1L, 1L)).containsExactly(current);
        assertThat(store.rangeQuery(5L, 5L)).isEmpty();
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE))
                .filteredOn(e -> e.primaryKey().equals("a"))
                .hasSize(1);
        assertThat(store.stats().updateCount()).isEqualTo(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void putRollsBackWhenOldOrderedSlotCannotBeRemoved() {
        store.put("a", 1L, "old");
        orderedIndex.failNext(FailingOrderedIndex.Op.REMOVE);

        assertThatThrownBy(() -> store.put("a", 5L, "new"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(store.get("a").value()).isEqualTo("old");
        assertThat(store.rangeQuery(1L, 1L)).hasSize(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void removeRollsBackWhenOrderedRemoveFails() {
        store.put("a", 1L, "va");
        orderedIndex.failNext(FailingOrderedIndex.Op.REMOVE);

        assertThatThrownBy(() -> store.remove("a"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(store.get("a").value()).isEqualTo("va");
        assertThat(store.rangeQuery(1L, 1L)).hasSize(1);
        assertThat(store.stats().orderedIndexSize()).isEqualTo(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void updateSortKeyRollsBackWhenOrderedPutFails() {
        store.put("a", 1L, "va");
        orderedIndex.failNext(FailingOrderedIndex.Op.PUT);

        assertThatThrownBy(() -> store.updateSortKey("a", 9L))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(store.get("a").sortKey()).isEqualTo(1L);
        assertThat(store.rangeQuery(1L, 1L)).hasSize(1);
        assertThat(store.rangeQuery(9L, 9L)).isEmpty();
        assertThat(store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE))
                .filteredOn(e -> e.primaryKey().equals("a"))
                .hasSize(1);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void hashPutFailureLeavesOrderedIndexUntouched() {
        hashIndex.failNext(FailingHashIndex.Op.PUT);

        assertThatThrownBy(() -> store.put("a", 1L, "va"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(orderedIndex.size()).isZero();
        assertThat(store.stats().hashIndexSize()).isZero();
        assertThat(store.stats().orderedIndexSize()).isZero();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void updateSortKeyRollsBackOrderedWhenHashPutFails() {
        store.put("a", 1L, "va");
        hashIndex.failNext(FailingHashIndex.Op.PUT);

        assertThatThrownBy(() -> store.updateSortKey("a", 9L))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(store.get("a").sortKey()).isEqualTo(1L);
        assertThat(store.rangeQuery(1L, 1L)).hasSize(1);
        assertThat(store.rangeQuery(9L, 9L)).isEmpty();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void storeKeepsWorkingAfterARolledBackFailure() {
        orderedIndex.failNext(FailingOrderedIndex.Op.PUT);
        assertThatThrownBy(() -> store.put("a", 1L, "va")).isInstanceOf(IndexUpdateException.class);

        store.put("a", 2L, "va2");
        store.put("b", 3L, "vb");

        assertThat(store.get("a").sortKey()).isEqualTo(2L);
        assertThat(store.stats().updateCount()).isEqualTo(2);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }
}
