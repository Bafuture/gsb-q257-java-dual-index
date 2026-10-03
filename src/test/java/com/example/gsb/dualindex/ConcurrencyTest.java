package com.example.gsb.dualindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    @Test
    void concurrentReadsAndWritesNeverObserveHalfAppliedState() throws Exception {
        DualIndexStore<String, Long, String> store = new DualIndexStore<>();
        int keySpace = 64;
        for (int i = 0; i < keySpace; i++) {
            store.put("key-" + i, (long) i, "init");
        }

        int writerCount = 4;
        int readerCount = 4;
        int opsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writerCount; w++) {
            final int writerId = w;
            futures.add(pool.submit(() -> {
                await(start);
                java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
                try {
                    for (int op = 0; op < opsPerThread; op++) {
                        String key = "key-" + random.nextInt(keySpace);
                        int kind = random.nextInt(3);
                        if (kind == 0) {
                            store.put(key, random.nextLong(1_000L), "w" + writerId + "-" + op);
                        } else if (kind == 1) {
                            try {
                                store.updateSortKey(key, random.nextLong(1_000L));
                            } catch (NoSuchElementException expected) {
                                // another writer removed the key concurrently
                            }
                        } else {
                            store.remove(key);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            }));
        }

        for (int r = 0; r < readerCount; r++) {
            futures.add(pool.submit(() -> {
                await(start);
                try {
                    for (int op = 0; op < opsPerThread; op++) {
                        List<Entry<String, Long, String>> snapshot =
                                store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE);
                        Map<String, Integer> counts = new ConcurrentHashMap<>();
                        for (Entry<String, Long, String> entry : snapshot) {
                            counts.merge(entry.primaryKey(), 1, Integer::sum);
                        }
                        counts.forEach((key, count) -> {
                            if (count > 1) {
                                throw new AssertionError("key " + key + " appeared " + count
                                        + " times in a single range snapshot");
                            }
                        });
                        if ((op & 0x3F) == 0) {
                            ConsistencyReport<String, Long, String> report = store.checkConsistency();
                            if (!report.isConsistent()) {
                                throw new AssertionError(report.describe());
                            }
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(store.checkConsistency().isConsistent()).isTrue();
        assertThat(store.stats().hashIndexSize()).isEqualTo(store.stats().orderedIndexSize());
    }

    @Test
    void hotSortKeyUpdatesNeverExposeDuplicateOrMissingSlot() throws Exception {
        DualIndexStore<String, Long, String> store = new DualIndexStore<>();
        store.put("hot", 0L, "v0");

        int updateCount = 5_000;
        int readerCount = 3;
        AtomicInteger readerFailures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            await(start);
            try {
                for (int i = 1; i <= updateCount; i++) {
                    store.updateSortKey("hot", (long) i);
                }
            } finally {
                done.countDown();
            }
        }));

        for (int r = 0; r < readerCount; r++) {
            futures.add(pool.submit(() -> {
                await(start);
                do {
                    List<Entry<String, Long, String>> snapshot =
                            store.rangeQuery(Long.MIN_VALUE, Long.MAX_VALUE);
                    long hotSlots = snapshot.stream().filter(e -> e.primaryKey().equals("hot")).count();
                    if (hotSlots != 1) {
                        readerFailures.incrementAndGet();
                        return;
                    }
                    if (store.get("hot") == null) {
                        readerFailures.incrementAndGet();
                        return;
                    }
                } while (done.getCount() > 0);
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(readerFailures).hasValue(0);
        assertThat(store.get("hot").sortKey()).isEqualTo((long) updateCount);
        assertThat(store.stats().updateCount()).isEqualTo(updateCount + 1L);
        assertThat(store.checkConsistency().isConsistent()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
