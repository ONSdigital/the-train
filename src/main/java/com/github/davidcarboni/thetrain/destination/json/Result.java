package com.github.davidcarboni.thetrain.destination.json;

/**
 * Created by david on 31/07/2015.
 */
public class Result {
    public String message;
    public boolean error;
    public Transaction transaction;

    /**
     * The response message returned from the endpoints in this class.
     *
     * @param message     An informational message.
     * @param error       If
     * @param transaction
     */
    public Result(String message, boolean error, Transaction transaction) {
        this.message = message;
        this.error = error;
        this.transaction = transaction;
    }
}
