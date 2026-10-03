package com.example.gsb.dualindex;

import java.util.List;

/**
 * Outcome of a repair run.
 *
 * @param repairedCount number of index entries that were inserted, removed or realigned
 * @param actions       human-readable description of every repair action, in execution order
 * @param report        the differences that this run was based on (empty when nothing needed repair)
 */
public record RepairResult<K, S, V>(int repairedCount, List<String> actions, ConsistencyReport<K, S, V> report) {

    public RepairResult {
        actions = List.copyOf(actions);
    }

    public boolean repairedAnything() {
        return repairedCount > 0;
    }
}
