package com.example.gsb.dualindex;

/**
 * 故障注入开关，用于测试回滚逻辑。生产环境保持默认（全部关闭）。
 *
 * <p>每个开关对应一类底层索引操作：置为 {@code true} 后，
 * 下一次对应的索引变更操作会抛出异常，从而触发上层事务的回滚路径。
 */
public final class FaultControl {

    volatile boolean failHashPut;
    volatile boolean failHashRemove;
    volatile boolean failOrderedPut;
    volatile boolean failOrderedRemove;

    public void failOnHashPut(boolean fail) {
        this.failHashPut = fail;
    }

    public void failOnHashRemove(boolean fail) {
        this.failHashRemove = fail;
    }

    public void failOnOrderedPut(boolean fail) {
        this.failOrderedPut = fail;
    }

    public void failOnOrderedRemove(boolean fail) {
        this.failOrderedRemove = fail;
    }

    /** 关闭全部故障注入。 */
    public void reset() {
        failHashPut = false;
        failHashRemove = false;
        failOrderedPut = false;
        failOrderedRemove = false;
    }
}
