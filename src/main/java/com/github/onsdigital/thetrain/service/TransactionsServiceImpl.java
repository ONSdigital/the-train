package com.github.onsdigital.thetrain.service;

import com.github.onsdigital.thetrain.exception.BadRequestException;
import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.storage.Transactions;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import spark.Request;

import java.nio.file.Path;
import java.util.concurrent.Future;

import static java.lang.String.format;

public class TransactionsServiceImpl implements TransactionsService {

    public static final String TRANSACTION_ID_KEY = "transactionId";
    public static final String TRANSACTON_ID_MISSING_ERR = "transactionID required but none provided";
    public static final String TRANSACTON_ID_UNKNOWN_ERR = "unknown transaction ID not found: transactionID: ";
    public static final String TRANS_CLOSED_ERR = "transaction closed transactionID: %s";
    public static final String TRANS_ASYNC_UPDATE_ERR = "error async updating transacion";
    public static final String TRANS_UPDATE_ERR = "error updating transacion";
    public static final String TRANS_CONTAINS_ERRS = "transaction cannot be committed because errors have been reported";
    public static final String TRANS_LIST_FILES_ERR = "error listing transaction files";
    public static final String TRANS_CONTENT_ERR = "error resolving transaction content path";

    @Override
    public Transaction create() throws PublishException {
        try {
            return Transactions.create();
        } catch (Exception e) {
            throw new PublishException("error while creating new publishing transaction", e, null,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Transaction getTransaction(Request request) throws BadRequestException, PublishException {
        return getTransaction(request.raw().getParameter(TRANSACTION_ID_KEY));
    }

    @Override
    public Transaction getTransaction(String transactionID) throws BadRequestException, PublishException {
        if (StringUtils.isBlank(transactionID)) {
            throw new BadRequestException(TRANSACTON_ID_MISSING_ERR);
        }

        // Get the transaction
        Transaction transaction = get(transactionID);
        if (transaction == null) {
            throw new BadRequestException(TRANSACTON_ID_UNKNOWN_ERR + transactionID);
        }

        // Check the transaction state
        if (!transaction.isOpen()) {
            throw new BadRequestException(TRANS_CLOSED_ERR, transactionID);
        }

        // Check for errors in the transaction
        if (transaction.hasErrors()) {
            throw new BadRequestException(TRANS_CONTAINS_ERRS);
        }

        return transaction;
    }

    @Override
    public void update(Transaction transaction) throws PublishException {
        try {
            Transactions.update(transaction);
        } catch (Exception e) {
            throw new PublishException(TRANS_UPDATE_ERR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void listFiles(Transaction transaction) throws PublishException {
        try {
            Transactions.listFiles(transaction);
        } catch (Exception e) {
            throw new PublishException(TRANS_LIST_FILES_ERR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Future<Boolean> tryUpdateAsync(Transaction transaction) throws PublishException {
        try {
            return Transactions.tryUpdateAsync(transaction.id());
        } catch (Exception e) {
            throw new PublishException(TRANS_ASYNC_UPDATE_ERR, e, transaction,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Path content(Transaction transaction) throws PublishException {
        try {
            return Transactions.content(transaction);
        } catch (Exception ex) {
            throw new PublishException(TRANS_CONTENT_ERR, ex, transaction,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private Transaction get(String id) throws PublishException {
        try {
            return Transactions.get(id);
        } catch (Exception e) {
            String message = format("error attempting to get transaction, transactionID: %s", id);
            throw new PublishException(message, e, null, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
