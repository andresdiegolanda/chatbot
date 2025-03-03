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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

        String rawBody = event.getBody();
        if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
            rawBody = new String(Base64.getDecoder().decode(rawBody));
        }
        context.getLogger().log("Raw body: " + rawBody);

        Map<String, String> params = parseFormData(rawBody);
        String from = params.getOrDefault("From", "Unknown");
        String messageBody = params.getOrDefault("Body", "").trim();
        context.getLogger().log("Received message from " + from + " with content: " + messageBody);

        if (messageBody.equalsIgnoreCase("lambda version")) {
            String versionInfo = getLambdaVersionInfo();
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(responseHeaders)
                    .withBody("<Response><Message>" + versionInfo + "</Message></Response>");
        }

        String openAiApiKey = getOpenAiApiKey(context);
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            context.getLogger().log("Failed to retrieve OpenAI API key.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(responseHeaders)
                    .withBody("<Response><Message>Error: API key unavailable.</Message></Response>");
        }

        String chatGptResponse = callChatGpt(messageBody, openAiApiKey, context);
        String twimlResponse = "<Response><Message>" + chatGptResponse + "</Message></Response>";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(responseHeaders)
                .withBody(twimlResponse);
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

    public String getOpenAiApiKey(Context context) {
        if (cachedOpenAiApiKey != null) {
            return cachedOpenAiApiKey;
        }
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

    /**
     * Loads version information from the filtered properties file.
     */
    private String getLambdaVersionInfo() {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            if (in != null) {
                properties.load(in);
                String artifact = properties.getProperty("artifact.id", "N/A");
                String version = properties.getProperty("version", "N/A");
                String buildTimestamp = properties.getProperty("build.timestamp", "N/A");
                return "Artifact: " + artifact + ", Version: " + version + ", Compiled on: " + buildTimestamp;
            } else {
                return "Version information not available.";
            }
        } catch (IOException e) {
            return "Error loading version information: " + e.getMessage();
        }
    }

    private String callChatGpt(String prompt, String apiKey, Context context) {
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
                return choicesNode.get(0).get("message").get("content").asText();
            } else {
                return "No response from ChatGPT API.";
            }
        } catch (IOException | InterruptedException e) {
            context.getLogger().log("Error calling ChatGPT API: " + e.getMessage());
            return "Error calling ChatGPT API.";
        }
    }
}