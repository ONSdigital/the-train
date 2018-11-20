package com.github.onsdigital.thetrain.handlers;

import com.github.onsdigital.thetrain.exception.BadRequestException;
import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.helpers.FileUploadHelper;
import com.github.onsdigital.thetrain.json.Result;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.logging.LogBuilder;
import com.github.onsdigital.thetrain.service.TransactionsService;
import com.github.onsdigital.thetrain.storage.Publisher;
import com.github.onsdigital.thetrain.storage.TransactionUpdate;
import org.apache.http.HttpStatus;
import spark.Request;
import spark.Response;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.zip.ZipInputStream;

import static com.github.onsdigital.thetrain.logging.LogBuilder.logBuilder;

public class AddFileToTransaction extends BaseHandler {

    static final String ADD_FILE_ERR_MSG = "error adding file to transaction";

    private TransactionsService transactionsService;
    private Publisher publisher;
    private FileUploadHelper fileUploadHelper;

    public AddFileToTransaction(TransactionsService transactionsService, Publisher publisher,
                                FileUploadHelper fileUploadHelper) {
        this.transactionsService = transactionsService;
        this.publisher = publisher;
        this.fileUploadHelper = fileUploadHelper;
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        Date startDate = new Date();
        LogBuilder log = logBuilder();

        Transaction transaction = transactionsService.getTransaction(request);
        log.transactionID(transaction.id());

        String uri = getURI(request);
        log.uri(uri);

        if (isZipped(request)) {
            handleZipRequest(request, transaction, uri);
        } else {
            handleNonZipRequest(request, transaction, uri, startDate);
        }

        transactionsService.tryUpdateAsync(transaction);

        log.info("file added to publish transaction successfully");
        return new Result("Published to " + uri, false, transaction);
    }

    private void handleZipRequest(Request request, Transaction transaction, String uri) throws PublishException {
        boolean isSuccess = false;
        try (
                InputStream data = fileUploadHelper.getFileInputStream(request.raw(), transaction.id());
                ZipInputStream input = new ZipInputStream(new BufferedInputStream(data))
        ) {
            isSuccess = publisher.addFiles(transaction, uri, input);
        } catch (Exception e) {
            throw new PublishException("error processing zip request", e, transaction,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        if (!isSuccess) {
            throw new PublishException("error processing zip request", transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        logBuilder().info("file unzipped and added successfully");
    }

    private void handleNonZipRequest(Request request, Transaction transaction, String uri, Date startDate)
            throws BadRequestException, PublishException {
        boolean isSuccess = false;
        try (
                InputStream data = fileUploadHelper.getFileInputStream(request.raw(), transaction.id());
                InputStream bis = new BufferedInputStream(data)
        ) {
            TransactionUpdate update = publisher.addContentToTransaction(transaction, uri, bis, startDate);
            isSuccess = update.isSuccess();

        } catch (BadRequestException e) {
            // re-throw
            throw e;
        } catch (Exception e) {
            // treat all others are a publish exception.
            throw new PublishException(ADD_FILE_ERR_MSG, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        if (!isSuccess) {
            throw new PublishException(ADD_FILE_ERR_MSG, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        logBuilder().transactionID(transaction).uri(uri).info("file successfully added to transaction");
    }
}
