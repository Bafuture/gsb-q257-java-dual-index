package com.example.gsb.dualindex;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 双索引同步更新与查询。 */
class DualIndexSyncTest {

    private DualIndex<String, String> index;

    @BeforeEach
    void setUp() {
        index = new DualIndex<>();
    }

    @Test
    void putUpdatesBothIndexesAndSameEntryInstanceIsReferenced() {
        index.put("k1", 10L, "v1");

        Entry<String, String> byKey = index.get("k1");
        assertThat(byKey).isNotNull();
        assertThat(byKey.sortKey()).isEqualTo(10L);
        assertThat(byKey.value()).isEqualTo("v1");

        List<Entry<String, String>> hits = index.range(0L, 100L);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).isSameAs(byKey);
    }

    @Test
    void putMultipleKeysSharesSortKeyBuckets() {
        index.put("k1", 10L, "v1");
        index.put("k2", 10L, "v2");
        index.put("k3", 20L, "v3");

        assertThat(index.hashIndexSize()).isEqualTo(3);
        assertThat(index.orderedIndexSize()).isEqualTo(3);

        assertThat(index.range(10L, 10L))
                .extracting(Entry::primaryKey)
                .containsExactly("k1", "k2");

        assertThat(index.range(0L, 15L)).extracting(Entry::primaryKey).containsExactly("k1", "k2");
        assertThat(index.range(11L, 100L)).extracting(Entry::primaryKey).containsExactly("k3");
        assertThat(index.range(0L, 100L)).extracting(Entry::primaryKey)
                .containsExactly("k1", "k2", "k3");
    }

    @Test
    void replaceMovesEntryWithinOrderedIndex() {
        index.put("k1", 10L, "v1");
        index.put("k1", 30L, "v2");

        assertThat(index.hashIndexSize()).isEqualTo(1);
        assertThat(index.orderedIndexSize()).isEqualTo(1);
        assertThat(index.get("k1").value()).isEqualTo("v2");
        assertThat(index.get("k1").sortKey()).isEqualTo(30L);
        assertThat(index.range(0L, 20L)).isEmpty();
        assertThat(index.range(20L, 100L)).extracting(Entry::primaryKey).containsExactly("k1");
    }

    @Test
    void deleteRemovesFromBothIndexes() {
        index.put("k1", 10L, "v1");
        index.put("k2", 20L, "v2");

        assertThat(index.delete("k1")).isTrue();

        assertThat(index.get("k1")).isNull();
        assertThat(index.hashIndexSize()).isEqualTo(1);
        assertThat(index.orderedIndexSize()).isEqualTo(1);
        assertThat(index.range(0L, 100L)).extracting(Entry::primaryKey).containsExactly("k2");
    }

    @Test
    void deleteUnknownKeyIsNoOp() {
        index.put("k1", 10L, "v1");
        assertThat(index.delete("missing")).isFalse();
        assertThat(index.stats().deleteCount()).isZero();
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void freshIndexIsConsistentAndEmpty() {
        assertThat(index.hashIndexSize()).isZero();
        assertThat(index.orderedIndexSize()).isZero();
        assertThat(index.range(Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty();
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }
}
