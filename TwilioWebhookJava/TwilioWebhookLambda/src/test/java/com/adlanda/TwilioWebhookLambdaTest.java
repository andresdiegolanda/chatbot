package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Base64;

public class TwilioWebhookLambdaTest {

    private TwilioWebhookLambda lambda;
    private Context context;

    @BeforeEach
    public void setUp() {
        lambda = new TwilioWebhookLambda();
        context = mock(Context.class);
        LambdaLogger logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    public void handleRequestShouldReturn200StatusAndXmlResponseWhenValidMessageProvided() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("From=%2B1234567890&Body=Hello");
        requestEvent.setIsBase64Encoded(false);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        assertTrue(responseEvent.getBody().contains("<Response><Message>"));
        assertTrue(responseEvent.getBody().contains("</Message></Response>"));
    }

    @Test
    public void handleRequestShouldReturn200StatusAndXmlResponseWhenBase64EncodedMessageProvided() {
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
    public void handleRequestShouldReturn500StatusAndErrorMessageWhenApiKeyIsMissing() {
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
        // Check that the response starts with the expected error message, ignoring appended debug logs.
        assertTrue(responseEvent.getBody().startsWith("<Response><Message>Error: API key unavailable."));
    }

    @Test
    public void handleRequestShouldReturn200StatusAndXmlResponseWhenEmptyBodyProvided() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("");
        requestEvent.setIsBase64Encoded(false);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        assertTrue(responseEvent.getBody().contains("<Response><Message>"));
        assertTrue(responseEvent.getBody().contains("</Message></Response>"));
    }

    @Test
    public void handleRequestShouldThrowIllegalArgumentExceptionWhenBase64EncodedBodyIsInvalid() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("!!!invalidbase64@@@");
        requestEvent.setIsBase64Encoded(true);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        assertThrows(IllegalArgumentException.class, () -> {
            lambda.handleRequest(requestEvent, context);
        });
    }

    @Test
    public void handleRequestShouldReturnVersionInfoWhenMessageIsLambdaVersion() {
        // Create an anonymous subclass that overrides getLambdaVersionInfo() to return test data.
        TwilioWebhookLambda lambdaVersion = new TwilioWebhookLambda() {
            @Override
            protected String getLambdaVersionInfo() {
                return "Artifact: TestArtifact, Version: 1.0, Compiled on: 2025-03-05";
            }
        };

        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("From=%2B1234567890&Body=lambda version");
        requestEvent.setIsBase64Encoded(false);

        LambdaLogger logger = context.getLogger();
        doNothing().when(logger).log(anyString());

        APIGatewayProxyResponseEvent responseEvent = lambdaVersion.handleRequest(requestEvent, context);

        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
        assertTrue(responseEvent.getBody().contains("Artifact: TestArtifact, Version: 1.0, Compiled on: 2025-03-05"));
    }
}
