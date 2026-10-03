package com.example.gsb.dualindex;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of a full consistency scan over both indexes. Carries the complete
 * list of differences so callers can inspect or log the exact divergence
 * instead of only learning that "something" is inconsistent.
 */
public final class ConsistencyReport<K, S, V> {

    private final List<IndexDifference<K, S, V>> differences;

    public ConsistencyReport(List<IndexDifference<K, S, V>> differences) {
        this.differences = List.copyOf(differences);
    }

    public boolean isConsistent() {
        return differences.isEmpty();
    }

    public List<IndexDifference<K, S, V>> differences() {
        return differences;
    }

    public List<IndexDifference<K, S, V>> ofType(IndexDifference.Type type) {
        return differences.stream().filter(d -> d.type() == type).toList();
    }

    /** Human-readable detail of every difference, one per line. */
    public String describe() {
        if (isConsistent()) {
            return "consistent: hash index and ordered index hold identical entry sets";
        }
        return differences.size() + " difference(s) detected:"
                + differences.stream()
                        .map(d -> "\n  - [" + d.type() + "] " + d.describe())
                        .collect(Collectors.joining());
    }

    @Override
    public String toString() {
        return describe();
    }
}
