package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;




/**
 * Additional tests have been added while retaining existing tests.
 */
public class TwilioWebhookLambdaTest {

    private TwilioWebhookLambda lambda;
    private Context context;

    @BeforeEach
    public void setUp() {
        lambda = new TwilioWebhookLambda();
        context = mock(Context.class);
        // Stub context.getLogger() to return a mock LambdaLogger
        LambdaLogger logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    public void testHandleRequestWithValidMessage() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("From=%2B1234567890&Body=Hello");
        requestEvent.setIsBase64Encoded(false);

        // Stub logger.log() to do nothing
        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        // Depending on external API calls the message may vary.
        // For consistency we check status and content type.
        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        // Either the ChatGPT call returns the default fallback message or a generated one.
        assertTrue(responseEvent.getBody().contains("<Response><Message>"));
        assertTrue(responseEvent.getBody().contains("</Message></Response>"));
    }

    @Test
    public void testHandleRequestWithBase64EncodedMessage() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        String encodedBody = Base64.getEncoder().encodeToString("From=%2B1234567890&Body=Hello".getBytes());
        requestEvent.setBody(encodedBody);
        requestEvent.setIsBase64Encoded(true);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        assertTrue(responseEvent.getBody().contains("<Response><Message>"));
        assertTrue(responseEvent.getBody().contains("</Message></Response>"));
    }

    @Test
    public void testHandleRequestWithMissingApiKey() {
        // Instead of spying (which causes issues on Java 22),
        // extend the Lambda to override getOpenAiApiKey
        TwilioWebhookLambda missingKeyLambda = new TwilioWebhookLambda() {
            @Override
            public String getOpenAiApiKey(Context ctx) {
                return ""; // Simulate missing API key
            }
        };

        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("From=%2B1234567890&Body=Hello");
        requestEvent.setIsBase64Encoded(false);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = missingKeyLambda.handleRequest(requestEvent, context);

        assertEquals(500, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        assertEquals("<Response><Message>Error: API key unavailable.</Message></Response>", responseEvent.getBody());
    }

    // Additional test: when body is empty
    @Test
    public void testHandleRequestWithEmptyBody() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("");
        requestEvent.setIsBase64Encoded(false);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        // With an empty body, parseFormData returns no parameters.
        // "From" defaults to "Unknown" and "Body" to an empty string.
        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        // The ChatGPT API is called with an empty prompt.
        // We simply check the response structure.
        assertTrue(responseEvent.getBody().contains("<Response><Message>"));
        assertTrue(responseEvent.getBody().contains("</Message></Response>"));
    }

    // Additional test: when base64--encoded body is invalid
    @Test
    public void testHandleRequestWithInvalidBase64() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        // Provide an invalid base64 encoded string
        requestEvent.setBody("!!!invalidbase64@@@");
        requestEvent.setIsBase64Encoded(true);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        // Expect the Base64 decoder to throw an IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            lambda.handleRequest(requestEvent, context);
        });
    }
}