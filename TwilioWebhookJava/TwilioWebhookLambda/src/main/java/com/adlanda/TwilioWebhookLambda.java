package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.Media;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class TwilioWebhookLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Updated HttpClient that follows redirects.
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static String cachedOpenAiApiKey = null; // API key cache

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Type", "application/xml");

        try {
            String rawBody = event.getBody();
            if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
                rawBody = new String(Base64.getDecoder().decode(rawBody));
            }
            context.getLogger().log("Raw body: " + rawBody);

            Map<String, String> params = parseFormData(rawBody);
            String from = params.getOrDefault("From", "Unknown");
            String messageBody = params.getOrDefault("Body", "").trim();
            String mediaUrl = params.get("MediaUrl0");

            context.getLogger().log("Received message from " + from + " with content: " + messageBody);

            if (messageBody.equalsIgnoreCase("lambda version")) {
                String versionInfo = getLambdaVersionInfo();
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(responseHeaders)
                        .withBody("<Response><Message>" + escapeXml(versionInfo) + "</Message></Response>");
            }

            // Audio processing branch:
            if (mediaUrl != null && !mediaUrl.isEmpty()) {
                StringBuilder debugLog = new StringBuilder();
                boolean errorOccurred = false;
                try {
                    debugLog.append("Media URL: ").append(mediaUrl).append("\n");

                    // Step 1: Download audio.
                    byte[] audioData = null;
                    try {
                        debugLog.append("Step 1: Downloading audio...\n");
                        audioData = downloadAudio(mediaUrl, context, debugLog);
                        debugLog.append("Audio downloaded. Size: ").append(audioData.length).append(" bytes.\n");
                    } catch (Exception e) {
                        debugLog.append("Error downloading audio: ").append(e.getMessage()).append("\n")
                                .append(getStackTrace(e)).append("\n");
                        errorOccurred = true;
                    }

                    // Step 2: Save audio to temporary OGG file.
                    Path tempOggFile = null;
                    if (audioData != null) {
                        try {
                            debugLog.append("Step 2: Saving audio to temporary OGG file...\n");
                            tempOggFile = Files.createTempFile("audio", ".ogg");
                            Files.write(tempOggFile, audioData, StandardOpenOption.WRITE);
                            debugLog.append("Temporary OGG file created at ")
                                    .append(tempOggFile.toString())
                                    .append(" (").append(Files.size(tempOggFile)).append(" bytes).\n");
                        } catch (Exception e) {
                            debugLog.append("Error saving to OGG file: ").append(e.getMessage()).append("\n")
                                    .append(getStackTrace(e)).append("\n");
                            errorOccurred = true;
                        }
                    }

                    // Step 3: Upload OGG file to S3.
                    String s3Uri = null;
                    if (tempOggFile != null) {
                        try {
                            debugLog.append("Step 3: Uploading OGG file to S3...\n");
                            s3Uri = uploadAudioToS3(tempOggFile, context);
                            debugLog.append("Uploaded to S3. S3 URI: ").append(s3Uri).append("\n");
                        } catch (Exception e) {
                            debugLog.append("Error uploading OGG file: ").append(e.getMessage()).append("\n")
                                    .append(getStackTrace(e)).append("\n");
                            errorOccurred = true;
                        } finally {
                            try {
                                Files.deleteIfExists(tempOggFile);
                                debugLog.append("Temporary OGG file deleted.\n");
                            } catch (Exception e) {
                                debugLog.append("Error deleting OGG file: ").append(e.getMessage()).append("\n")
                                        .append(getStackTrace(e)).append("\n");
                            }
                        }
                    }

                    // Step 4: Start transcription job using OGG format.
                    if (s3Uri != null) {
                        try {
                            debugLog.append("Step 4: Starting transcription job...\n");
                            String transcript = transcribeAudio(s3Uri, context);
                            debugLog.append("Transcription complete. Transcript: ").append(transcript).append("\n");
                        } catch (Exception e) {
                            debugLog.append("Error transcribing audio: ").append(e.getMessage()).append("\n")
                                    .append(getStackTrace(e)).append("\n");
                            errorOccurred = true;
                        }
                    }
                } catch (Exception ex) {
                    debugLog.append("Unhandled exception in audio processing: ").append(ex.getMessage()).append("\n")
                            .append(getStackTrace(ex)).append("\n");
                    errorOccurred = true;
                }
                context.getLogger().log("Final debug log: " + debugLog.toString());
                String responseBody;
                if (errorOccurred) {
                    responseBody = "<Response><Message>Error processing audio file</Message></Response>";
                } else {
                    responseBody = "<Response><Message>" + escapeXml(debugLog.toString()) + "</Message></Response>";
                }
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(responseHeaders)
                        .withBody(responseBody);
            }

            // Non-audio branch:
            String textToProcess = messageBody;
            context.getLogger().log("Final text to process: " + textToProcess);

            String openAiApiKey = getOpenAiApiKey(context);
            if (openAiApiKey == null || openAiApiKey.isEmpty()) {
                context.getLogger().log("Failed to retrieve OpenAI API key.");
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(500)
                        .withHeaders(responseHeaders)
                        .withBody("<Response><Message>" + escapeXml("Error: API key unavailable.") + "</Message></Response>");
            }

            String chatGptResponse = callChatGpt(textToProcess, openAiApiKey, context);
            if (chatGptResponse == null || chatGptResponse.trim().isEmpty()) {
                chatGptResponse = "No response from ChatGPT.";
                context.getLogger().log("ChatGPT returned an empty response. Using fallback message.");
            }
            context.getLogger().log("ChatGPT response: " + chatGptResponse);

            String twimlResponse = "<Response><Message>" + escapeXml(chatGptResponse) + "</Message></Response>";
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(responseHeaders)
                    .withBody(twimlResponse);
        } catch (Exception e) {
            context.getLogger().log("Unhandled exception in handleRequest: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String errorResponse = "<Response><Message>" + escapeXml("Unhandled exception: " + e.getMessage() + "\n" + sw.toString()) + "</Message></Response>";
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(responseHeaders)
                    .withBody(errorResponse);
        }
    }

    private Map<String, String> parseFormData(String data) {
        Map<String, String> map = new HashMap<>();
        if (data == null || data.isEmpty()) return map;
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

    // Retrieve Twilio credentials from Secrets Manager using secret name "Twilio".
    private Map<String, String> getTwilioCredentials(Context context) {
        String secretName = "Twilio";
        Region region = Region.of("eu-west-1");
        SecretsManagerClient client = SecretsManagerClient.builder().region(region).build();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secret = response.secretString();
            JsonNode jsonNode = objectMapper.readTree(secret);
            Map<String, String> credentials = new HashMap<>();
            JsonNode sidNode = jsonNode.get("TWILIO_ACCOUNT_SID");
            JsonNode tokenNode = jsonNode.get("TWILIO_AUTH_TOKEN");
            if (sidNode == null || tokenNode == null) {
                throw new RuntimeException("Twilio secret is missing required keys.");
            }
            credentials.put("TWILIO_ACCOUNT_SID", sidNode.asText());
            credentials.put("TWILIO_AUTH_TOKEN", tokenNode.asText());
            return credentials;
        } catch (Exception e) {
            context.getLogger().log("Error retrieving Twilio credentials: " + e.getMessage());
            return null;
        } finally {
            client.close();
        }
    }

    // downloadAudio uses Basic auth with credentials from Secrets Manager.
    private byte[] downloadAudio(String mediaUrl, Context context, StringBuilder debugLog) throws IOException, InterruptedException {
        Map<String, String> creds = getTwilioCredentials(context);
        if (creds == null || !creds.containsKey("TWILIO_ACCOUNT_SID") || !creds.containsKey("TWILIO_AUTH_TOKEN")) {
            throw new IOException("Twilio credentials not available.");
        }
        String credentials = creds.get("TWILIO_ACCOUNT_SID") + ":" + creds.get("TWILIO_AUTH_TOKEN");
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mediaUrl))
                .header("Authorization", "Basic " + encodedCredentials)
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        debugLog.append("Twilio Response Code: ").append(response.statusCode()).append("\n");
        debugLog.append("Twilio Response Headers: ").append(response.headers().map().toString()).append("\n");
        context.getLogger().log("downloadAudio: Received audio data of length: " + response.body().length);
        return response.body();
    }

    // Uploads the OGG file to S3.
    private String uploadAudioToS3(Path audioFilePath, Context context) throws IOException {
        String bucketName = "twilio-audio-messages-eu-west-1-andreslandaaws";
        String key = "transcribe/" + System.currentTimeMillis() + ".ogg";
        context.getLogger().log("uploadAudioToS3: Uploading file " + audioFilePath.toString()
                + " of size " + Files.size(audioFilePath) + " bytes");
        try (S3Client s3 = S3Client.builder().region(Region.of("eu-west-1")).build()){
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3.putObject(putReq, audioFilePath);
        }
        return "s3://" + bucketName + "/" + key;
    }

    // Transcribe the OGG file using AWS Transcribe (media format "ogg").
    private String transcribeAudio(String s3Uri, Context context) throws InterruptedException, IOException {
        try (TranscribeClient transcribeClient = TranscribeClient.builder().region(Region.of("eu-west-1")).build()){
            String jobName = "TranscriptionJob-" + System.currentTimeMillis();
            StartTranscriptionJobRequest startRequest = StartTranscriptionJobRequest.builder()
                    .transcriptionJobName(jobName)
                    .languageCode("en-US")
                    .mediaFormat("ogg")
                    .media(Media.builder().mediaFileUri(s3Uri).build())
                    .build();
            transcribeClient.startTranscriptionJob(startRequest);

            String jobStatus = "";
            int attempts = 0;
            while (attempts < 30) {
                GetTranscriptionJobRequest getRequest = GetTranscriptionJobRequest.builder()
                        .transcriptionJobName(jobName)
                        .build();
                GetTranscriptionJobResponse getResponse = transcribeClient.getTranscriptionJob(getRequest);
                jobStatus = getResponse.transcriptionJob().transcriptionJobStatusAsString();
                context.getLogger().log("transcribeAudio: Poll attempt " + (attempts + 1) + ", status: " + jobStatus);
                if ("COMPLETED".equals(jobStatus)) {
                    break;
                } else if ("FAILED".equals(jobStatus)) {
                    throw new RuntimeException("Transcription job failed: " + getResponse.transcriptionJob().failureReason());
                }
                Thread.sleep(10000);
                attempts++;
            }
            if (!"COMPLETED".equals(jobStatus)) {
                throw new RuntimeException("Transcription job timed out.");
            }
            GetTranscriptionJobResponse finalResponse = transcribeClient.getTranscriptionJob(
                    GetTranscriptionJobRequest.builder().transcriptionJobName(jobName).build());
            String transcriptFileUri = finalResponse.transcriptionJob().transcript().transcriptFileUri();
            context.getLogger().log("transcribeAudio: Transcript file URI: " + transcriptFileUri);
            HttpRequest transcriptRequest = HttpRequest.newBuilder()
                    .uri(URI.create(transcriptFileUri))
                    .build();
            HttpResponse<String> transcriptResponse = httpClient.send(transcriptRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode transcriptJson = objectMapper.readTree(transcriptResponse.body());
            String transcriptText = transcriptJson.get("results").get("transcripts").get(0).get("transcript").asText();
            return transcriptText;
        }
    }

    public String getOpenAiApiKey(Context context) {
        if (cachedOpenAiApiKey != null) return cachedOpenAiApiKey;
        String secretName = "OpenAiApiKey";
        Region region = Region.of("eu-west-1");

        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(region)
                .build();
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();
        try {
            GetSecretValueResponse response = client.getSecretValue(request);
            String secret = response.secretString();
            if (secret.trim().startsWith("{")) {
                JsonNode jsonNode = objectMapper.readTree(secret);
                if (jsonNode.has("OpenAiApiKey")) {
                    cachedOpenAiApiKey = jsonNode.get("OpenAiApiKey").asText();
                    return cachedOpenAiApiKey;
                }
            }
            cachedOpenAiApiKey = secret;
            return cachedOpenAiApiKey;
        } catch (Exception e) {
            context.getLogger().log("Error retrieving secret " + secretName + ": " + e.getMessage());
            return null;
        } finally {
            client.close();
        }
    }

    private String callChatGpt(String prompt, String apiKey, Context context) {
        if (prompt == null || prompt.isEmpty()) {
            context.getLogger().log("callChatGpt: Prompt is empty.");
            return "No prompt provided.";
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", "gpt-3.5-turbo",
                    "messages", new Object[] {
                            Map.of("role", "system", "content", "You are ChatGPT."),
                            Map.of("role", "user", "content", prompt)
                    },
                    "temperature", 0.7
            ));
            context.getLogger().log("ChatGPT Request: " + requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            context.getLogger().log("ChatGPT Response Code: " + response.statusCode());
            context.getLogger().log("ChatGPT Response Body: " + response.body());
            JsonNode jsonNode = objectMapper.readTree(response.body());
            JsonNode choicesNode = jsonNode.get("choices");
            if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
                String result = choicesNode.get(0).get("message").get("content").asText();
                return result;
            } else {
                return "No response from ChatGPT API.";
            }
        } catch (IOException | InterruptedException e) {
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
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
