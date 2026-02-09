package com.github.onsdigital.thetrain.routes;

import com.github.onsdigital.thetrain.exception.BadRequestException;
import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.helpers.PathUtils;
import com.github.onsdigital.thetrain.helpers.uploads.CloseablePart;
import com.github.onsdigital.thetrain.helpers.uploads.CloseablePartSupplier;
import com.github.onsdigital.thetrain.json.Result;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.service.PublisherService;
import com.github.onsdigital.thetrain.service.TransactionsService;
import com.github.onsdigital.thetrain.storage.TransactionUpdate;
import org.apache.hc.core5.http.HttpStatus;
import spark.Request;
import spark.Response;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.zip.ZipInputStream;

import static com.github.onsdigital.thetrain.logging.TrainEvent.info;

public class AddFileToTransaction extends BaseHandler {

    static final String ADD_FILE_ERR_MSG = "error adding file to transaction";

    private TransactionsService transactionsService;
    private PublisherService publisherService;
    private CloseablePartSupplier filePartSupplier;

    /**
     * Construct a new add file to transaction Route.
     */
    public AddFileToTransaction(TransactionsService transactionsService, PublisherService publisherService,
                                CloseablePartSupplier filePartSupplier) {
        this.transactionsService = transactionsService;
        this.publisherService = publisherService;
        this.filePartSupplier = filePartSupplier;
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        Date startDate = new Date();

        Transaction transaction = transactionsService.getTransaction(request);
        String uri = getURI(request);

        try {
            if (isZipped(request)) {
                handleZipRequest(request, transaction, uri);
            } else {
                handleNonZipRequest(request, transaction, uri, startDate);
            }
        } finally {
            transactionsService.tryUpdateAsync(transaction);
        }

        info().transactionID(transaction.id())
                .data("uri", uri)
                .log("file added to publish transaction successfully");

        return new Result("Published to " + uri, false, transaction);
    }

    /**
     * Handle a zip file request
     */
    private void handleZipRequest(Request request, Transaction transaction, String uri) throws PublishException,
            BadRequestException {
        info().transactionID(transaction.id()).data("uri", uri).log("attempting to add zip files to transactions");

        Path zipPath = writeZipToTransaction(request, transaction, uri);
        boolean isSuccess = extractZipContentIntoTransaction(zipPath, transaction, uri);

        if (!isSuccess) {
            throw new PublishException("error adding zipped files to transaction", transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        info().log("succcessfully added zip content to transaction");
    }

    private Path writeZipToTransaction(Request request, Transaction transaction, String uri) throws BadRequestException,
            PublishException {
        Path zipPath = createZipFileInTransaction(transaction, uri);

        try (
                CloseablePart closeablePart = filePartSupplier.getFilePart(request, transaction);
                InputStream in = closeablePart.getInputStream();
                ReadableByteChannel src = Channels.newChannel(in);
                FileOutputStream fos = new FileOutputStream(zipPath.toFile());
                FileChannel dest = fos.getChannel();
        ) {
            dest.transferFrom(src, 0, Long.MAX_VALUE);
            return zipPath;
        } catch (IOException ex) {
            throw new PublishException("error writing inputstream to transaction", ex, transaction);
        }
    }

    private boolean extractZipContentIntoTransaction(Path zipPath, Transaction transaction, String uri) throws PublishException {
        try (
                BufferedInputStream buf = new BufferedInputStream(Files.newInputStream(zipPath));
                ZipInputStream zipInputStream = new ZipInputStream((buf))
        ) {
            return publisherService.addFiles(transaction, uri, zipInputStream);
        } catch (IOException ex) {
            throw new PublishException("error attempting to extract zip content to transaction ", ex, transaction);
        }
    }

    /**
     * handle a single file request.
     */
    private void handleNonZipRequest(Request request, Transaction transaction, String uri, Date startDate)
            throws BadRequestException, PublishException {
        boolean isSuccess = false;
        info().transactionID(transaction.id()).data("uri", uri).log("attempting to add single file to transactions");
        try (
                CloseablePart closeablePart = filePartSupplier.getFilePart(request, transaction);
                InputStream data = closeablePart.getInputStream();
                InputStream bis = new BufferedInputStream(data)
        ) {
            TransactionUpdate update = publisherService.addContentToTransaction(transaction, uri, bis, startDate);
            isSuccess = update.isSuccess();
            transaction.addUri(update.getUriInfo());

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

        info().transactionID(transaction.id()).data("uri", uri).log("file successfully added to transaction");
    }

    private Path createZipFileInTransaction(Transaction transaction, String uri) throws PublishException {
        try {
            Path zipPath = getTransactionZipPath(transaction, uri);

            if (Files.notExists(zipPath.getParent())) {
                zipPath.toFile().getParentFile().mkdirs();
                Files.createFile(zipPath);
            }

            return zipPath;
        } catch (IOException ex) {
            throw new PublishException("error getting transaction content path", ex, transaction);
        }
    }

    private Path getTransactionZipPath(Transaction transaction, String uri) throws IOException, PublishException {
        Path zipPath = null;
        Path contentPath = transactionsService.content(transaction);

        if (uri.endsWith("/timeseries")) {
            String parentURI = Paths.get(uri).getParent().toString();
            zipPath = PathUtils.toPath(parentURI, contentPath).resolve("timeseries-to-publish.zip");
        } else {
            zipPath = PathUtils.toPath(uri, contentPath);
        }
        return zipPath;
    }
}
