package com.example.gsb.dualindex;

/**
 * A single divergence between the hash index and the ordered index.
 * Fields that do not apply to a given {@link Type} are {@code null}.
 *
 * @param type            kind of divergence
 * @param primaryKey      primary key of the diverging entry
 * @param expectedSortKey sort key the ordered index should hold (from the hash side)
 * @param actualSortKey   sort-key position actually found in the ordered index
 * @param hashEntry       entry as seen by the hash index
 * @param orderedEntry    entry as seen by the ordered index
 */
public record IndexDifference<K, S, V>(
        Type type,
        K primaryKey,
        S expectedSortKey,
        S actualSortKey,
        Entry<K, S, V> hashEntry,
        Entry<K, S, V> orderedEntry) {

    public enum Type {
        /** Present in the hash index but has no slot at its sort key in the ordered index. */
        MISSING_IN_ORDERED,
        /** Present in the ordered index but unknown to the hash index. */
        MISSING_IN_HASH,
        /** Ordered index still holds the key at an outdated sort-key position. */
        STALE_ORDERED_SLOT,
        /** Both indexes hold the key at the same position but with different entry data. */
        ENTRY_MISMATCH
    }

    public String describe() {
        return switch (type) {
            case MISSING_IN_ORDERED -> String.format(
                    "key=%s missing in ordered index (expected sortKey=%s, hashEntry=%s)",
                    primaryKey, expectedSortKey, hashEntry);
            case MISSING_IN_HASH -> String.format(
                    "key=%s missing in hash index (orphan ordered slot at sortKey=%s, orderedEntry=%s)",
                    primaryKey, actualSortKey, orderedEntry);
            case STALE_ORDERED_SLOT -> String.format(
                    "key=%s stale ordered slot at sortKey=%s (expected sortKey=%s)",
                    primaryKey, actualSortKey, expectedSortKey);
            case ENTRY_MISMATCH -> String.format(
                    "key=%s entry mismatch at sortKey=%s (hashEntry=%s, orderedEntry=%s)",
                    primaryKey, expectedSortKey, hashEntry, orderedEntry);
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
