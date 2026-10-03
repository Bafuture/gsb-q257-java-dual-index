package com.example.gsb.dualindex;

/**
 * Point-in-time statistics snapshot.
 *
 * @param hashIndexSize      current number of entries in the hash index
 * @param orderedIndexSize   current number of entries in the ordered index
 * @param updateCount        successful mutating operations (put / remove / updateSortKey)
 * @param inconsistencyCount scans (check or repair) that found at least one difference
 * @param repairedCount      total number of index entries fixed by repair runs
 */
public record IndexStats(
        long hashIndexSize,
        long orderedIndexSize,
        long updateCount,
        long inconsistencyCount,
        long repairedCount) {
}
