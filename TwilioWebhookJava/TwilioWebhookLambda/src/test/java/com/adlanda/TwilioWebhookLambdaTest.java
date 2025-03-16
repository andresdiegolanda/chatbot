package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import static org.mockito.Mockito.*;

public class TwilioWebhookLambdaTest {

    private TwilioWebhookLambda lambda;
    private Context context;

    public static class TestableTwilioWebhookLambda extends TwilioWebhookLambda {

        // Override to return a dummy ChatGPT response.
        protected String callChatGpt(String prompt, String apiKey, Context context) {
            if (prompt != null && !prompt.isEmpty()) {
                return "dummy ChatGPT response";
            }
            return "";
        }

        // Override to return a dummy API key.
        public String getOpenAiApiKey(Context context) {
            return "dummyApiKey";
        }

        // Override to simulate transcription or throw an error for invalid URLs.
        protected String transcribeAudio(String s3Uri, Context context)
                throws InterruptedException, java.io.IOException {
            if (s3Uri != null && s3Uri.contains("invalid-url")) {
                throw new java.io.IOException("Simulated transcription error");
            }
            return "Simulated transcript";
        }
    }

    @BeforeEach
    public void setUp() {
        // Use the testable lambda to avoid any external calls.
        lambda = new TestableTwilioWebhookLambda();
        context = mock(Context.class);
        LambdaLogger logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    public void handleRequestShouldReturn200AndValidXmlWhenTextMessageReceived() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("From=%2B1234567890&Body=Hello");
        request.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

        // Verify status code and that the body contains expected XML.
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("<Message>"), "Response body should contain <Message>");
        assertTrue(response.getBody().contains("dummy ChatGPT response"), "Response body should contain dummy ChatGPT response");
    }

    @Test
    public void handleRequestShouldReturn200AndCorrectXmlWhenAudioMessageProvided() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("From=%2B1234567890&MediaUrl0=https%3A%2F%2Finvalid-url.com%2Fbroken.mp3");
        request.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

        // Verify status code and error message in the body.
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Error processing audio file"), "Response body should indicate an audio processing error");
    }

    @Test
    public void handleRequestShouldHandleCorrectlyWhenBodyIsBase64Encoded() {
        String body = "From=%2B1234567890&Body=Hello Base64";
        String encodedBody = Base64.getEncoder().encodeToString(body.getBytes());
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(encodedBody);
        request.setIsBase64Encoded(true);

        APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

        // Verify status code and that the body contains expected XML.
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("<Message>"), "Response body should contain <Message>");
        assertTrue(response.getBody().contains("dummy ChatGPT response"), "Response body should contain dummy ChatGPT response");
    }

    @Test
    public void handleRequestShouldReturn500WhenOpenAiApiKeyUnavailable() {
        // Create a subclass instance that returns null for the API key.
        TwilioWebhookLambda lambdaNoApiKey = new TestableTwilioWebhookLambda() {
            @Override
            public String getOpenAiApiKey(Context context) {
                return null;
            }
        };

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("From=%2B1234567890&Body=Test API Key");
        request.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent response = lambdaNoApiKey.handleRequest(request, context);

        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Error: API key unavailable"), "Response body should indicate API key error");
    }

    @Test
    public void handleRequestShouldReturnLambdaVersionWhenRequested() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("From=%2B1234567890&Body=lambda%20version");
        request.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Artifact:"), "Response body should contain version artifact info");
    }

    @Test
    public void handleRequestShouldReturnFallbackWhenChatGptReturnsEmpty() {
        // Create a subclass where callChatGpt returns an empty string.
        TwilioWebhookLambda emptyLambda = new TestableTwilioWebhookLambda() {
            protected String callChatGpt(String prompt, String apiKey, Context context) {
                return "";
            }
        };

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("From=%2B1234567890&Body=Test empty response");
        request.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent response = emptyLambda.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("No response from ChatGPT"), "Response body should indicate fallback for empty ChatGPT response");
    }
}
