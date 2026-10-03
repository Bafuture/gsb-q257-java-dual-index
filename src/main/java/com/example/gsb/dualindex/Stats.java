package com.example.gsb.dualindex;

/**
 * 双索引组件的统计快照。
 *
 * @param hashIndexSize         哈希索引当前条目数
 * @param orderedIndexSize      有序索引当前条目数
 * @param putCount              成功提交的写入次数（含覆盖更新）
 * @param deleteCount           成功提交的删除次数
 * @param sortKeyUpdateCount    成功提交的排序键更新次数
 * @param inconsistenciesFound  一致性检查累计发现的不一致条目数
 * @param checkCount            一致性检查执行次数
 * @param entriesRepaired       修复累计处理的条目数
 */
public record Stats(
        long hashIndexSize,
        long orderedIndexSize,
        long putCount,
        long deleteCount,
        long sortKeyUpdateCount,
        long inconsistenciesFound,
        long checkCount,
        long entriesRepaired) {

    /** 成功提交的更新总次数（写入 + 删除 + 排序键更新）。 */
    public long updateCount() {
        return putCount + deleteCount + sortKeyUpdateCount;
    }
}
