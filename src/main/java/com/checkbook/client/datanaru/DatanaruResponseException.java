package com.checkbook.client.datanaru;

public class DatanaruResponseException extends RuntimeException {
    public DatanaruResponseException(String message) {
        super(message);
    }

    public DatanaruResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
