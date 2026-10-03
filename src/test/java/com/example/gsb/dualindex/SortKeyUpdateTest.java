package com.example.gsb.dualindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 排序键更新：先移除旧位置再插入新位置，查询不会看到重复条目。 */
class SortKeyUpdateTest {

    private DualIndex<String, String> index;

    @BeforeEach
    void setUp() {
        index = new DualIndex<>();
        index.put("k1", 10L, "v1");
        index.put("k2", 20L, "v2");
        index.put("k3", 30L, "v3");
    }

    @Test
    void updateMovesEntryToNewPositionWithoutDuplicates() {
        assertThat(index.updateSortKey("k1", 25L)).isTrue();

        assertThat(index.get("k1").sortKey()).isEqualTo(25L);
        assertThat(index.range(0L, 15L)).isEmpty();
        assertThat(index.range(20L, 30L))
                .extracting(Entry::primaryKey)
                .containsExactly("k2", "k1", "k3");
        assertThat(index.range(Long.MIN_VALUE, Long.MAX_VALUE))
                .extracting(Entry::primaryKey)
                .containsExactly("k2", "k1", "k3");
        assertThat(index.orderedIndexSize()).isEqualTo(3);
        assertThat(index.hashIndexSize()).isEqualTo(3);
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void updateToOccupiedSortKeySharesBucket() {
        assertThat(index.updateSortKey("k1", 20L)).isTrue();

        assertThat(index.range(20L, 20L))
                .extracting(Entry::primaryKey)
                .containsExactly("k2", "k1");
        assertThat(index.orderedIndexSize()).isEqualTo(3);
    }

    @Test
    void updateUnknownKeyReturnsFalse() {
        assertThat(index.updateSortKey("missing", 99L)).isFalse();
        assertThat(index.stats().sortKeyUpdateCount()).isZero();
    }

    @Test
    void updateToSameSortKeyIsNoOp() {
        assertThat(index.updateSortKey("k1", 10L)).isTrue();
        assertThat(index.stats().sortKeyUpdateCount()).isZero();
        assertThat(index.range(0L, 100L)).hasSize(3);
    }

    @Test
    void repeatedUpdatesKeepSingleEntryPerKey() {
        for (long sortKey = 100L; sortKey < 120L; sortKey++) {
            index.updateSortKey("k1", sortKey);
            assertThat(index.range(Long.MIN_VALUE, Long.MAX_VALUE))
                    .extracting(Entry::primaryKey)
                    .containsOnlyOnce("k1");
        }
        assertThat(index.get("k1").sortKey()).isEqualTo(119L);
        assertThat(index.orderedIndexSize()).isEqualTo(3);
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }
}
