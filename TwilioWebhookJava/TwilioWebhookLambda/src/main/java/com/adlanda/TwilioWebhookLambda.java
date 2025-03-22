package com.adlanda;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobResponse;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;

public class TwilioWebhookLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	// Constants to avoid magic literals.
	private static final String CONTENT_TYPE_XML = "application/xml";
	private static final String XML_RESPONSE_START = "<Response><Message>";
	private static final String XML_RESPONSE_END = "</Message></Response>";
	private static final String AUDIO_ERROR_MSG = "Error processing audio file";
	private static final String OPEN_AI_SECRET_NAME = "OpenAiApiKey";
	private static final String TWILIO_ACCOUNT_SID_KEY = "TWILIO_ACCOUNT_SID";
	private static final String TWILIO_AUTH_TOKEN_KEY = "TWILIO_AUTH_TOKEN";
	private static final String JSON_TRANSCRIPT = "transcript";
	private static final String JSON_CONTENT = "content";

	// Static initialization of clients with explicit credentials provider.
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	// Atomic caching for OpenAI API key.
	private static final AtomicReference<String> cachedOpenAiApiKey = new AtomicReference<>(null);
	private static final Region REGION = Region.of("eu-west-1");
	static final SecretsManagerClient SECRETS_MANAGER_CLIENT = SecretsManagerClient.builder().region(REGION)
			.credentialsProvider(DefaultCredentialsProvider.create()).build();
	private static final S3Client S3_CLIENT = S3Client.builder().region(REGION)
			.credentialsProvider(DefaultCredentialsProvider.create()).build();
	private static final TranscribeClient TRANSCRIBE_CLIENT = TranscribeClient.builder().region(REGION)
			.credentialsProvider(DefaultCredentialsProvider.create()).build();

	// Dedicated exception for Twilio credential issues.
	public static class TwilioCredentialsException extends Exception {
		public TwilioCredentialsException(String message) {
			super(message);
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
		Map<String, String> responseHeaders = new HashMap<>();
		responseHeaders.put("Content-Type", CONTENT_TYPE_XML);

		try {
			String rawBody = event.getBody();
			if (rawBody == null) {
				rawBody = "";
			}
			if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
				rawBody = new String(Base64.getDecoder().decode(rawBody), StandardCharsets.UTF_8);
			}
			logStructured(context, "rawBody", Map.of("body", rawBody));

			Map<String, String> params = parseFormData(rawBody);
			String messageBody = params.getOrDefault("Body", "").trim();
			String mediaUrl = params.get("MediaUrl0");

			// Handle "lambda version" request.
			if ("lambda version".equalsIgnoreCase(messageBody)) {
				String versionInfo = getLambdaVersionInfo();
				return createResponse(200, XML_RESPONSE_START + escapeXml(versionInfo) + XML_RESPONSE_END,
						responseHeaders);
			}
			// Audio processing branch.
			if (mediaUrl != null && !mediaUrl.isEmpty()) {
				return processAudioMessage(mediaUrl, context, responseHeaders);
			}
			// Non-audio (text) branch.
			return processTextMessage(messageBody, context, responseHeaders);
		} catch (Exception e) {
			logStructured(context, "error", Map.of("message", "Unhandled exception in handleRequest", "error",
					e.getMessage() == null ? "null" : e.getMessage()));
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			String errorResponse = XML_RESPONSE_START
					+ escapeXml("Unhandled exception: " + e.getMessage() + "\n" + sw.toString()) + XML_RESPONSE_END;
			return createResponse(500, errorResponse, responseHeaders);
		}
	}

	private APIGatewayProxyResponseEvent createResponse(int statusCode, String body, Map<String, String> headers) {
		return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withHeaders(headers).withBody(body);
	}

	private APIGatewayProxyResponseEvent processTextMessage(String messageBody, Context context,
			Map<String, String> responseHeaders) {
		logStructured(context, "processTextMessage", Map.of("finalText", messageBody));
		String openAiApiKey = getOpenAiApiKey(context);
		if (openAiApiKey == null || openAiApiKey.isEmpty()) {
			logStructured(context, "error", Map.of("message", "Failed to retrieve OpenAI API key"));
			String errBody = XML_RESPONSE_START + escapeXml("Error: API key unavailable.") + XML_RESPONSE_END;
			return createResponse(500, errBody, responseHeaders);
		}
		String chatGptResponse = callChatGpt(messageBody, openAiApiKey, context);
		if (chatGptResponse == null || chatGptResponse.trim().isEmpty()) {
			chatGptResponse = "No response from ChatGPT.";
			logStructured(context, "fallback", Map.of("message", "ChatGPT returned an empty response."));
		}
		logStructured(context, "chatGptResponse", Map.of("response", chatGptResponse));
		String twimlResponse = XML_RESPONSE_START + escapeXml(chatGptResponse) + XML_RESPONSE_END;
		return createResponse(200, twimlResponse, responseHeaders);
	}

	/**
	 * Refactored to reduce cognitive complexity.
	 */
	private APIGatewayProxyResponseEvent processAudioMessage(String mediaUrl, Context context,
			Map<String, String> responseHeaders) {
		StringBuilder debugLog = new StringBuilder();
		debugLog.append("Starting audio processing.\n");

		// Download audio safely.
		byte[] audioData = safeDownloadAudio(mediaUrl, context, debugLog);
		if (audioData.length == 0) {
			return createResponse(500, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
		}

		// Create temporary OGG file safely with explicit temp directory.
		Path tempOggFile = safeCreateTempOggFile(audioData, debugLog);
		if (tempOggFile == null) {
			return createResponse(500, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
		}

		String s3Uri = null;
		try {
			// Upload file safely.
			debugLog.append("Step 3: Uploading OGG file to S3...\n");
			s3Uri = safeUploadFile(tempOggFile, context, debugLog);
			debugLog.append("Uploaded to S3. S3 URI: ").append(s3Uri).append("\n");
			if (s3Uri == null) {
				return createResponse(500, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
			}
			// Get ChatGPT response from transcription.
			String chatGptResponse;
			try {
				chatGptResponse = getChatGptResponseForS3Uri(s3Uri, context, debugLog);
			} catch (IOException | InterruptedException ex) {
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				debugLog.append("Error processing transcription: ").append(ex.getMessage()).append("\n")
						.append(getStackTrace(ex)).append("\n");
				return createResponse(500, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
			}
			logStructured(context, "audioProcessing",
					Map.of("chatGptResponse", chatGptResponse, "debugLog", debugLog.toString()));
			return createResponse(200, XML_RESPONSE_START + escapeXml(chatGptResponse) + XML_RESPONSE_END,
					responseHeaders);
		} finally {
			try {
				Files.deleteIfExists(tempOggFile);
				debugLog.append("Temporary OGG file deleted.\n");
			} catch (IOException ioe) {
				debugLog.append("Error deleting OGG file: ").append(ioe.getMessage()).append("\n")
						.append(getStackTrace(ioe)).append("\n");
			}
		}
	}

	// Helper method: safely download audio.
	private byte[] safeDownloadAudio(String mediaUrl, Context context, StringBuilder debugLog) {
		try {
			debugLog.append("Step 1: Downloading audio...\n");
			byte[] data = downloadAudio(mediaUrl, context, debugLog);
			debugLog.append("Audio downloaded. Size: ").append(data.length).append(" bytes.\n");
			return data;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			debugLog.append("Error downloading audio: ").append(e.getMessage()).append("\n").append(getStackTrace(e))
					.append("\n");
			return new byte[0]; // Return an empty array instead of null.
		}
	}

	// Helper method: safely create temporary OGG file.
	private Path safeCreateTempOggFile(byte[] audioData, StringBuilder debugLog) {
		try {
			return createTempOggFile(audioData, debugLog);
		} catch (IOException e) {
			debugLog.append("Error saving to OGG file: ").append(e.getMessage()).append("\n").append(getStackTrace(e))
					.append("\n");
			return null;
		}
	}

	// Helper method: safely upload file.
	private String safeUploadFile(Path file, Context context, StringBuilder debugLog) {
		try {
			return uploadAudioToS3(file, context);
		} catch (IOException e) {
			debugLog.append("Error uploading OGG file: ").append(e.getMessage()).append("\n").append(getStackTrace(e))
					.append("\n");
			return null;
		}
	}

	// Helper method to obtain ChatGPT response from a transcription.
	private String getChatGptResponseForS3Uri(String s3Uri, Context context, StringBuilder debugLog)
			throws IOException, InterruptedException {
		String transcript = transcribeAudio(s3Uri, context);
		debugLog.append("Transcription complete. Transcript: ").append(transcript).append("\n");
		String openAiApiKey = getOpenAiApiKey(context);
		if (openAiApiKey == null || openAiApiKey.isEmpty()) {
			debugLog.append("Error: OpenAI API key unavailable.\n");
			return "";
		}
		String chatGptResponse = callChatGpt(transcript, openAiApiKey, context);
		debugLog.append("ChatGPT response: ").append(chatGptResponse).append("\n");
		return chatGptResponse;
	}

	// Helper method to create a temporary OGG file.
	private Path createTempOggFile(byte[] audioData, StringBuilder debugLog) throws IOException {
		Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
		Path tempOggFile = Files.createTempFile(tempDir, "audio-", ".ogg");
		Files.write(tempOggFile, audioData, StandardOpenOption.WRITE);
		debugLog.append("Temporary OGG file created at ").append(tempOggFile.toString()).append(" (")
				.append(Files.size(tempOggFile)).append(" bytes).\n");
		return tempOggFile;
	}

	Map<String, String> parseFormData(String data) {
		Map<String, String> map = new HashMap<>();
		if (data == null || data.isEmpty()) {
			return map;
		}
		String[] pairs = data.split("&");
		for (String pair : pairs) {
			String[] keyValue = pair.split("=", 2);
			if (keyValue.length == 2) {
				String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
				String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
				map.put(key, value);
			}
		}
		return map;
	}

	byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog)
			throws IOException, InterruptedException {
		Map<String, String> creds = getTwilioCredentials(context);
		if (creds.isEmpty() || !creds.containsKey(TWILIO_ACCOUNT_SID_KEY)
				|| !creds.containsKey(TWILIO_AUTH_TOKEN_KEY)) {
			throw new IOException("Twilio credentials not available.");
		}
		String credentials = creds.get(TWILIO_ACCOUNT_SID_KEY) + ":" + creds.get(TWILIO_AUTH_TOKEN_KEY);
		String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(mediaUrl))
				.header("Authorization", "Basic " + encodedCredentials).build();
		HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		debugLog.append("Twilio Response Code: ").append(response.statusCode()).append("\n");
		debugLog.append("Twilio Response Headers: ").append(response.headers().map().toString()).append("\n");
		logStructured(context, "downloadAudio",
				Map.of("responseCode", response.statusCode(), "dataLength", response.body().length));
		return response.body();
	}

	String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
		// TODO: Monitor AWS service limits for S3 usage.
		final String bucketName = "twilio-audio-messages-eu-west-1-andreslandaaws";
		final String key = "transcribe/" + UUID.randomUUID().toString() + ".ogg";
		logStructured(context, "uploadAudioToS3",
				Map.of("filePath", audioFilePath.toString(), "fileSize", Files.size(audioFilePath), "s3Key", key));
		PutObjectRequest putReq = PutObjectRequest.builder().bucket(bucketName).key(key).build();
		S3_CLIENT.putObject(putReq, audioFilePath);
		return "s3://" + bucketName + "/" + key;
	}

	String transcribeAudio(String s3Uri, Context context) throws IOException, InterruptedException {
		// Use UUID for unique transcription job name.
		String jobName = "TranscriptionJob-" + UUID.randomUUID().toString();
		StartTranscriptionJobRequest startRequest = StartTranscriptionJobRequest.builder().transcriptionJobName(jobName)
				.languageCode("en-US").mediaFormat("ogg").media(mediaBuilder -> mediaBuilder.mediaFileUri(s3Uri))
				.build();
		TRANSCRIBE_CLIENT.startTranscriptionJob(startRequest);

		String jobStatus = "";
		int attempts = 0;
		while (attempts < 30) {
			GetTranscriptionJobRequest getRequest = GetTranscriptionJobRequest.builder().transcriptionJobName(jobName)
					.build();
			GetTranscriptionJobResponse getResponse = TRANSCRIBE_CLIENT.getTranscriptionJob(getRequest);
			jobStatus = getResponse.transcriptionJob().transcriptionJobStatusAsString();
			logStructured(context, "transcribeAudio", Map.of("attempt", attempts + 1, "status", jobStatus));
			if ("COMPLETED".equals(jobStatus)) {
				break;
			} else if ("FAILED".equals(jobStatus)) {
				throw new IOException("Transcription job failed: " + getResponse.transcriptionJob().failureReason());
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new IOException("Thread interrupted during transcription polling.", ie);
			}
			attempts++;
		}
		if (!"COMPLETED".equals(jobStatus)) {
			throw new IOException("Transcription job timed out.");
		}
		GetTranscriptionJobResponse finalResponse = TRANSCRIBE_CLIENT
				.getTranscriptionJob(GetTranscriptionJobRequest.builder().transcriptionJobName(jobName).build());
		String transcriptFileUri = finalResponse.transcriptionJob().transcript().transcriptFileUri();
		logStructured(context, "transcribeAudio", Map.of("transcriptFileUri", transcriptFileUri));
		HttpRequest transcriptRequest = HttpRequest.newBuilder().uri(URI.create(transcriptFileUri)).build();
		HttpResponse<String> transcriptResponse = HTTP_CLIENT.send(transcriptRequest,
				HttpResponse.BodyHandlers.ofString());
		JsonNode transcriptJson = OBJECT_MAPPER.readTree(transcriptResponse.body());
		JsonNode transcriptsNode = transcriptJson.path("results").path("transcripts");
		if (!transcriptsNode.isArray() || transcriptsNode.size() == 0) {
			throw new IOException("Transcript JSON structure is invalid.");
		}
		return transcriptsNode.get(0).path(JSON_TRANSCRIPT).asText();
	}

	public static String getOpenAiApiKey(Context context) {
		String key = cachedOpenAiApiKey.get();
		if (key != null) {
			return key;
		}
		try {
			GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(OPEN_AI_SECRET_NAME).build();
			GetSecretValueResponse response = SECRETS_MANAGER_CLIENT.getSecretValue(request);
			String secret = response.secretString();
			if (secret != null && secret.trim().startsWith("{")) {
				JsonNode jsonNode = OBJECT_MAPPER.readTree(secret);
				if (jsonNode.has(OPEN_AI_SECRET_NAME)) {
					key = jsonNode.get(OPEN_AI_SECRET_NAME).asText();
					cachedOpenAiApiKey.compareAndSet(null, key);
					return key;
				}
			}
			cachedOpenAiApiKey.compareAndSet(null, secret);
			return secret;
		} catch (Exception e) {
			logStaticError(context, "Error retrieving secret " + OPEN_AI_SECRET_NAME + ": "
					+ (e.getMessage() == null ? "null" : e.getMessage()));
			return null;
		}
	}

	protected String callChatGpt(String prompt, String apiKey, Context context) {
		if (prompt == null || prompt.isEmpty()) {
			logStructured(context, "callChatGpt", Map.of("message", "Prompt is empty."));
			return "No prompt provided.";
		}
		try {
			String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("model", "gpt-3.5-turbo", "messages",
					new Object[] { Map.of("role", "system", JSON_CONTENT, "You are ChatGPT."),
							Map.of("role", "user", JSON_CONTENT, prompt) },
					"temperature", 0.7));
			logStructured(context, "callChatGpt", Map.of("requestBody", requestBody));
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			logStructured(context, "callChatGpt",
					Map.of("responseCode", response.statusCode(), "responseBody", response.body()));
			JsonNode jsonNode = OBJECT_MAPPER.readTree(response.body());
			JsonNode choicesNode = jsonNode.get("choices");
			if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
				return choicesNode.get(0).get("message").get(JSON_CONTENT).asText();
			} else {
				return "No response from ChatGPT API.";
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			logStructured(context, "callChatGpt", Map.of("error", e.getMessage() == null ? "null" : e.getMessage()));
			return "Error calling ChatGPT API: " + (e.getMessage() == null ? "null" : e.getMessage()) + "\n"
					+ getStackTrace(e);
		}
	}

	protected String getLambdaVersionInfo() {
		Properties properties = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("version.properties")) {
			if (in != null) {
				properties.load(in);
				String artifact = properties.getProperty("artifact.id", "N/A");
				String version = properties.getProperty("version", "N/A");
				String buildTimestamp = properties.getProperty("build.timestamp", "N/A");
				return "Artifact: " + artifact + ", Version: " + version + ", Compiled on: " + buildTimestamp;
			} else {
				return "Version information not available (version.properties not found).";
			}
		} catch (IOException e) {
			return "Error loading version information: " + e.getMessage();
		}
	}

	private String getStackTrace(Exception e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	private String escapeXml(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'",
				"&apos;");
	}

	Map<String, String> getTwilioCredentials(Context context) {
		final String secretName = "Twilio";
		try {
			GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(secretName).build();
			GetSecretValueResponse response = SECRETS_MANAGER_CLIENT.getSecretValue(request);
			String secret = response.secretString();
			JsonNode jsonNode = OBJECT_MAPPER.readTree(secret);
			Map<String, String> credentials = new HashMap<>();
			JsonNode sidNode = jsonNode.get(TWILIO_ACCOUNT_SID_KEY);
			JsonNode tokenNode = jsonNode.get(TWILIO_AUTH_TOKEN_KEY);
			if (sidNode == null || tokenNode == null) {
				logStructured(context, "error", Map.of("message", "Twilio secret is missing required keys."));
				return Collections.emptyMap();
			}
			credentials.put(TWILIO_ACCOUNT_SID_KEY, sidNode.asText());
			credentials.put(TWILIO_AUTH_TOKEN_KEY, tokenNode.asText());
			return credentials;
		} catch (Exception e) {
			logStructured(context, "error", Map.of("message", "Error retrieving Twilio credentials", "error",
					e.getMessage() == null ? "null" : e.getMessage()));
			return Collections.emptyMap();
		}
	}

	// Helper method for structured logging.
	private void logStructured(Context context, String event, Map<String, Object> details) {
		// Create a mutable copy of the provided details.
		Map<String, Object> mutableDetails = new HashMap<>(details);
		mutableDetails.put("event", event);
		try {
			String json = OBJECT_MAPPER.writeValueAsString(mutableDetails);
			context.getLogger().log(json);
		} catch (Exception e) {
			context.getLogger().log("{\"event\":\"loggingError\", \"error\":\"" + e.getMessage() + "\"}");
		}
	}

	// Static logging helper for static contexts.
	private static void logStaticError(Context context, String message) {
		context.getLogger().log("{\"event\":\"error\",\"message\":\"" + message + "\"}");
	}
}
