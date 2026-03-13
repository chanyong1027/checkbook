package com.checkbook.elibrary.client;

public class ELibraryClientException extends RuntimeException {

    public ELibraryClientException(String message) {
        super(message);
    }

    public ELibraryClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
