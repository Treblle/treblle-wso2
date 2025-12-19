
package com.treblle.wso2publisher.handlers;

import org.apache.axis2.util.URL;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.client.entity.GzipCompressingEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.treblle.wso2publisher.dto.RuntimeError;
import com.treblle.wso2publisher.dto.TrebllePayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PublisherClient is responsible for sending events.
 */
public class PublisherClient {

    // Logger for logging messages
    private static final Log log = LogFactory.getLog(PublisherClient.class);

    // Array of base URLs for the Treblle service
    private static final String[] BASE_URLS = {
            "https://rocknrolla.treblle.com",
            "https://punisher.treblle.com",
            "https://sicario.treblle.com"
    };

    // Round-robin endpoint index for load balancing
    private static final AtomicInteger endpointIndex = new AtomicInteger(0);

    // Array of keywords to be masked in the payload
    private static final String[] MASK_KEYWORDS = {
            "password", "pwd", "secret", "password_confirmation", "cc", "card_number", "ccv", "ssn", "credit_score"};
    List<String> maskKeywordsList = new ArrayList<>(Arrays.asList(MASK_KEYWORDS));

    // Pooled HTTP client for connection reuse
    private final CloseableHttpClient httpClient;

    // SDK Token for authentication
    private String sdkToken;

    // API Key for the Treblle project
    private String apiKey;

    /**
     * Constructor to initialize the PublisherClient with SDK token, API key, and HTTP client.
     *
     * @param sdkToken   the SDK token for authentication
     * @param apiKey     the API key for the Treblle project
     * @param httpClient the pooled HTTP client for connection reuse
     */
    public PublisherClient(String sdkToken, String apiKey, CloseableHttpClient httpClient) {
        this.sdkToken = sdkToken;
        this.apiKey = apiKey;
        this.httpClient = httpClient;

        // Retrieve additional mask keywords from environment variable
        String maskKeywordsEnv = System.getenv("ADDITIONAL_MASK_KEYWORDS");
        if (maskKeywordsEnv != null) {
            String[] maskKeywordsEnvArray = maskKeywordsEnv.split(",");
            maskKeywordsList.addAll(Arrays.asList(maskKeywordsEnvArray));
        }

        log.debug("Masking keywords: " + maskKeywordsList);
    }


    /**
     * Method to retry publishing the payload.
     * Uses a short delay to avoid blocking worker threads for too long.
     *
     * @param payload the TrebllePayload object to be published
     */
    private void doRetry(TrebllePayload payload) {

        Integer currentAttempt = PublisherClientContextHolder.PUBLISH_ATTEMPTS.get();

        if (currentAttempt > 0) {
            currentAttempt -= 1;
            PublisherClientContextHolder.PUBLISH_ATTEMPTS.set(currentAttempt);
            try {
                // Short delay to avoid overwhelming the server, but not blocking worker thread for long
                Thread.sleep(1000);
                publish(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                log.error("Retry attempt interrupted at Publisher client", e);
            }
        } else if (currentAttempt == 0) {
            log.error("Failed all retrying attempts. Event will be dropped for SDK token: " + sdkToken);
        }
    }

    /**
     * publish method is responsible for publishing the event.
     */
    public void publish(TrebllePayload payload) {

        // Null check for payload
        if (payload == null) {
            log.error("Payload is null. Skipping event publication.");
            return;
        }

        // Setting SDK Token and API Key
        payload.setSdkToken(sdkToken);
        payload.setApiKey(apiKey);

        // Check if custom gateway URL is configured, otherwise use round-robin default URL
        String gatewayUrl = System.getenv("TREBLLE_GATEWAY_URL");
        if (gatewayUrl == null || gatewayUrl.trim().isEmpty()) {
            gatewayUrl = getNextBaseUrl();
        }

        int statusCode = 0;
        String reasonPhrase = "";

        // Use try-with-resources to ensure response is properly closed
        try (CloseableHttpResponse response = maskAndSendPayload(payload, gatewayUrl)) {
            if (response != null) {
                statusCode = response.getStatusLine().getStatusCode();
                reasonPhrase = response.getStatusLine().getReasonPhrase();
            }
        } catch (IOException e) {
            log.error("Error closing HTTP response for SDK token: " + sdkToken, e);
        }

        if (statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 204) {
            log.debug("Event successfully published.");
        } else if (statusCode >= 400 && statusCode < 500) {
            log.error("Event publishing failed for SDK token: " + sdkToken + " with status code: " + statusCode
                    + " and reason: " + reasonPhrase);
        } else {
            log.error("Event publishing failed for SDK token: " + sdkToken + ". Retrying...");
            doRetry(payload);
        }

    }

    /**
     * Method to get the next base URL using round-robin load balancing.
     *
     * @return the next base URL in round-robin order
     */
    private static String getNextBaseUrl() {
        int index = Math.abs(endpointIndex.getAndIncrement() % BASE_URLS.length);
        return BASE_URLS[index];
    }


    /**
     * Method to mask sensitive data and send the payload to the Treblle service.
     * Uses gzip compression and pooled HTTP client for optimal performance.
     *
     * @param payload the TrebllePayload object to be sent
     * @param baseUrl the base URL of the Treblle service
     * @return the HTTP response from the Treblle service
     */
    private CloseableHttpResponse maskAndSendPayload(TrebllePayload payload, String baseUrl) {

        final List<RuntimeError> errors = new ArrayList<>(2);
        if (!errors.isEmpty()) {
            payload.getData().setErrors(errors);
        }

        HttpPost httpPost = new HttpPost(baseUrl);
        httpPost.setHeader("x-api-key", payload.getSdkToken());
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        httpPost.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");

        try {
            org.json.JSONObject requestBody = buildRequestBodyForTrebllePayload(payload);
            log.debug("Treblle Payload - " + requestBody);

            // Create entity and wrap with gzip compression
            StringEntity uncompressed = new StringEntity(requestBody.toString());
            GzipCompressingEntity gzipEntity = new GzipCompressingEntity(uncompressed);
            httpPost.setEntity(gzipEntity);

            // Use pooled HTTP client for connection reuse
            return httpClient.execute(httpPost);
        } catch (IOException e) {
            log.error("Error sending payload to Treblle: " + e.getMessage(), e);
        }

        return null;
    }


    /**
     * Method to build the request body for the Treblle payload.
     *
     * @param trebllePayload the TrebllePayload object
     * @return the JSON object representing the request body
     */
    private org.json.JSONObject buildRequestBodyForTrebllePayload(TrebllePayload trebllePayload) {

        org.json.JSONObject requestBody = new org.json.JSONObject();
        requestBody.put("sdk_token", trebllePayload.getSdkToken());
        requestBody.put("api_key", trebllePayload.getApiKey());
        requestBody.put("sdk", "wso2");
        requestBody.put("version", TrebllePayload.TREBLLE_VERSION);

        // Add internal_id and internal_name if available
        if (trebllePayload.getInternalId() != null) {
            requestBody.put("internal_id", trebllePayload.getInternalId());
        }
        if (trebllePayload.getInternalName() != null) {
            requestBody.put("internal_name", trebllePayload.getInternalName());
        }

        org.json.JSONObject data = new org.json.JSONObject();
        data.put("language", new org.json.JSONObject(trebllePayload.getData().getLanguage()));

        org.json.JSONObject request = new org.json.JSONObject();
        request.put("timestamp", trebllePayload.getData().getRequest().getTimestamp());
        request.put("ip", trebllePayload.getData().getRequest().getIp());
        request.put("user_agent", trebllePayload.getData().getRequest().getUserAgent());
        request.put("method", trebllePayload.getData().getRequest().getMethod());
        request.put("url", trebllePayload.getData().getRequest().getUrl());
        request.put("headers", new org.json.JSONObject(trebllePayload.getData().getRequest().getHeaders()));

        // Add route_path if available
        if (trebllePayload.getData().getRequest().getRoutePath() != null) {
            request.put("route_path", trebllePayload.getData().getRequest().getRoutePath());
        }

        JsonNode reqBody = trebllePayload.getData().getRequest().getBody();
        if (reqBody != null) {
            request.put("body", convertJsonNodeToOrgJson(reqBody));
        } else {
            request.put("body", new org.json.JSONObject());
        }

        data.put("request", request);

        org.json.JSONObject response = new org.json.JSONObject();
        response.put("code", trebllePayload.getData().getResponse().getCode());
        response.put("size", trebllePayload.getData().getResponse().getSize());
        response.put("headers", new org.json.JSONObject(trebllePayload.getData().getResponse().getHeaders()));
        response.put("load_time", trebllePayload.getData().getResponse().getLoadTime());

        JsonNode responseBody = trebllePayload.getData().getResponse().getBody();
        if (responseBody != null) {
            response.put("body", convertJsonNodeToOrgJson(responseBody));
        } else {
            response.put("body", new org.json.JSONObject());
        }

        data.put("response", response);
        data.put("server", new org.json.JSONObject(trebllePayload.getData().getServer()));
        data.put("errors", new org.json.JSONArray(trebllePayload.getData().getErrors()));

        for (String keyword : maskKeywordsList) {
            maskKeywordInJson(data, keyword);
        }
        requestBody.put("data", data);

        return requestBody;
    }

    /**
     * Method to mask sensitive keywords in the JSON object.
     *
     * @param jsonObject the JSON object to be masked
     * @param keyword    the keyword to be masked
     */
    private void maskKeywordInJson(org.json.JSONObject jsonObject, String keyword) {
        String lowerCaseKeyword = keyword.toLowerCase();
        for (Object key : jsonObject.keySet()) {
            String lowerCaseKey = key.toString().toLowerCase();
            if (lowerCaseKey.equals(lowerCaseKeyword)) {
                jsonObject.put(key.toString(), "****");
            } else {
                Object value = jsonObject.get(key.toString());
                if (value instanceof org.json.JSONObject) {
                    maskKeywordInJson((org.json.JSONObject) value, keyword);
                }
            }
        }
    }

    /**
     * Convert a Jackson JsonNode to an appropriate org.json object type.
     * Handles different node types (Object, Array, String, Number, Boolean, Null).
     *
     * @param node the Jackson JsonNode to convert
     * @return the appropriate org.json type (JSONObject, JSONArray, String, Number, Boolean, or JSONObject.NULL)
     */
    private Object convertJsonNodeToOrgJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return org.json.JSONObject.NULL;
        }

        try {
            if (node.isObject()) {
                return new org.json.JSONObject(node.toString());
            } else if (node.isArray()) {
                return new org.json.JSONArray(node.toString());
            } else if (node.isBoolean()) {
                return node.asBoolean();
            } else if (node.isNumber()) {
                return node.numberValue();
            } else if (node.isTextual()) {
                return node.asText();
            } else {
                // Fallback: try to parse as generic JSON
                return new org.json.JSONObject(node.toString());
            }
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to org.json type: " + e.getMessage() + ". Using empty object as fallback.");
            return new org.json.JSONObject();
        }
    }

}
