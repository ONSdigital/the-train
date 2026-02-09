package com.github.onsdigital.thetrain.routes;

import com.github.onsdigital.thetrain.exception.PublishException;
import com.github.onsdigital.thetrain.json.Result;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.Test;
import spark.Route;

import static com.github.onsdigital.thetrain.routes.OpenTransaction.SUCCESS_MSG;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OpenTransactionTest extends BaseRouteTest {

    private Route route;

    @Override
    public void customSetUp() throws Exception {
        when(transaction.id())
                .thenReturn("666");

        route = new OpenTransaction(transactionsService);
    }

    @Test(expected = PublishException.class)
    public void testHandlePublishException() throws Exception {
        when(transactionsService.create())
                .thenThrow(new PublishException("Test"));

        try {
            route.handle(request, response);
        } catch (PublishException e) {
            assertThat(e.getStatus(), equalTo(HttpStatus.SC_INTERNAL_SERVER_ERROR));
            assertThat(e.getMessage(), equalTo("Test"));
            verify(transactionsService, times(1)).create();
            throw e;
        }
    }

    @Test
    public void testHandleSuccess() throws Exception {
        when(transactionsService.create())
                .thenReturn(transaction);

        Result result = (Result) route.handle(request, response);

        verify(transactionsService, times(1)).create();
        verify(response, times(1)).status(HttpStatus.SC_OK);
        assertThat(result.message, equalTo(SUCCESS_MSG));
        assertThat(result.transaction, equalTo(transaction));
        assertFalse(result.error);
    }
}
