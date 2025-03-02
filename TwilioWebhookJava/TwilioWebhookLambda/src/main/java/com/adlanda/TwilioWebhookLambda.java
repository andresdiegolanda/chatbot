package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// AWS SDK v2 imports for Secrets Manager
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

public class TwilioWebhookLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        // Set response headers (we return XML as required by Twilio)
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Type", "application/xml");

        // Get the raw body and decode if necessary
        String rawBody = event.getBody();
        if (event.getIsBase64Encoded() != null && event.getIsBase64Encoded()) {
            rawBody = new String(Base64.getDecoder().decode(rawBody));
        }
        context.getLogger().log("Raw body: " + rawBody);

        // Parse the URL-encoded form data
        Map<String, String> params = parseFormData(rawBody);
        String from = params.getOrDefault("From", "Unknown");
        String messageBody = params.getOrDefault("Body", "");
        context.getLogger().log("Received message from " + from + " with content: " + messageBody);

        // Retrieve the OpenAI API key from AWS Secrets Manager
        // The key is expected to be stored as a JSON object with a property named
        // "OpenAiApiKey"
        String openAiApiKey = getOpenAiApiKey(context);
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            context.getLogger().log("Failed to retrieve OpenAI API key.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(responseHeaders)
                    .withBody("<Response><Message>Error: API key unavailable.</Message></Response>");
        }

        // Call ChatGPT API with the received message
        String chatGptResponse = callChatGpt(messageBody, openAiApiKey, context);

        // Return the ChatGPT response in a TwiML response
        String twimlResponse = "<Response><Message>" + chatGptResponse + "</Message></Response>";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(responseHeaders)
                .withBody(twimlResponse);
    }

    /**
     * Parses URL-encoded form data (e.g., "key1=value1&key2=value2") into a Map.
     */
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

    /**
     * Retrieves the OpenAI API key from AWS Secrets Manager.
     * Expects the secret to be stored as JSON with a property "OpenAiApiKey".
     */
    public String getOpenAiApiKey(Context context) {
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
            // If the secret is in JSON format, extract the "OpenAiApiKey" property.
            if (secret.trim().startsWith("{")) {
                JsonNode jsonNode = objectMapper.readTree(secret);
                if (jsonNode.has("OpenAiApiKey")) {
                    return jsonNode.get("OpenAiApiKey").asText();
                }
            }
            // Otherwise, return the secret string as-is.
            return secret;
        } catch (Exception e) {
            context.getLogger().log("Error retrieving secret " + secretName + ": " + e.getMessage());
            return null;
        } finally {
            client.close();
        }
    }

    /**
     * Calls the ChatGPT API with the provided prompt and returns its response.
     */
    private String callChatGpt(String prompt, String apiKey, Context context) {
        try {
            // Build the JSON payload for the ChatGPT API
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", "gpt-3.5-turbo",
                    "messages", new Object[] {
                            Map.of("role", "system", "content", "You are ChatGPT."),
                            Map.of("role", "user", "content", prompt)
                    },
                    "temperature", 0.7));
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

            // Parse the JSON response and extract the generated message
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
