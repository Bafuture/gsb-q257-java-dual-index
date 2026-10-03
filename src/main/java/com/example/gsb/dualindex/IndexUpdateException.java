package com.example.gsb.dualindex;

/**
 * Thrown when a mutating operation could not keep both indexes in sync.
 * The successful side has been rolled back, so the store is exactly in its
 * pre-operation state (unless the exception carries suppressed rollback
 * failures).
 */
public class IndexUpdateException extends DualIndexException {

    public IndexUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
