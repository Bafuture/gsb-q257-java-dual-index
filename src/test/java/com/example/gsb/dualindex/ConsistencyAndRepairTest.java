package com.example.gsb.dualindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 一致性检查输出差异明细，修复幂等且按权威侧对齐。 */
class ConsistencyAndRepairTest {

    private DualIndex<String, String> index;

    @BeforeEach
    void setUp() {
        index = new DualIndex<>();
        index.put("a", 1L, "va");
        index.put("b", 2L, "vb");
        index.put("c", 3L, "vc");
    }

    @Test
    void consistentIndexReportsNoDiffs() {
        ConsistencyReport report = index.checkConsistency();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.diffs()).isEmpty();
        assertThat(report.hashIndexSize()).isEqualTo(3);
        assertThat(report.orderedIndexSize()).isEqualTo(3);
    }

    @Test
    void checkReportsOnlyInHashWithDetail() {
        Corruption.removeFromOrderedOnly(index, "b", 2L);

        ConsistencyReport report = index.checkConsistency();

        assertThat(report.isConsistent()).isFalse();
        assertThat(report.diffs()).hasSize(1);
        ConsistencyReport.Diff diff = report.diffs().get(0);
        assertThat(diff.type()).isEqualTo(ConsistencyReport.DiffType.ONLY_IN_HASH);
        assertThat(diff.primaryKey()).isEqualTo("b");
        assertThat(diff.hashEntry().value()).isEqualTo("vb");
        assertThat(diff.orderedEntry()).isNull();
        assertThat(report.describe()).contains("ONLY_IN_HASH").contains("b");
    }

    @Test
    void checkReportsOnlyInOrderedWithDetail() {
        Corruption.removeFromHashOnly(index, "c");

        ConsistencyReport report = index.checkConsistency();

        assertThat(report.diffs()).hasSize(1);
        ConsistencyReport.Diff diff = report.diffs().get(0);
        assertThat(diff.type()).isEqualTo(ConsistencyReport.DiffType.ONLY_IN_ORDERED);
        assertThat(diff.primaryKey()).isEqualTo("c");
        assertThat(diff.hashEntry()).isNull();
        assertThat(diff.orderedEntry().value()).isEqualTo("vc");
    }

    @Test
    void checkReportsValueMismatchWithBothSides() {
        index.delete("a");
        Corruption.makeMismatch(index, "a", 1L, "va", 99L, "stale");

        ConsistencyReport report = index.checkConsistency();

        assertThat(report.diffs()).hasSize(1);
        ConsistencyReport.Diff diff = report.diffs().get(0);
        assertThat(diff.type()).isEqualTo(ConsistencyReport.DiffType.VALUE_MISMATCH);
        assertThat(diff.primaryKey()).isEqualTo("a");
        assertThat(diff.hashEntry().sortKey()).isEqualTo(1L);
        assertThat(diff.orderedEntry().sortKey()).isEqualTo(99L);
        assertThat(report.describe()).contains("VALUE_MISMATCH").contains("stale");
    }

    @Test
    void checkReportsMultipleDiffsAtOnce() {
        Corruption.removeFromOrderedOnly(index, "a", 1L);
        Corruption.removeFromHashOnly(index, "b");
        index.delete("c");
        Corruption.makeMismatch(index, "c", 3L, "vc", 33L, "stale");

        ConsistencyReport report = index.checkConsistency();

        assertThat(report.diffs()).hasSize(3);
        assertThat(report.describe())
                .contains("ONLY_IN_HASH", "ONLY_IN_ORDERED", "VALUE_MISMATCH");
    }

    @Test
    void repairWithHashAuthoritativeFixesOrderedSide() {
        Corruption.removeFromOrderedOnly(index, "a", 1L);
        Corruption.addOnlyInOrdered(index, "ghost", 50L, "vg");
        index.delete("c");
        Corruption.makeMismatch(index, "c", 3L, "vc", 33L, "stale");

        RepairReport repair = index.repair(DualIndex.AuthoritativeSide.HASH);

        assertThat(repair.repairedCount()).isEqualTo(3);
        assertThat(repair.consistentAfterRepair()).isTrue();
        assertThat(index.checkConsistency().isConsistent()).isTrue();

        assertThat(index.range(0L, 10L)).extracting(Entry::primaryKey)
                .containsExactly("a", "b", "c");
        assertThat(index.get("ghost")).isNull();
        assertThat(index.range(50L, 50L)).isEmpty();
        assertThat(index.range(33L, 33L)).isEmpty();
        assertThat(index.get("c").sortKey()).isEqualTo(3L);
    }

    @Test
    void repairWithOrderedAuthoritativeFixesHashSide() {
        Corruption.addOnlyInHash(index, "ghost", 7L, "vg");
        Corruption.removeFromHashOnly(index, "b");
        index.delete("c");
        Corruption.makeMismatch(index, "c", 3L, "vc", 33L, "stale");

        RepairReport repair = index.repair(DualIndex.AuthoritativeSide.ORDERED);

        assertThat(repair.repairedCount()).isEqualTo(3);
        assertThat(repair.consistentAfterRepair()).isTrue();
        assertThat(index.checkConsistency().isConsistent()).isTrue();

        assertThat(index.get("ghost")).isNull();
        assertThat(index.get("b")).isNotNull();
        assertThat(index.get("c").sortKey()).isEqualTo(33L);
        assertThat(index.get("c").value()).isEqualTo("stale");
    }

    @Test
    void repairIsIdempotent() {
        Corruption.removeFromOrderedOnly(index, "a", 1L);

        RepairReport first = index.repair(DualIndex.AuthoritativeSide.HASH);
        RepairReport second = index.repair(DualIndex.AuthoritativeSide.HASH);

        assertThat(first.repairedCount()).isEqualTo(1);
        assertThat(second.repairedCount()).isZero();
        assertThat(second.consistentAfterRepair()).isTrue();
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }

    @Test
    void repairOnConsistentIndexDoesNothing() {
        RepairReport repair = index.repair(DualIndex.AuthoritativeSide.HASH);
        assertThat(repair.repairedCount()).isZero();
        assertThat(repair.consistentAfterRepair()).isTrue();
        assertThat(index.hashIndexSize()).isEqualTo(3);
        assertThat(index.orderedIndexSize()).isEqualTo(3);
    }

    @Test
    void checkAndRepairUpdateStatistics() {
        Corruption.removeFromOrderedOnly(index, "a", 1L);
        Corruption.removeFromHashOnly(index, "b");

        index.checkConsistency();
        index.checkConsistency();
        index.repair(DualIndex.AuthoritativeSide.HASH);

        Stats stats = index.stats();
        assertThat(stats.checkCount()).isEqualTo(2);
        assertThat(stats.inconsistenciesFound()).isEqualTo(4);
        assertThat(stats.entriesRepaired()).isEqualTo(2);
    }
}
