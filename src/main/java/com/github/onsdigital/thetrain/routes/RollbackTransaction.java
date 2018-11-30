package com.github.onsdigital.thetrain.routes;

import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.json.Result;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.logging.LogBuilder;
import com.github.onsdigital.thetrain.service.PublisherService;
import com.github.onsdigital.thetrain.service.TransactionsService;
import spark.Request;
import spark.Response;

import static com.github.onsdigital.thetrain.logging.LogBuilder.logBuilder;
import static org.eclipse.jetty.http.HttpStatus.OK_200;

/**
 * {@link spark.Route} implementation for handling rollback publish transaction requests.
 */
public class RollbackTransaction extends BaseHandler {

    static final String ROLLBACK_SUCESS_MSG = "Transaction rolled back.";
    static final String ROLLBACK_UNSUCESSFUL_ERR = "rollback transaction was unsuccessful";

    private TransactionsService transactionsService;
    private PublisherService publisherService;

    /**
     * Construct a new rollback transaction {@link spark.Route}.
     *
     * @param transactionsService the {@link TransactionsService} to use.
     * @param publisherService    the {@link PublisherService} to use.
     */
    public RollbackTransaction(TransactionsService transactionsService, PublisherService publisherService) {
        this.transactionsService = transactionsService;
        this.publisherService = publisherService;
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        LogBuilder log = logBuilder();

        Transaction transaction = transactionsService.getTransaction(request);
        log.transactionID(transaction);

        try {
            if (!publisherService.rollback(transaction)) {
                throw new PublishException(ROLLBACK_UNSUCESSFUL_ERR, transaction);
            }

            // TODO no idea what this is doing... keeping it here to avoid breaking something
            transactionsService.listFiles(transaction);

            log.info("rollback transaction completed successfully");
            response.status(OK_200);
            return new Result(ROLLBACK_SUCESS_MSG, false, transaction);
        } finally {
            log.info("updating transaction");
            transactionsService.update(transaction);
        }
    }
}
