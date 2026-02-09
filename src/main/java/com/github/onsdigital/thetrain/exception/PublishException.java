package com.github.onsdigital.thetrain.exception;

import com.github.onsdigital.thetrain.json.Transaction;
import org.apache.hc.core5.http.HttpStatus;

public class PublishException extends Exception {

    private Transaction transaction;
    private int status;

    public PublishException(String message, Throwable cause, Transaction transaction, int status) {
        super(message, cause);
        this.status = status;
        this.transaction = transaction;
    }

    public PublishException(String message, Transaction transaction, int status) {
        super(message);
        this.status = status;
        this.transaction = transaction;
    }

    public PublishException(String message, Transaction transaction) {
        super(message);
        this.transaction = transaction;
        this.status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
    }

    public PublishException(String message, Throwable cause, Transaction transaction) {
        super(message, cause);
        this.transaction = transaction;
    }

    public PublishException(String message) {
        super(message);
        this.status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public int getStatus() {
        return status;
    }
}
