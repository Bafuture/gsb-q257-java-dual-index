package com.example.gsb.dualindex;

/**
 * 写入/删除/排序键更新过程中某一侧索引操作失败时抛出。
 * 抛出前组件已完成回滚，两个索引恢复到操作前的状态。
 */
public class IndexUpdateException extends RuntimeException {

    public IndexUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
