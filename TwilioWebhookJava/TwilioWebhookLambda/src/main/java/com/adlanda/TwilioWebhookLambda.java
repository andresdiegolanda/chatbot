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
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
	private static final String JSON_CONTENT = "content";

	// Static initialization of clients with explicit credentials provider.
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	// Volatile to ensure thread safety.
	private static volatile String cachedOpenAiApiKey = null;
	private static final Region REGION = Region.of("eu-west-1");
	private static final SecretsManagerClient SECRETS_MANAGER_CLIENT = SecretsManagerClient.builder().region(REGION)
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
			if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
				rawBody = new String(Base64.getDecoder().decode(rawBody), StandardCharsets.UTF_8);
			}
			context.getLogger().log("Raw body: " + rawBody);

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
			context.getLogger().log("Unhandled exception in handleRequest: " + e.getMessage());
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
		context.getLogger().log("Final text to process: " + messageBody);
		String openAiApiKey = getOpenAiApiKey(context);
		if (openAiApiKey == null || openAiApiKey.isEmpty()) {
			context.getLogger().log("Failed to retrieve OpenAI API key.");
			String errBody = XML_RESPONSE_START + escapeXml("Error: API key unavailable.") + XML_RESPONSE_END;
			return createResponse(500, errBody, responseHeaders);
		}
		String chatGptResponse = callChatGpt(messageBody, openAiApiKey, context);
		if (chatGptResponse == null || chatGptResponse.trim().isEmpty()) {
			chatGptResponse = "No response from ChatGPT.";
			context.getLogger().log("ChatGPT returned an empty response. Using fallback message.");
		}
		context.getLogger().log("ChatGPT response: " + chatGptResponse);
		String twimlResponse = XML_RESPONSE_START + escapeXml(chatGptResponse) + XML_RESPONSE_END;
		return createResponse(200, twimlResponse, responseHeaders);
	}

	/**
	 * Refactored to reduce cognitive complexity.
	 */
	private APIGatewayProxyResponseEvent processAudioMessage(String mediaUrl, Context context,
			Map<String, String> responseHeaders) {
		StringBuilder debugLog = new StringBuilder();

		// Download audio safely.
		byte[] audioData = safeDownloadAudio(mediaUrl, context, debugLog);
		if (audioData.length == 0) {
			return createResponse(200, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
		}

		// Create temporary OGG file safely.
		Path tempOggFile = safeCreateTempOggFile(audioData, debugLog);
		if (tempOggFile == null) {
			return createResponse(200, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
		}

		// Upload file safely.
		String s3Uri = safeUploadFile(tempOggFile, context, debugLog);
		try {
			Files.deleteIfExists(tempOggFile);
			debugLog.append("Temporary OGG file deleted.\n");
		} catch (IOException ioe) {
			debugLog.append("Error deleting OGG file: ").append(ioe.getMessage()).append("\n")
					.append(getStackTrace(ioe)).append("\n");
		}
		if (s3Uri == null) {
			return createResponse(200, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
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
			return createResponse(200, XML_RESPONSE_START + AUDIO_ERROR_MSG + XML_RESPONSE_END, responseHeaders);
		}
		context.getLogger().log("Final debug log: " + debugLog.toString());
		return createResponse(200, XML_RESPONSE_START + escapeXml(chatGptResponse) + XML_RESPONSE_END, responseHeaders);
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
			debugLog.append("Step 3: Uploading OGG file to S3...\n");
			String s3Uri = uploadAudioToS3(file, context);
			debugLog.append("Uploaded to S3. S3 URI: ").append(s3Uri).append("\n");
			return s3Uri;
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
		Path tempOggFile = Files.createTempFile("audio", ".ogg");
		Files.write(tempOggFile, audioData, StandardOpenOption.WRITE);
		debugLog.append("Temporary OGG file created at ").append(tempOggFile.toString()).append(" (")
				.append(Files.size(tempOggFile)).append(" bytes).\n");
		return tempOggFile;
	}

	private Map<String, String> parseFormData(String data) {
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

	private byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog)
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
		context.getLogger().log("downloadAudio: Received audio data of length: " + response.body().length);
		return response.body();
	}

	private String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
		final String bucketName = "twilio-audio-messages-eu-west-1-andreslandaaws";
		final String key = "transcribe/" + System.currentTimeMillis() + ".ogg";
		context.getLogger().log("uploadAudioToS3: Uploading file " + audioFilePath.toString() + " of size "
				+ Files.size(audioFilePath) + " bytes");
		PutObjectRequest putReq = PutObjectRequest.builder().bucket(bucketName).key(key).build();
		S3_CLIENT.putObject(putReq, audioFilePath);
		return "s3://" + bucketName + "/" + key;
	}

	private String transcribeAudio(String s3Uri, Context context) throws IOException, InterruptedException {
		String jobName = "TranscriptionJob-" + System.currentTimeMillis();
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
			context.getLogger().log("transcribeAudio: Poll attempt " + (attempts + 1) + ", status: " + jobStatus);
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
		context.getLogger().log("transcribeAudio: Transcript file URI: " + transcriptFileUri);
		HttpRequest transcriptRequest = HttpRequest.newBuilder().uri(URI.create(transcriptFileUri)).build();
		HttpResponse<String> transcriptResponse = HTTP_CLIENT.send(transcriptRequest,
				HttpResponse.BodyHandlers.ofString());
		JsonNode transcriptJson = OBJECT_MAPPER.readTree(transcriptResponse.body());
		JsonNode transcriptsNode = transcriptJson.path("results").path("transcripts");
		if (!transcriptsNode.isArray() || transcriptsNode.size() == 0) {
			throw new IOException("Transcript JSON structure is invalid.");
		}
		return transcriptsNode.get(0).path(JSON_CONTENT).asText();
	}

	public static String getOpenAiApiKey(Context context) {
		if (cachedOpenAiApiKey != null) {
			return cachedOpenAiApiKey;
		}
		try {
			GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(OPEN_AI_SECRET_NAME).build();
			GetSecretValueResponse response = SECRETS_MANAGER_CLIENT.getSecretValue(request);
			String secret = response.secretString();
			if (secret != null && secret.trim().startsWith("{")) {
				JsonNode jsonNode = OBJECT_MAPPER.readTree(secret);
				if (jsonNode.has(OPEN_AI_SECRET_NAME)) {
					cachedOpenAiApiKey = jsonNode.get(OPEN_AI_SECRET_NAME).asText();
					return cachedOpenAiApiKey;
				}
			}
			cachedOpenAiApiKey = secret;
			return cachedOpenAiApiKey;
		} catch (Exception e) {
			context.getLogger().log("Error retrieving secret " + OPEN_AI_SECRET_NAME + ": " + e.getMessage());
			return null;
		}
	}

	protected String callChatGpt(String prompt, String apiKey, Context context) {
		if (prompt == null || prompt.isEmpty()) {
			context.getLogger().log("callChatGpt: Prompt is empty.");
			return "No prompt provided.";
		}
		try {
			String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("model", "gpt-3.5-turbo", "messages",
					new Object[] { Map.of("role", "system", JSON_CONTENT, "You are ChatGPT."),
							Map.of("role", "user", JSON_CONTENT, prompt) },
					"temperature", 0.7));
			context.getLogger().log("ChatGPT Request: " + requestBody);
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			context.getLogger().log("ChatGPT Response Code: " + response.statusCode());
			context.getLogger().log("ChatGPT Response Body: " + response.body());
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
			context.getLogger().log("Error calling ChatGPT API: " + e.getMessage());
			return "Error calling ChatGPT API: " + e.getMessage() + "\n" + getStackTrace(e);
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

	private Map<String, String> getTwilioCredentials(Context context) {
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
				context.getLogger().log("Twilio secret is missing required keys.");
				return Collections.emptyMap();
			}
			credentials.put(TWILIO_ACCOUNT_SID_KEY, sidNode.asText());
			credentials.put(TWILIO_AUTH_TOKEN_KEY, tokenNode.asText());
			return credentials;
		} catch (Exception e) {
			context.getLogger().log("Error retrieving Twilio credentials: " + e.getMessage());
			return Collections.emptyMap();
		}
	}
}
