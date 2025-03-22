package com.adlanda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

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
		// Override to simulate transcription failure when the s3Uri contains
		// "invalid-url".
		@Override
		protected String transcribeAudio(String s3Uri, Context context) throws IOException {
			if (s3Uri != null && s3Uri.contains("invalid-url")) {
				throw new IOException("Simulated transcription error");
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

		// Stub static method getOpenAiApiKey.
		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambda.handleRequest(request, context);

			assertEquals(200, response.getStatusCode());
			assertTrue(response.getBody().contains("<Message>"), "Response body should contain <Message>");
			assertTrue(response.getBody().contains("dummy ChatGPT response"),
					"Response body should contain dummy ChatGPT response");
		}
	}

	// This test now creates a lambda instance that simulates transcription failure
	// by overriding uploadAudioToS3 to return an S3 URI containing "invalid-url".
	@Test
	void handleRequestShouldReturn500AndCorrectXmlWhenAudioMessageProvided() {
		TestableTwilioWebhookLambda lambdaWithTranscriptionFailure = new TestableTwilioWebhookLambda() {
			@Override
			String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
				// Return a fake S3 URI that includes "invalid-url" so that transcribeAudio
				// throws an exception.
				return "s3://dummy-bucket/invalid-url.ogg";
			}
		};

		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		// The media URL here can be any non-empty string; the override forces the
		// error.
		request.setBody("From=%2B1234567890&MediaUrl0=https%3A%2F%2Finvalid-url.com%2Fbroken.mp3");
		request.setIsBase64Encoded(false);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambdaWithTranscriptionFailure.handleRequest(request, context);

			// Expecting a 500 response due to simulated transcription failure.
			assertEquals(500, response.getStatusCode(), "Expected status code 500 when transcription fails");
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

	// --- New Tests ---

	@Test
	void handleRequestShouldReturn500WhenAudioDownloadFails() {
		// Create a lambda instance that simulates an audio download failure.
		TestableTwilioWebhookLambda lambdaWithDownloadFailure = new TestableTwilioWebhookLambda() {
			@Override
			byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog)
					throws IOException, InterruptedException {
				if ("download-error".equals(mediaUrl)) {
					throw new RuntimeException("Simulated audio download failure");
				}
				return super.downloadAudio(mediaUrl, context, debugLog);
			}
		};

		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&MediaUrl0=download-error");
		request.setIsBase64Encoded(false);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambdaWithDownloadFailure.handleRequest(request, context);

			assertEquals(500, response.getStatusCode(), "Expected status code 500 when audio download fails");
			assertTrue(response.getBody().contains("Simulated audio download failure"),
					"Response body should contain the simulated audio download failure message");
		}
	}

	@Test
	void handleRequestShouldReturn500WhenS3UploadFails() {
		// Create a lambda instance that simulates an S3 upload failure.
		TestableTwilioWebhookLambda lambdaWithS3UploadFailure = new TestableTwilioWebhookLambda() {
			@Override
			byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog)
					throws IOException, InterruptedException {
				return new byte[] { 1, 2, 3 };
			}

			@Override
			String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
				throw new RuntimeException("Simulated S3 upload failure");
			}
		};

		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&MediaUrl0=dummy-audio-url");
		request.setIsBase64Encoded(false);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambdaWithS3UploadFailure.handleRequest(request, context);

			assertEquals(500, response.getStatusCode(), "Expected status code 500 when S3 upload fails");
			assertTrue(response.getBody().contains("Simulated S3 upload failure"),
					"Response body should contain the simulated S3 upload failure message");
		}
	}

	@Test
	void handleRequestShouldReturn500WhenTranscriptionFails() {
		// Create a lambda instance that simulates a transcription failure.
		TestableTwilioWebhookLambda lambdaWithTranscriptionFailure = new TestableTwilioWebhookLambda() {
			@Override
			byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog)
					throws IOException, InterruptedException {
				return new byte[] { 1, 2, 3 };
			}

			@Override
			String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
				return "s3://dummy-bucket/invalid-url.ogg";
			}

			@Override
			protected String transcribeAudio(String s3Uri, Context context) throws IOException {
				if (s3Uri.contains("invalid-url")) {
					throw new RuntimeException("Simulated transcription failure");
				}
				return super.transcribeAudio(s3Uri, context);
			}
		};

		APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
		request.setBody("From=%2B1234567890&MediaUrl0=dummy-audio-url");
		request.setIsBase64Encoded(false);

		try (MockedStatic<TwilioWebhookLambda> mockedStatic = mockStatic(TwilioWebhookLambda.class)) {
			mockedStatic.when(() -> TwilioWebhookLambda.getOpenAiApiKey(context)).thenReturn("dummyApiKey");

			APIGatewayProxyResponseEvent response = lambdaWithTranscriptionFailure.handleRequest(request, context);

			assertEquals(500, response.getStatusCode(), "Expected status code 500 when transcription fails");
			assertTrue(response.getBody().contains("Simulated transcription failure"),
					"Response body should contain the simulated transcription failure message");
		}
	}

	@Test
	void parseFormDataShouldParseCorrectlyWithValidData() {
		String data = "key1=value1&key2=value2";
		Map<String, String> result = lambda.parseFormData(data);
		assertEquals(2, result.size(), "Expected 2 key-value pairs");
		assertEquals("value1", result.get("key1"), "Expected key1 to map to value1");
		assertEquals("value2", result.get("key2"), "Expected key2 to map to value2");
	}

	@Test
	void parseFormDataShouldParseCorrectlyWithEmptyData() {
		String data = "";
		Map<String, String> result = lambda.parseFormData(data);
		assertTrue(result.isEmpty(), "Expected empty map for empty data");

		result = lambda.parseFormData(null);
		assertTrue(result.isEmpty(), "Expected empty map for null data");
	}
}
