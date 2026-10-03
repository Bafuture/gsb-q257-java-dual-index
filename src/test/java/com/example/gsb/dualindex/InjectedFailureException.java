package com.example.gsb.dualindex;

/** Raised by the failing test indexes to simulate a backend failure. */
public class InjectedFailureException extends RuntimeException {

    public InjectedFailureException(String message) {
        super(message);
    }
}
