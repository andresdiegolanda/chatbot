package com.adlanda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

class TwilioWebhookLambdaTest {

	private TwilioWebhookLambda lambda;
	private Context context;

	public static class TestableTwilioWebhookLambda extends TwilioWebhookLambda {

		// Override to return a dummy ChatGPT response.
		@Override
		protected String callChatGpt(String prompt, String apiKey, Context context) {
			if (prompt != null && !prompt.isEmpty()) {
				return "dummy ChatGPT response";
			}
			return "";
		}

		// Do NOT override getOpenAiApiKey because we want it static.
		// Override to simulate transcription or throw an error for invalid URLs.
		protected String transcribeAudio(String s3Uri, Context context) throws java.io.IOException {
			if (s3Uri != null && s3Uri.contains("invalid-url")) {
				throw new java.io.IOException("Simulated transcription error");
			}
			return "Simulated transcript";
		}
	}

	@BeforeEach
	void setUp() {
		lambda = new TestableTwilioWebhookLambda();
		context = mock(Context.class);
		LambdaLogger logger = mock(LambdaLogger.class);
		when(context.getLogger()).thenReturn(logger);
	}

	@Test
	void handleRequestShouldReturn200AndValidXmlWhenTextMessageReceived() {
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&Body=Hello");
		request.setIsBase64Encoded(false);

		// Stub the static getOpenAiApiKey method to return a dummy API key.
		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("<Message>"), "Response body should contain <Message>");
			assertTrue(response.getBody().contains("dummy ChatGPT response"),
					"Response body should contain dummy ChatGPT response");
		}
	}

	@Test
	void handleRequestShouldReturn200AndCorrectXmlWhenAudioMessageProvided() {
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&MediaUrl0=https%3A%2F%2Finvalid-url.com%2Fbroken.mp3");
		request.setIsBase64Encoded(false);

		// Stub the static getOpenAiApiKey method.
		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("Error processing audio file"),
					"Response body should indicate an audio processing error");
		}
	}

	@Test
	void handleRequestShouldHandleCorrectlyWhenBodyIsBase64Encoded() {
		String body = "From=%2B1234567890&Body=Hello Base64";
		String encodedBody = Base64.getEncoder().encodeToString(body.getBytes());
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody(encodedBody);
		request.setIsBase64Encoded(true);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("<Message>"), "Response body should contain <Message>");
			assertTrue(response.getBody().contains("dummy ChatGPT response"),
					"Response body should contain dummy ChatGPT response");
		}
	}

	@Test
	void handleRequestShouldReturn500WhenOpenAiApiKeyUnavailable() {
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&Body=Test API Key");
		request.setIsBase64Encoded(false);

		// Stub the static method to simulate an unavailable API key (null).
		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn(null);

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(500, response.getStatusCode());
			assertTrue(response.getBody().contains("Error: API key unavailable"),
					"Response body should indicate API key error");
		}
	}

	@Test
	void handleRequestShouldReturnLambdaVersionWhenRequested() {
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&Body=lambda%20version");
		request.setIsBase64Encoded(false);

		// Even if not used, stub getOpenAiApiKey to a dummy value.
		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("Artifact:"), "Response body should contain version artifact info");
		}
	}

	@Test
	void handleRequestShouldReturnFallbackWhenChatGptReturnsEmpty() {
		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&Body=Test empty response");
		request.setIsBase64Encoded(false);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			// Create a lambda instance where callChatGpt returns an empty string.
			TwilioWebhookLambda emptyLambda = new TestableTwilioWebhookLambda() {
				@Override
				protected String callChatGpt(String prompt, String apiKey, Context context) {
					return "";
				}
			};

			APIGatewayProxyResponseEvent response = emptyLambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("No response from ChatGPT"),
					"Response body should indicate fallback for empty ChatGPT response");
		}
	}
}
