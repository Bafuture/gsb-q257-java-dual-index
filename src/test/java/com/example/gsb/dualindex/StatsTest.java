package com.example.gsb.dualindex;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 统计：两索引条目数、更新次数、不一致次数与修复条数。 */
class StatsTest {

    @Test
    void countsWritesDeletesAndSortKeyUpdates() {
        DualIndex<String, String> index = new DualIndex<>();
        index.put("k1", 1L, "v1");
        index.put("k2", 2L, "v2");
        index.put("k3", 3L, "v3");
        index.put("k1", 10L, "v1b");
        index.delete("k2");
        index.updateSortKey("k3", 30L);

        Stats stats = index.stats();
        assertThat(stats.hashIndexSize()).isEqualTo(2);
        assertThat(stats.orderedIndexSize()).isEqualTo(2);
        assertThat(stats.putCount()).isEqualTo(4);
        assertThat(stats.deleteCount()).isEqualTo(1);
        assertThat(stats.sortKeyUpdateCount()).isEqualTo(1);
        assertThat(stats.updateCount()).isEqualTo(6);
    }

    @Test
    void failedOperationsDoNotCountAsUpdates() {
        DualIndex<String, String> index = new DualIndex<>();
        index.put("k1", 1L, "v1");

        index.faultControl().failOnOrderedPut(true);
        assertThatThrownBy(() -> index.put("k2", 2L, "v2"))
                .isInstanceOf(IndexUpdateException.class);
        index.faultControl().reset();

        index.faultControl().failOnOrderedRemove(true);
        assertThatThrownBy(() -> index.delete("k1"))
                .isInstanceOf(IndexUpdateException.class);
        index.faultControl().reset();

        Stats stats = index.stats();
        assertThat(stats.putCount()).isEqualTo(1);
        assertThat(stats.deleteCount()).isZero();
        assertThat(stats.updateCount()).isEqualTo(1);
    }

    @Test
    void countsInconsistenciesAndRepairs() {
        DualIndex<String, String> index = new DualIndex<>();
        index.put("a", 1L, "va");
        index.put("b", 2L, "vb");

        Corruption.removeFromOrderedOnly(index, "a", 1L);
        Corruption.removeFromHashOnly(index, "b");

        index.checkConsistency();
        index.checkConsistency();
        RepairReport repair = index.repair(DualIndex.AuthoritativeSide.HASH);

        Stats stats = index.stats();
        assertThat(stats.checkCount()).isEqualTo(2);
        assertThat(stats.inconsistenciesFound()).isEqualTo(4);
        assertThat(stats.entriesRepaired()).isEqualTo(2);
        assertThat(repair.repairedCount()).isEqualTo(2);
        assertThat(repair.consistentAfterRepair()).isTrue();
    }

    @Test
    void consistentChecksDoNotInflateInconsistencyCount() {
        DualIndex<String, String> index = new DualIndex<>();
        index.put("a", 1L, "va");

        index.checkConsistency();
        index.checkConsistency();

        Stats stats = index.stats();
        assertThat(stats.checkCount()).isEqualTo(2);
        assertThat(stats.inconsistenciesFound()).isZero();
        assertThat(stats.entriesRepaired()).isZero();
    }

    @Test
    void statsSnapshotReflectsLiveIndexSizes() {
        DualIndex<String, String> index = new DualIndex<>();
        for (int i = 0; i < 10; i++) {
            index.put("k" + i, i, "v" + i);
        }
        assertThat(index.stats().hashIndexSize()).isEqualTo(10);
        assertThat(index.stats().orderedIndexSize()).isEqualTo(10);

        index.delete("k0");
        index.delete("k1");
        assertThat(index.stats().hashIndexSize()).isEqualTo(8);
        assertThat(index.stats().orderedIndexSize()).isEqualTo(8);
    }
}
