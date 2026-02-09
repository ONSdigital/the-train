package com.github.onsdigital.thetrain.service;

import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.json.Transaction;
import com.github.onsdigital.thetrain.json.request.Manifest;
import com.github.onsdigital.thetrain.storage.Publisher;
import com.github.onsdigital.thetrain.storage.TransactionUpdate;
import org.apache.hc.core5.http.HttpStatus;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Date;
import java.util.zip.ZipInputStream;

public class PublisherServiceImpl implements PublisherService {

    static final String COPY_FILE_TO_TRANS_ERR = "error copying files to transaction";
    static final String ADD_FILES_TO_TRANS_ERR = "error adding files to transaction";
    static final String ADD_FILES_FROM_ZIP_TO_TRANS_ERR = "error adding files from zip to transaction";
    static final String ADD_DELETES_TO_TRANS_ERR = "error adding files to delete to transaction";
    static final String WEDBSITE_PATH_NULL_ERR = "error getting website path config, expected value but was null";
    static final String WEDBSITE_PATH_ERR = "error getting website path config";
    static final String COMMIT_TRANS_ERROR = "error committing publishing transaction";
    static final String ROLLBACK_TRANS_ERROR = "error rolling back publishing transaction";

    private Publisher publisher;
    private Path websitePath;

    public PublisherServiceImpl(Publisher publisher, Path websitePath) {
        this.publisher = publisher;
        this.websitePath = websitePath;
    }

    @Override
    public boolean commit(Transaction transaction) throws PublishException {
        try {
            return publisher.commit(transaction, websitePath);
        } catch (Exception e) {
            throw new PublishException(COMMIT_TRANS_ERROR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public boolean rollback(Transaction transaction) throws PublishException {
        try {
            return publisher.rollback(transaction);
        } catch (Exception e) {
            throw new PublishException(ROLLBACK_TRANS_ERROR, e);
        }
    }

    @Override
    public int copyFilesIntoTransaction(Transaction transaction, Manifest manifest) throws PublishException {
        try {
            return publisher.copyFilesIntoTransaction(transaction, manifest, websitePath);
        } catch (Exception e) {
            throw new PublishException(COPY_FILE_TO_TRANS_ERR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public int addFilesToDelete(Transaction transaction, Manifest manifest) throws PublishException {
        try {
            return publisher.addFilesToDelete(transaction, manifest, websitePath);
        } catch (Exception e) {
            throw new PublishException(ADD_DELETES_TO_TRANS_ERR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public TransactionUpdate addContentToTransaction(Transaction transaction, String uri, InputStream input,
                                                     Date startDate) throws PublishException {
        try {
            return publisher.addContentToTransaction(transaction, uri, input, startDate, websitePath);
        } catch (Exception e) {
            throw new PublishException(ADD_FILES_TO_TRANS_ERR, e, transaction, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public boolean addFiles(Transaction transaction, String uri, ZipInputStream zip) throws PublishException {
        try {
            return publisher.addFiles(transaction, uri, zip, websitePath);
        } catch (Exception e) {
            throw new PublishException(ADD_FILES_FROM_ZIP_TO_TRANS_ERR, e, transaction,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
