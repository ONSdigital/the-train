package com.github.davidcarboni.thetrain.storage;

import com.github.davidcarboni.cryptolite.Random;
import com.github.davidcarboni.thetrain.helpers.Hash;
import com.github.davidcarboni.thetrain.helpers.PathUtils;
import com.github.davidcarboni.thetrain.json.Transaction;
import com.github.davidcarboni.thetrain.json.UriInfo;
import com.github.davidcarboni.thetrain.json.request.Manifest;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PublisherTest {

    Transaction transaction;


    @BeforeClass
    public static void preSetup() throws Exception {
        Publisher.init(20);
    }

    @Before
    public void setUp() throws Exception {
        transaction = Transactions.create();

    }

    @Test
    public void shouldPublishFile() throws IOException {

        // Given
        // A URI to copy to with an existing published file.
        String uri = "/test.txt";
        Path website = Website.path();
        Files.move(tempFile(), PathUtils.toPath(uri, website)); // create published file in website directory

        // When
        // We publish the file
        Publisher.getInstance().addFile(transaction, uri, Random.inputStream(5000));

        // Then
        // The transaction should exist and be populated with values
        Path path = Publisher.getInstance().getFile(transaction, uri);
        assertNotNull(path);

        // there is a file in the backup directory that is the same as the website file
        Path backup = Transactions.backup(transaction);
        assertTrue(Files.exists(PathUtils.toPath(uri, backup)));
        assertEquals(Hash.sha(PathUtils.toPath(uri, backup)),
                Hash.sha(PathUtils.toPath(uri, website)));
    }

    @Test
    public void shouldPublishFileNotEncrypted() throws IOException {

        // Given
        // Content to publish
        Path file = tempFile();
        String sha = Hash.sha(file);

        // A URI to publish to
        String uri = "/test.txt";

        // An encrypted transaction
        Transaction transaction = Transactions.create();

        // When
        // We publish the file
        Publisher.getInstance().addFile(transaction, uri, Files.newInputStream(file));

        // Then
        // The published file should have the same hash as the original
        Path published = PathUtils.toPath(uri, Transactions.content(transaction));
        assertEquals(sha, Hash.sha(published));
        assertFalse(transaction.hasErrors());
    }

    @Test
    public void shouldMoveFile() throws IOException {
        // Given
        // A transaction
        Transaction transaction = Transactions.create();
        Path website = Website.path();

        // An existing file on the website
        String source = "/move-" + Random.id() + ".txt";
        String target = "/moved/move-" + Random.id() + ".txt";

        Path websiteTarget = PathUtils.toPath(target, website);
        Files.createDirectories(websiteTarget.getParent());
        Files.move(tempFile(), PathUtils.toPath(source, website));

        // When
        // Files being published
        Publisher.getInstance().copyFileIntoTransaction(transaction, source, target, website);

        // Then
        // The moved files should be in the transaction in the target location.
        Path path = Publisher.getInstance().getFile(transaction, target);
        assertNotNull(path);
        assertTrue(Files.exists(path));
        assertFalse(transaction.hasErrors());
    }

    @Test
    public void shouldNotMoveFileIfItAlreadyExists() throws IOException {
        // Given
        // A transaction
        Transaction transaction = Transactions.create();
        Path website = Website.path();

        // An existing file on the website
        String source = "/move-" + Random.id() + ".txt";
        String target = "/moved/move-" + Random.id() + ".txt";

        Path websiteTarget = PathUtils.toPath(target, website);
        Files.createDirectories(websiteTarget.getParent());
        Files.move(tempFile(), websiteTarget);
        Files.move(tempFile(), PathUtils.toPath(source, website));

        // When
        // Files being published
        Publisher.getInstance().copyFileIntoTransaction(transaction, source, target, website);

        // Then
        // The moved files should be in the transaction in the target location.
        Path path = Publisher.getInstance().getFile(transaction, target);
        assertNull(path);
    }


    @Test
    public void shouldComputeHash() throws IOException {

        // Given
        // A URI to copy to
        String uri = "/test.txt";

        // When
        // We publish the file
        Publisher.getInstance().addFile(transaction, uri, data());

        // Then
        // The transaction should exist and be populated with values
        Path path = Publisher.getInstance().getFile(transaction, uri);
        assertNotNull(path);
    }


    @Test
    public void shouldGetFile() throws IOException {

        // Given
        // A published file
        String uri = "/greeneggs.txt";
        Publisher.getInstance().addFile(transaction, uri, data());

        // When
        // We get the file
        Path path = Publisher.getInstance().getFile(transaction, "greeneggs.txt");

        // Then
        // The transaction should exist and be populated with values
        assertNotNull(path);
    }


    @Test
    public void shouldHandleSlashes() throws IOException {

        // Given
        // Files with inconsistent leading slashes
        String zero = "zero.txt";
        String one = "/one.txt";
        String two = "//two.txt";

        // When
        // We publish the files
        Publisher.getInstance().addFile(transaction, zero, data());
        Publisher.getInstance().addFile(transaction, one, data());
        Publisher.getInstance().addFile(transaction, two, data());

        // Then
        // The transaction should exist and be populated with values
        Path pathZero = Publisher.getInstance().getFile(transaction, "/zero.txt");
        Path pathOne = Publisher.getInstance().getFile(transaction, "/one.txt");
        Path pathTwo = Publisher.getInstance().getFile(transaction, "/two.txt");
        assertNotNull(pathZero);
        assertNotNull(pathOne);
        assertNotNull(pathTwo);
    }


    @Test
    public void shouldHandleSubdirectories() throws IOException {

        // Given
        // URIs that describe subdirectories
        String sub = "/folder/sub.txt";
        String subsub = "/another/directory/subsub.txt";

        // When
        // We publish data to those URIs
        Publisher.getInstance().addFile(transaction, sub, data());
        Publisher.getInstance().addFile(transaction, subsub, data());

        // Then
        // The data should be present at the requested URIs
        Path pathSub = Publisher.getInstance().getFile(transaction, sub);
        Path pathSubsub = Publisher.getInstance().getFile(transaction, subsub);
        assertNotNull(pathSub);
        assertNotNull(pathSubsub);
    }


    @Test
    public void shouldCommitTransaction() throws IOException {

        // Given

        // A transaction
        Transaction transaction = Transactions.create();
        Path content = Transactions.content(transaction);
        Path website = Website.path();

        // Files being published
        String create = "/create-" + Random.id() + ".txt";
        String update = "/update-" + Random.id() + ".txt";

        // An existing file on the website
        Path originalSource = tempFile();
        Files.copy(originalSource, PathUtils.toPath(update, website));

        Publisher.getInstance().addFile(transaction, create, data());
        Publisher.getInstance().addFile(transaction, update, data());

        // When
        // We commit the transaction
        Publisher.getInstance().commit(transaction, website);

        // Then
        // The published files should be on the website
        assertTrue(Files.exists(PathUtils.toPath(create, website)));
        assertTrue(Files.exists(PathUtils.toPath(update, website)));
        assertEquals(Hash.sha(PathUtils.toPath(create, content)),
                Hash.sha(PathUtils.toPath(create, website)));
        assertEquals(Hash.sha(PathUtils.toPath(update, content)),
                Hash.sha(PathUtils.toPath(update, website)));
        assertNotEquals(Hash.sha(originalSource),
                Hash.sha(PathUtils.toPath(update, website)));

        // Check the transaction details
        assertFalse(transaction.hasErrors());
        assertTrue(StringUtils.isNotBlank(transaction.startDate()));
        assertTrue(StringUtils.isNotBlank(transaction.endDate()));
        assertEquals(2, transaction.uris().size());
        assertTrue(transaction.uris().contains(new UriInfo(create)));
        assertTrue(transaction.uris().contains(new UriInfo(update)));
        for (UriInfo uriInfo : transaction.uris()) {
            assertEquals(UriInfo.COMMITTED, uriInfo.status());
        }
    }

    @Test
    public void shouldCommitDeletesInTransaction() throws IOException {

        // Given a transaction with deletes defined
        Transaction transaction = Transactions.create();
        String uri = "/some/uri";
        String uriForAssociatedFile = "/some/uri";

        // create the published file
        Path website = Website.path();
        Path targetPath = PathUtils.toPath(uri + "/data.json", website);
        Path targetPathForAssociatedFile = PathUtils.toPath(uriForAssociatedFile + "/12345.json", website);
        Files.createDirectories(targetPath.getParent());
        Files.copy(tempFile(), targetPath);
        Files.copy(tempFile(), targetPathForAssociatedFile);

        Manifest manifest = new Manifest();
        manifest.addUriToDelete(uri);
        Publisher.getInstance().addFilesToDelete(transaction, manifest);

        // When we commit the transaction
        Publisher.getInstance().commit(transaction, website);

        // Then the file is deleted from the website
        assertFalse(Files.exists(targetPath));
        assertFalse(Files.exists(targetPathForAssociatedFile));
        assertFalse(Files.exists(targetPath.getParent()));
    }

    @Test
    public void shouldReturnZeroFilesToDeleteForNullCollection() throws IOException {

        // Given a manifest with the filesToDelete set to null.
        Manifest manifest = new Manifest();

        // When we attempt to add the files to delete to the transaction.
        int filesToDelete = Publisher.getInstance().addFilesToDelete(transaction, manifest);

        // Then the return value is zero and no exception is thrown.
        assertEquals(0, filesToDelete);
    }

    @Test
    public void shouldCommitTransactionWithEncryption() throws IOException {

        // Given

        // A transaction
        String password = Random.password(8);
        Transaction transaction = Transactions.create();
        Path content = Transactions.content(transaction);
        Path website = Website.path();

        // A file being published
        String uri = "/file-" + Random.id() + ".txt";
        Path source = tempFile();
        String sha = Hash.sha(source);
        Publisher.getInstance().addFile(transaction, uri, Files.newInputStream(source));


        // When
        // We commit the transaction
        Publisher.getInstance().commit(transaction, website);


        // Then
        // The published file should be decrypted
        assertEquals(sha, Hash.sha(PathUtils.toPath(uri, website)));
        assertFalse(transaction.hasErrors());
    }


    @Test
    public void shouldRollbackTransaction() throws IOException {

        // Given

        // A transaction
        Path content = Transactions.content(transaction);
        Path backup = Transactions.backup(transaction);

        // Files being published
        String file = "/file-" + Random.id() + ".txt";
        Publisher.getInstance().addFile(transaction, file, data());


        // When
        // We roll back the transaction
        Publisher.getInstance().rollback(transaction);


        // Then
        // Check the transaction details
        assertFalse(transaction.hasErrors());
        assertTrue(StringUtils.isNotBlank(transaction.startDate()));
        assertTrue(StringUtils.isNotBlank(transaction.endDate()));
        assertEquals(1, transaction.uris().size());
        assertTrue(transaction.uris().contains(new UriInfo(file)));
        for (UriInfo uriInfo : transaction.uris()) {
            assertEquals(UriInfo.ROLLED_BACK, uriInfo.status());
        }
    }

    @Test
    public void shouldAddFilesToDelete() throws IOException {

        // Given a manifest with two files to delete.
        Manifest manifest = new Manifest();
        manifest.addUriToDelete("/some/uri");
        manifest.addUriToDelete("/some/other/uri");

        // When we add files to delete to the transaction.
        int filesToDelete = Publisher.getInstance().addFilesToDelete(this.transaction, manifest);

        // Then the returned number of deletes is as expected
        assertEquals(manifest.getUrisToDelete().size(), filesToDelete);

        // and the transaction contains a uriInfo instance for each delete added.
        ArrayList<UriInfo> uriInfos = new ArrayList<>(this.transaction.urisToDelete());
        assertEquals(manifest.getUrisToDelete().size(), uriInfos.size());

        for (UriInfo uriInfo : uriInfos) {
            assertTrue(manifest.getUrisToDelete().contains(uriInfo.uri()));
        }
    }

    @Test
    public void shouldBackupFilesWhenAddingFilesToDelete() throws IOException {

        // Given a manifest with two files to delete.
        Manifest manifest = new Manifest();
        String uri = "/some/uri";
        manifest.addUriToDelete(uri);

        Path website = Website.path();
        final String fileUri = uri + "/data.json";
        Path target = PathUtils.toPath(fileUri, website);
        Files.createDirectories(target.getParent());
        Files.move(tempFile(), target); // create published file in website directory

        // When we add files to delete to the transaction.
        int filesToDelete = Publisher.getInstance().addFilesToDelete(this.transaction, manifest);

        // Then the returned number of deletes is as expected
        assertEquals(manifest.getUrisToDelete().size(), filesToDelete);

        // and the transaction contains a uriInfo instance for each delete added.
        ArrayList<UriInfo> uriInfos = new ArrayList<>(this.transaction.urisToDelete());
        assertEquals(manifest.getUrisToDelete().size(), uriInfos.size());

        for (UriInfo uriInfo : uriInfos) {
            assertTrue(manifest.getUrisToDelete().contains(uriInfo.uri()));
        }

        // there is a file in the backup directory that is the same as the website file
        Path backup = Transactions.backup(transaction);
        assertTrue(Files.exists(PathUtils.toPath(uri, backup)));
        assertEquals(Hash.sha(PathUtils.toPath(fileUri, backup)),
                Hash.sha(PathUtils.toPath(fileUri, website)));

    }

    private static InputStream data() throws IOException {
        return Random.inputStream(5000);
    }

    private static Path tempFile() throws IOException {

        // A temp file
        Path file = Files.createTempFile(PublisherTest.class.getSimpleName(), ".txt");

        try (InputStream input = Random.inputStream(5000); OutputStream output = Files.newOutputStream(file)) {
            IOUtils.copy(input, output);
        }

        return file;
    }
}