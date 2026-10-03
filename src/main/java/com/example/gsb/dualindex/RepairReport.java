package com.example.gsb.dualindex;

/**
 * 一次修复操作的结果。
 *
 * @param repairedCount 本次修复处理的条目数（幂等：无差异时为 0）
 * @param consistentAfterRepair 修复后复检是否一致
 */
public record RepairReport(long repairedCount, boolean consistentAfterRepair) {
}
