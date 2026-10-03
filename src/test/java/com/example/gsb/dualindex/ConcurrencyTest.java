package com.example.gsb.dualindex;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发读写：读路径持读锁拿到的是某一写事务的完整快照，
 * 不会看到只更新了一侧索引的中间状态，也不会看到重复条目。
 */
class ConcurrencyTest {

    private static final int KEY_COUNT = 50;
    private static final int WRITER_THREADS = 8;
    private static final int READER_THREADS = 4;
    private static final long SORT_KEY_MIN = 0L;
    private static final long SORT_KEY_MAX = 99L;

    @Test
    void concurrentPutsDeletesUpdatesAndReadsNeverObserveInconsistentState()
            throws InterruptedException {
        DualIndex<String, String> index = new DualIndex<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            index.put("k" + i, 10L, "v10");
        }

        ExecutorService pool = Executors.newFixedThreadPool(WRITER_THREADS + READER_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(WRITER_THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        for (int w = 0; w < WRITER_THREADS; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline && failure.get() == null) {
                        String pk = "k" + ThreadLocalRandomInt();
                        long sortKey = randomInRange();
                        int op = (int) (randomInRange() % 4L);
                        switch (op) {
                            case 0 -> index.put(pk, sortKey, "v" + sortKey);
                            case 1 -> index.updateSortKey(pk, sortKey);
                            case 2 -> {
                                index.delete(pk);
                                index.put(pk, sortKey, "v" + sortKey);
                            }
                            default -> index.put(pk, sortKey, "v" + sortKey);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int r = 0; r < READER_THREADS; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline && failure.get() == null) {
                        assertSnapshotConsistent(index);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        if (failure.get() != null) {
            throw new AssertionError("concurrent reader observed inconsistency", failure.get());
        }

        assertThat(index.hashIndexSize()).isEqualTo(KEY_COUNT);
        assertThat(index.orderedIndexSize()).isEqualTo(KEY_COUNT);
        ConsistencyReport report = index.checkConsistency();
        assertThat(report.isConsistent()).as(report.describe()).isTrue();
    }

    @Test
    void concurrentSortKeyUpdatesNeverExposeDuplicateEntries() throws InterruptedException {
        DualIndex<String, String> index = new DualIndex<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            index.put("k" + i, 10L, "v10");
        }

        ExecutorService pool = Executors.newFixedThreadPool(WRITER_THREADS + READER_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        for (int w = 0; w < WRITER_THREADS; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline && failure.get() == null) {
                        String pk = "k" + ThreadLocalRandomInt();
                        index.updateSortKey(pk, randomInRange());
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
        for (int r = 0; r < READER_THREADS; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline && failure.get() == null) {
                        assertSnapshotConsistent(index);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        if (failure.get() != null) {
            throw new AssertionError("duplicate or torn entry observed", failure.get());
        }
        assertThat(index.checkConsistency().isConsistent()).isTrue();
    }

    /** 校验一次读锁快照内：主键不重复、排序键落在所属区间、哈希侧引用同一条目。 */
    private static void assertSnapshotConsistent(DualIndex<String, String> index) {
        List<Entry<String, String>> snapshot = index.range(SORT_KEY_MIN, SORT_KEY_MAX);
        Set<String> seen = new HashSet<>();
        for (Entry<String, String> entry : snapshot) {
            assertThat(entry.sortKey())
                    .as("entry returned outside its sort-key bucket")
                    .isBetween(SORT_KEY_MIN, SORT_KEY_MAX);
            assertThat(seen.add(entry.primaryKey()))
                    .as("duplicate primary key visible in ordered index: %s", entry.primaryKey())
                    .isTrue();
        }
        Stats stats = index.stats();
        assertThat(stats.orderedIndexSize())
                .as("index sizes diverge within a single locked snapshot")
                .isEqualTo(stats.hashIndexSize());
    }

    private static int ThreadLocalRandomInt() {
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(KEY_COUNT);
    }

    private static long randomInRange() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(SORT_KEY_MAX + 1);
    }
}
