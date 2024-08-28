package dev.jaoow.cotatrack.rest.exception;

public class FailedFetchException extends RuntimeException {
    public FailedFetchException(String message) {
        super(message);
    }
}
