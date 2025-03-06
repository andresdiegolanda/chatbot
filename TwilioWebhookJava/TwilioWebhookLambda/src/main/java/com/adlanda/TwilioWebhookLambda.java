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
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class TwilioWebhookLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static String cachedOpenAiApiKey = null; // API key cache

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Type", "application/xml");

        // Get the raw body, decode if necessary
        String rawBody = event.getBody();
        if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
            rawBody = new String(Base64.getDecoder().decode(rawBody));
        }
        context.getLogger().log("Raw body: " + rawBody);

        // Parse form data
        Map<String, String> params = parseFormData(rawBody);
        String from = params.getOrDefault("From", "Unknown");
        String messageBody = params.getOrDefault("Body", "").trim();
        String mediaUrl = params.get("MediaUrl0"); // Audio message URL

        context.getLogger().log("Received message from " + from + " with content: " + messageBody);

        // Handle version info request
        if (messageBody.equalsIgnoreCase("lambda version")) {
            String versionInfo = getLambdaVersionInfo();
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(responseHeaders)
                    .withBody("<Response><Message>" + versionInfo + "</Message></Response>");
        }

        String textToProcess;
        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            // Process audio message
            context.getLogger().log("Audio message detected. MediaUrl: " + mediaUrl);
            try {
                byte[] audioData = downloadAudio(mediaUrl, context);
                context.getLogger().log("Downloaded audio size: " + audioData.length + " bytes");
                String s3Uri = uploadAudioToS3(audioData, context);
                context.getLogger().log("Audio uploaded to S3. S3 URI: " + s3Uri);
                textToProcess = transcribeAudio(s3Uri, context);
                context.getLogger().log("Transcribed text: " + textToProcess);
            } catch (Exception e) {
                context.getLogger().log("Error processing audio: " + e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String stackTrace = sw.toString();
                String errorResponse = "<Response><Message>Error transcribing audio message: " 
                        + e.getMessage() + "\n" + stackTrace + "</Message></Response>";
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(responseHeaders)
                        .withBody(errorResponse);
            }
        } else {
            textToProcess = messageBody;
        }
        context.getLogger().log("Final text to process: " + textToProcess);

        String openAiApiKey = getOpenAiApiKey(context);
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            context.getLogger().log("Failed to retrieve OpenAI API key.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(responseHeaders)
                    .withBody("<Response><Message>Error: API key unavailable.</Message></Response>");
        }

        String chatGptResponse = callChatGpt(textToProcess, openAiApiKey, context);
        // If ChatGPT returns an empty response, use a fallback message.
        if (chatGptResponse == null || chatGptResponse.trim().isEmpty()) {
            chatGptResponse = "No response from ChatGPT.";
            context.getLogger().log("ChatGPT returned an empty response. Using fallback message.");
        }
        context.getLogger().log("ChatGPT response: " + chatGptResponse);

        String twimlResponse = "<Response><Message>" + chatGptResponse + "</Message></Response>";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(responseHeaders)
                .withBody(twimlResponse);
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

    private byte[] downloadAudio(String mediaUrl, Context context) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mediaUrl))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        context.getLogger().log("downloadAudio: Received audio data of length: " + response.body().length);
        return response.body();
    }

    private String uploadAudioToS3(byte[] audioData, Context context) throws IOException {
        String bucketName = "twilio-audio-messages-eu-west-1-andreslandaaws";
        String key = "transcribe/" + System.currentTimeMillis() + ".mp3";

        Path tempFile = Files.createTempFile("audio", ".mp3");
        Files.write(tempFile, audioData, StandardOpenOption.WRITE);
        context.getLogger().log("uploadAudioToS3: Temp file created at " + tempFile.toString() + " with size " + Files.size(tempFile) + " bytes");

        try (S3Client s3 = S3Client.builder().region(Region.of("eu-west-1")).build()){
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3.putObject(putReq, tempFile);
        }
        return "s3://" + bucketName + "/" + key;
    }

    private String transcribeAudio(String s3Uri, Context context) throws InterruptedException, IOException {
        try (TranscribeClient transcribeClient = TranscribeClient.builder().region(Region.of("eu-west-1")).build()){
            String jobName = "TranscriptionJob-" + System.currentTimeMillis();
            StartTranscriptionJobRequest startRequest = StartTranscriptionJobRequest.builder()
                    .transcriptionJobName(jobName)
                    .languageCode("en-US")
                    .mediaFormat("mp3")
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
                // Log each poll attempt with status.
                context.getLogger().log("transcribeAudio: Poll attempt " + (attempts + 1) + ", status: " + jobStatus);
                if ("COMPLETED".equals(jobStatus)) {
                    break;
                } else if ("FAILED".equals(jobStatus)) {
                    throw new RuntimeException("Transcription job failed: " +
                            getResponse.transcriptionJob().failureReason());
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
            return "Error calling ChatGPT API.";
        }
    }

    /**
     * Loads version information from version.properties.
     * Returns a clear message if not available.
     */
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
}
