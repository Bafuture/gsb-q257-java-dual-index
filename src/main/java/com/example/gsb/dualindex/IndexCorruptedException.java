package com.example.gsb.dualindex;

/**
 * 内部索引结构出现不可能状态（例如有序索引内部计数为负）时抛出。
 * 属于组件自身的防御性检查，正常路径不会触发。
 */
public class IndexCorruptedException extends RuntimeException {

    public IndexCorruptedException(String message) {
        super(message);
    }
}
