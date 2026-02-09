package com.github.onsdigital.thetrain.routes;

import com.github.onsdigital.thetrain.exception.BadRequestException;
import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.helpers.uploads.CloseablePart;
import com.github.onsdigital.thetrain.json.Result;
import com.github.onsdigital.thetrain.storage.TransactionUpdate;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import spark.Route;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.github.onsdigital.thetrain.routes.AddFileToTransaction.ADD_FILE_ERR_MSG;
import static com.github.onsdigital.thetrain.routes.BaseHandler.URI_MISSING_ERR;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class AddFileToTransactionTest extends BaseRouteTest {

    private Route route;
    private String testURI = "/a/b/c";

    @Mock
    private CloseablePart closeablePart;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Override
    public void customSetUp() throws Exception {
        when(transaction.id())
                .thenReturn(TRANSACTION_ID);

        route = new AddFileToTransaction(transactionsService, publisherService, filePartSupplier);
    }

    @Test(expected = BadRequestException.class)
    public void testAddFileTransactionIDNull() throws Exception {
        when(request.raw()).thenReturn(raw);

        when(transactionsService.getTransaction(request))
                .thenThrow(new BadRequestException("TEST", 500));

        try {
            route.handle(request, response);
        } catch (BadRequestException e) {
            assertThat(e.getMessage(), equalTo("TEST"));

            verify(transactionsService, times(1)).getTransaction(request);
            verifyZeroInteractions(publisherService);
            throw e;
        }
    }

    @Test(expected = BadRequestException.class)
    public void testAddFileURINull() throws Exception {
        when(request.raw()).thenReturn(raw);

        when(raw.getParameter("uri")).thenReturn(null);

        when(transactionsService.getTransaction(request)).thenReturn(transaction);

        try {
            route.handle(request, response);
        } catch (BadRequestException e) {
            assertThat(e.getMessage(), equalTo(URI_MISSING_ERR));

            verify(transactionsService, times(1)).getTransaction(request);
            verifyZeroInteractions(publisherService);
            throw e;
        }
    }

    @Test(expected = PublishException.class)
    public void testAddFileGetFileInputStreamException() throws Exception {
        when(request.raw()).thenReturn(raw);

        when(raw.getParameter("uri")).thenReturn(testURI);

        when(transactionsService.getTransaction(request)).thenReturn(transaction);

        when(filePartSupplier.getFilePart(request, transaction)).thenReturn(closeablePart);

        Exception expected = new IOException("Test");
        when(closeablePart.getInputStream()).thenThrow(expected);

        try {
            route.handle(request, response);
        } catch (PublishException e) {
            assertThat(e.getMessage(), equalTo("error adding file to transaction"));
            assertThat(e.getCause(), equalTo(expected));

            verify(transactionsService, times(1)).getTransaction(request);
            verify(filePartSupplier, times(1)).getFilePart(request, transaction);
            verify(closeablePart, times(1)).getInputStream();
            verify(closeablePart, times(1)).close();
            verifyZeroInteractions(publisherService);
            throw e;
        }
    }

    @Test(expected = PublishException.class)
    public void testAddContentToTransactionException() throws Exception {
        InputStream stream = new ByteArrayInputStream("SOME DATE".getBytes());
        PublishException cause = new PublishException("publisher error!");

        when(request.raw()).thenReturn(raw);

        when(raw.getParameter("uri")).thenReturn(testURI);

        when(transactionsService.getTransaction(request)).thenReturn(transaction);

        when(filePartSupplier.getFilePart(request, transaction)).thenReturn(closeablePart);

        when(closeablePart.getInputStream()).thenReturn(stream);

        when(publisherService.addContentToTransaction(eq(transaction), eq(testURI), any(InputStream.class), any(Date.class)))
                .thenThrow(cause);

        try {
            route.handle(request, response);
        } catch (PublishException e) {
            assertThat(e.getMessage(), equalTo(ADD_FILE_ERR_MSG));
            assertThat(e.getStatus(), equalTo(HttpStatus.SC_INTERNAL_SERVER_ERROR));
            assertThat(e.getCause(), equalTo(cause));

            verify(transactionsService, times(1)).getTransaction(request);
            verify(filePartSupplier, times(1)).getFilePart(request, transaction);
            verify(closeablePart, times(1)).getInputStream();
            verify(closeablePart, times(1)).close();
            verify(publisherService, times(1)).addContentToTransaction(
                    eq(transaction), eq(testURI), any(InputStream.class), any(Date.class));
            throw e;
        }
    }

    @Test(expected = PublishException.class)
    public void testAddContentToTransactionUnsuccessful() throws Exception {
        InputStream stream = new ByteArrayInputStream("SOME DATE".getBytes());
        TransactionUpdate update = new TransactionUpdate();
        update.setSuccess(false);

        when(request.raw()).thenReturn(raw);

        when(raw.getParameter("uri")).thenReturn(testURI);

        when(transactionsService.getTransaction(request)).thenReturn(transaction);

        when(filePartSupplier.getFilePart(request, transaction)).thenReturn(closeablePart);

        when(closeablePart.getInputStream()).thenReturn(stream);

        when(publisherService.addContentToTransaction(eq(transaction), eq(testURI), any(InputStream.class), any(Date.class)))
                .thenReturn(update);

        try {
            route.handle(request, response);
        } catch (PublishException e) {
            assertThat(e.getMessage(), equalTo(ADD_FILE_ERR_MSG));
            assertThat(e.getStatus(), equalTo(HttpStatus.SC_INTERNAL_SERVER_ERROR));

            verify(transactionsService, times(1)).getTransaction(request);
            verify(filePartSupplier, times(1)).getFilePart(request, transaction);
            verify(closeablePart, times(1)).getInputStream();
            verify(closeablePart, times(1)).close();
            verify(publisherService, times(1)).addContentToTransaction(
                    eq(transaction), eq(testURI), any(InputStream.class), any(Date.class));
            throw e;
        }
    }

    @Test
    public void testAddContentToTransactionSuccessful() throws Exception {
        InputStream stream = new ByteArrayInputStream("SOME DATE".getBytes());
        TransactionUpdate update = new TransactionUpdate();
        update.setSuccess(true);

        when(request.raw()).thenReturn(raw);

        when(raw.getParameter("uri")).thenReturn(testURI);

        when(transactionsService.getTransaction(request)).thenReturn(transaction);

        when(filePartSupplier.getFilePart(request, transaction)).thenReturn(closeablePart);

        when(closeablePart.getInputStream()).thenReturn(stream);

        when(publisherService.addContentToTransaction(eq(transaction), eq(testURI), any(InputStream.class), any(Date.class)))
                .thenReturn(update);

        Result actual = (Result) route.handle(request, response);

        assertThat(actual.message, equalTo("Published to " + testURI));
        assertThat(actual.transaction, equalTo(transaction));
        assertFalse(actual.error);

        verify(transactionsService, times(1)).getTransaction(request);
        verify(filePartSupplier, times(1)).getFilePart(request, transaction);
        verify(closeablePart, times(1)).getInputStream();
        verify(closeablePart, times(1)).close();
        verify(publisherService, times(1)).addContentToTransaction(
                eq(transaction), eq(testURI), any(InputStream.class), any(Date.class));
        verify(transactionsService, times(1)).tryUpdateAsync(transaction);
    }

    // This test is a beast... sorry
    @Test
    public void handle_timeSeriesZipFile_success() throws Exception {
        // The path of to the json file in the test resources. This is contained within the zip.
        Path srcJsonPath = Paths.get(getClass().getResource("/request-body/zip-content.json").getPath());

        // The path of the zip file in test resources - this zip is used for the request body.
        Path srcZipPath = Paths.get(getClass().getResource("/request-body/timeseries-to-publish.zip").getPath());

        // The uri of the content being added to the transaction.
        String uri = "/a/b/c/timeseries";

        try (InputStream requestBody = new FileInputStream(srcZipPath.toFile())) {
            when(transactionsService.getTransaction(request))
                    .thenReturn(transaction);

            when(request.raw())
                    .thenReturn(raw);

            when(raw.getParameter("uri"))
                    .thenReturn(uri);

            when(raw.getParameter("zip"))
                    .thenReturn("true");

            when(filePartSupplier.getFilePart(request, transaction)).thenReturn(closeablePart);

            when(closeablePart.getInputStream()).thenReturn(requestBody);

            // Create a transaction directory to use for the test.
            Path transactionDir = temporaryFolder.newFolder("test-transaction").toPath();
            transactionDir.toFile().deleteOnExit();

            when(transactionsService.content(transaction))
                    .thenReturn(transactionDir);

            // When the mock is called write the zip content to the temp dir so we can check it was written correctly.
            when(publisherService.addFiles(eq(transaction), eq("/a/b/c/timeseries"), any(ZipInputStream.class)))
                    .thenAnswer(invocationOnMock -> {
                        ZipInputStream zipIn = invocationOnMock.getArgumentAt(2, ZipInputStream.class);
                        writeZip(transactionDir.resolve("a/b/c/timeseries"), zipIn);
                        return true;
                    });

            // run the test and execute the route handler.
            Result actual = (Result) route.handle(request, response);

            // asser the results.
            assertThat(actual.error, is(false));

            // Check the zip file exists within the transaction and check its SHA-1 hash matches the origin src zip.
            Path zipTransactionPath = transactionDir.resolve("a/b/c/timeseries-to-publish.zip");
            assertTrue(Files.exists(zipTransactionPath));

            String srcZipHash = getSHA1Hash(srcZipPath);
            String transactionZipHash = getSHA1Hash(zipTransactionPath);
            assertThat(srcZipHash, equalTo(transactionZipHash));

            // check the transaction contains the expected json file and check its SHA-1 hash matches the hash for
            // the original src file - confirming the zip content was unpacked correctly in the transaction dir.
            Path transactionJsonPath = transactionDir.resolve("a/b/c/timeseries/zip-content.json");
            Files.exists(transactionJsonPath);

            String srcJsonHash = getSHA1Hash(srcJsonPath);
            String transactionJsonHash = getSHA1Hash(transactionJsonPath);
            assertThat(srcJsonHash, equalTo(transactionJsonHash));
        }
    }

    private void writeZip(Path transactionPath, ZipInputStream zipIn) throws Exception {
        if (Files.notExists(transactionPath)) {
            transactionPath.toFile().mkdirs();
        }

        byte[] buffer = new byte[1024];
        ZipEntry entry = zipIn.getNextEntry();
        while (entry != null) {
            Path filePath = transactionPath.resolve(entry.getName());
            Files.createFile(filePath);

            try (FileOutputStream os = new FileOutputStream(filePath.toFile())) {
                int len;

                while ((len = zipIn.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
            entry = zipIn.getNextEntry();
        }
    }

    private String getSHA1Hash(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            return DigestUtils.sha1Hex(in);
        }
    }
}
