package com.example.gsb.dualindex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 任一侧索引失败时整体回滚，不留只更新一侧的中间状态。 */
class RollbackTest {

    private DualIndex<String, String> index;

    @BeforeEach
    void setUp() {
        index = new DualIndex<>();
        index.put("k1", 10L, "v1");
        index.put("k2", 20L, "v2");
    }

    @AfterEach
    void tearDown() {
        index.faultControl().reset();
    }

    private void assertUntouched() {
        assertThat(index.hashIndexSize()).isEqualTo(2);
        assertThat(index.orderedIndexSize()).isEqualTo(2);
        assertThat(index.get("k1").value()).isEqualTo("v1");
        assertThat(index.get("k1").sortKey()).isEqualTo(10L);
        assertThat(index.get("k2").value()).isEqualTo("v2");
        assertThat(index.range(0L, 100L))
                .extracting(Entry::primaryKey)
                .containsExactly("k1", "k2");
        assertThat(index.checkConsistency().isConsistent()).isTrue();
        assertThat(index.stats().putCount()).isEqualTo(2);
    }

    @Test
    void putNewFailsOnHashSideLeavesBothIndexesUntouched() {
        index.faultControl().failOnHashPut(true);

        assertThatThrownBy(() -> index.put("k3", 30L, "v3"))
                .isInstanceOf(IndexUpdateException.class);

        assertUntouched();
    }

    @Test
    void putNewFailsOnOrderedSideRollsBackHashIndex() {
        index.faultControl().failOnOrderedPut(true);

        assertThatThrownBy(() -> index.put("k3", 30L, "v3"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.get("k3")).isNull();
        assertUntouched();
    }

    @Test
    void replaceFailsOnOrderedRemoveLeavesBothIndexesUntouched() {
        index.faultControl().failOnOrderedRemove(true);

        assertThatThrownBy(() -> index.put("k1", 99L, "new"))
                .isInstanceOf(IndexUpdateException.class);

        assertUntouched();
    }

    @Test
    void replaceFailsOnHashPutRestoresOrderedIndex() {
        index.faultControl().failOnHashPut(true);

        assertThatThrownBy(() -> index.put("k1", 99L, "new"))
                .isInstanceOf(IndexUpdateException.class);

        assertUntouched();
    }

    @Test
    void replaceFailsOnOrderedReinsertRestoresBothIndexes() {
        index.faultControl().failOnOrderedPut(true);

        assertThatThrownBy(() -> index.put("k1", 99L, "new"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.range(90L, 100L)).isEmpty();
        assertUntouched();
    }

    @Test
    void deleteFailsOnHashSideLeavesBothIndexesUntouched() {
        index.faultControl().failOnHashRemove(true);

        assertThatThrownBy(() -> index.delete("k1"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.stats().deleteCount()).isZero();
        assertUntouched();
    }

    @Test
    void deleteFailsOnOrderedSideRollsBackHashIndex() {
        index.faultControl().failOnOrderedRemove(true);

        assertThatThrownBy(() -> index.delete("k1"))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.get("k1")).isNotNull();
        assertThat(index.stats().deleteCount()).isZero();
        assertUntouched();
    }

    @Test
    void updateSortKeyFailsOnRemoveLeavesBothIndexesUntouched() {
        index.faultControl().failOnOrderedRemove(true);

        assertThatThrownBy(() -> index.updateSortKey("k1", 99L))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.stats().sortKeyUpdateCount()).isZero();
        assertUntouched();
    }

    @Test
    void updateSortKeyFailsOnReinsertRestoresOldPosition() {
        index.faultControl().failOnOrderedPut(true);

        assertThatThrownBy(() -> index.updateSortKey("k1", 99L))
                .isInstanceOf(IndexUpdateException.class);

        assertThat(index.get("k1").sortKey()).isEqualTo(10L);
        assertThat(index.range(90L, 100L)).isEmpty();
        assertThat(index.stats().sortKeyUpdateCount()).isZero();
        assertUntouched();
    }

    @Test
    void failedWriteCanBeRetriedAfterFaultCleared() {
        index.faultControl().failOnOrderedPut(true);
        assertThatThrownBy(() -> index.put("k3", 30L, "v3"))
                .isInstanceOf(IndexUpdateException.class);

        index.faultControl().reset();
        index.put("k3", 30L, "v3");

        assertThat(index.get("k3")).isNotNull();
        assertThat(index.hashIndexSize()).isEqualTo(3);
        assertThat(index.orderedIndexSize()).isEqualTo(3);
        assertThat(index.checkConsistency().isConsistent()).isTrue();
        assertThat(index.stats().putCount()).isEqualTo(3);
    }
}
