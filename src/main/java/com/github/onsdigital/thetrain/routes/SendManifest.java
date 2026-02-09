package com.github.onsdigital.thetrain.routes;

import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.json.Result;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.json.request.Manifest;
import com.github.onsdigital.thetrain.service.PublisherService;
import com.github.onsdigital.thetrain.service.TransactionsService;
import com.github.onsdigital.thetrain.storage.Transactions;
import com.google.gson.Gson;
import org.apache.hc.core5.http.HttpStatus;
import spark.Request;
import spark.Response;

import java.nio.file.Path;

import static com.github.onsdigital.thetrain.logging.TrainEvent.info;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jetty.http.HttpStatus.OK_200;

/**
 * Endpoint to move files within an existing {@link Transaction}.
 */
public class SendManifest extends BaseHandler {

    static final String COPY_RESULT_ERR = "the number of copied files does not match expected in value of the " +
            "manifest, expected: %d, actual %d";

    static final String DELETE_RESULT_ERR = "the number of delete files does not match expected in value of the " +
            "manifest, expected: %d, actual %d";

    private TransactionsService transactionsService;
    private PublisherService publisherService;
    private Path websiteContentPath;

    /**
     * Construct a new send manifest Route
     */
    public SendManifest(TransactionsService transactionsService, PublisherService publisherService,
                        Path websiteContentPath) {
        this.gson = new Gson();
        this.transactionsService = transactionsService;
        this.publisherService = publisherService;
        this.websiteContentPath = requireNonNull(websiteContentPath);
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        Transaction transaction = null;
        Manifest manifest = getManifest(request);

        try {
            transaction = transactionsService.getTransaction(request);

            int copied = publisherService.copyFilesIntoTransaction(transaction, manifest);
            int copyExpected = manifest.getFilesToCopy().size();
            if (copied != copyExpected) {
                throw new PublishException(format(COPY_RESULT_ERR, copied, copyExpected), transaction);
            }

            int deleted = publisherService.addFilesToDelete(transaction, manifest);
            int deleteExpected = manifest.getUrisToDelete().size();
            if (deleted != deleteExpected) {
                throw new PublishException(format(DELETE_RESULT_ERR, copied, deleteExpected), transaction);
            }

            // success
            info().transactionID(transaction.id())
                    .data("files_copied", copied)
                    .data("files_deleted", deleted)
                    .log("copying manifest files to website and adding files to delete completed successfully");

            response.status(OK_200);
            return new Result(format("Copied %d files. Deleted %s files.", copied, deleted), false, transaction);
        } finally {
            try {
                transactionsService.update(transaction);
                Transactions.update(transaction);
            } catch (PublishException e) {
                throw new PublishException("failed to update transaction", e, transaction,
                        HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
}
