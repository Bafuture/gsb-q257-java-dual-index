package com.example.gsb.dualindex;

/** Unrecoverable failure of the dual-index component. */
public class DualIndexException extends RuntimeException {

    public DualIndexException(String message) {
        super(message);
    }

    public DualIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
